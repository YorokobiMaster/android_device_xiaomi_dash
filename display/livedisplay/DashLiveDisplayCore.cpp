/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "DashLiveDisplayCore.h"

#include <android-base/logging.h>

#include <cstdlib>
#include <utility>

namespace dash {

namespace {

// Command model and evidence: docs/dash/livedisplay-v1-contract.md.
constexpr int32_t kDisplayId = 0;
constexpr int32_t kFeatureScreenAdapt = 0;
constexpr int32_t kFeatureScreenStandard = 2;
constexpr int32_t kFeatureScreenExpert = 26;
constexpr int32_t kNoCookie = 0xff;
// Stock default screen_color_level (SCREEN_COLOR_NATURE).
constexpr int32_t kColorLevelNature = 2;
constexpr int32_t kGamutP3 = 2;
constexpr int32_t kGamutSrgb = 3;

// Expert mode is nine cookie writes in ascending order; cookie 0 selects the
// gamut, the rest carry the neutral dash.xml defaults.
std::vector<SetFeatureCommand> expertCommands(int32_t gamut) {
  return {
      {kFeatureScreenExpert, gamut, 0},  // gamut
      {kFeatureScreenExpert, 255, 1},    // R
      {kFeatureScreenExpert, 255, 2},    // G
      {kFeatureScreenExpert, 255, 3},    // B
      {kFeatureScreenExpert, 0, 4},      // hue
      {kFeatureScreenExpert, 0, 5},      // saturation
      {kFeatureScreenExpert, 0, 6},      // value
      {kFeatureScreenExpert, 0, 7},      // contrast
      {kFeatureScreenExpert, 220, 8},    // gamma
  };
}

}  // namespace

const std::vector<ColorProfile>& DashLiveDisplayCore::profiles() {
  // Names resolve through LineageParts' live_display_color_profile_<name>_title
  // string keys, which carry translations for every locale.
  static const std::vector<ColorProfile> kProfiles = {
      {0, "Dynamic", {{kFeatureScreenAdapt, kColorLevelNature, kNoCookie}}},
      {1, "Natural", {{kFeatureScreenStandard, kColorLevelNature, kNoCookie}}},
      {2, "DCI-P3", expertCommands(kGamutP3)},
      {3, "sRGB", expertCommands(kGamutSrgb)},
  };
  return kProfiles;
}

const ColorProfile* DashLiveDisplayCore::findProfile(int32_t id) {
  for (const ColorProfile& profile : profiles()) {
    if (profile.id == id) {
      return &profile;
    }
  }
  return nullptr;
}

DashLiveDisplayCore::DashLiveDisplayCore(HalFactory hal_factory, PropertyGet property_get,
                                         PropertySet property_set)
    : hal_factory_(std::move(hal_factory)),
      property_get_(std::move(property_get)),
      property_set_(std::move(property_set)) {
  if (!property_get_) {
    property_get_ = [](const std::string&) { return std::string(); };
  }
  if (!property_set_) {
    property_set_ = [](const std::string&, const std::string&) { return false; };
  }
}

void DashLiveDisplayCore::setHalFactory(HalFactory hal_factory) {
  std::lock_guard lock(mutex_);
  hal_factory_ = std::move(hal_factory);
}

int32_t DashLiveDisplayCore::currentModeId() {
  std::lock_guard lock(mutex_);
  return current_id_;
}

int32_t DashLiveDisplayCore::defaultModeId() {
  std::lock_guard lock(mutex_);
  return persistedDefaultLocked();
}

ApplyResult DashLiveDisplayCore::setMode(int32_t id, bool makeDefault) {
  std::lock_guard lock(mutex_);

  if (findProfile(id) == nullptr) {
    return ApplyResult::kUnknownMode;
  }

  ApplyResult result = ensureConnectedLocked();
  if (result != ApplyResult::kOk) {
    return result;
  }

  result = applyLocked(id);
  if (result != ApplyResult::kOk) {
    return result;
  }

  if (makeDefault && !property_set_(kPersistModeProperty, std::to_string(id))) {
    LOG(ERROR) << "Failed to persist " << kPersistModeProperty << "=" << id;
    return ApplyResult::kPersistError;
  }
  LOG(INFO) << "Set display mode " << id << (makeDefault ? " (persisted)" : "");
  return ApplyResult::kOk;
}

ApplyResult DashLiveDisplayCore::ensureConnected() {
  std::lock_guard lock(mutex_);
  return ensureConnectedLocked();
}

void DashLiveDisplayCore::awaitDisconnected() {
  std::unique_lock lock(mutex_);
  disconnected_cv_.wait(lock, [this] { return !hal_connected_; });
}

void DashLiveDisplayCore::notifyHalDeath() {
  std::lock_guard lock(mutex_);
  if (hal_connected_) {
    LOG(WARNING) << "Xiaomi displayfeature HAL died; will reconnect and replay";
    markDisconnectedLocked();
  }
}

ApplyResult DashLiveDisplayCore::ensureConnectedLocked() {
  if (hal_connected_) {
    return ApplyResult::kOk;
  }
  if (!hal_factory_) {
    return ApplyResult::kHalUnavailable;
  }

  auto hal = hal_factory_();
  if (hal == nullptr) {
    return ApplyResult::kHalUnavailable;
  }
  hal_ = std::move(hal);
  hal_connected_ = true;

  const bool replay = applied_;
  const int32_t target = replay ? current_id_ : persistedDefaultLocked();
  ApplyResult result = applyLocked(target);
  if (result != ApplyResult::kOk) {
    // A failed first apply must not look connected: the monitor loop would
    // otherwise treat the next ensureConnected() as a no-op and never retry.
    markDisconnectedLocked();
    return result;
  }
  LOG(INFO) << "Applied display mode " << target << (replay ? " (replayed)" : " (default)");
  return ApplyResult::kOk;
}

ApplyResult DashLiveDisplayCore::applyLocked(int32_t id) {
  const ColorProfile* profile = findProfile(id);
  for (const SetFeatureCommand& command : profile->commands) {
    HalStatus status =
        hal_->setFeature(kDisplayId, command.feature, command.value, command.cookie);
    if (status != HalStatus::kOk) {
      LOG(ERROR) << "setFeature(displayId=" << kDisplayId << ", feature=" << command.feature
                 << ", value=" << command.value << ", cookie=" << command.cookie << ") failed";
      if (status == HalStatus::kDead) {
        markDisconnectedLocked();
        return ApplyResult::kHalDead;
      }
      return ApplyResult::kHalError;
    }
  }
  current_id_ = id;
  applied_ = true;
  return ApplyResult::kOk;
}

int32_t DashLiveDisplayCore::persistedDefaultLocked() {
  const std::string value = property_get_(kPersistModeProperty);
  if (!value.empty()) {
    char* end = nullptr;
    long id = std::strtol(value.c_str(), &end, 10);
    if (end != value.c_str() && *end == '\0' && findProfile(static_cast<int32_t>(id)) != nullptr) {
      return static_cast<int32_t>(id);
    }
    LOG(WARNING) << "Ignoring malformed " << kPersistModeProperty << "=\"" << value << "\"";
  }
  return kDefaultModeId;
}

void DashLiveDisplayCore::markDisconnectedLocked() {
  hal_connected_ = false;
  hal_.reset();
  disconnected_cv_.notify_all();
}

}  // namespace dash
