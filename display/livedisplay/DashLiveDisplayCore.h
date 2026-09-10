/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

// Binder-free core of the dash LiveDisplay adapter: profile table, command
// sequences, current/default state, persistence, expert-mode transactions and
// replay rules from docs/dash/livedisplay-v1-contract.md. Kept free of
// binder/AIDL headers so host tests can link it without the AIDL runtimes.
namespace dash {

constexpr char kPersistModeProperty[] = "persist.dash.livedisplay.mode";
// Derived from Night Light by DashEyeCare; the service only reads it. This keeps the
// warm eye-care toggle independent from LineageOS' own reading enhancement.
constexpr char kEyeCareProperty[] = "sys.dash.livedisplay.eyecare";
constexpr int32_t kDefaultModeId = 1;  // stock default: original color

struct SetFeatureCommand {
  int32_t feature;
  int32_t value;
  int32_t cookie;
};

struct ColorProfile {
  int32_t id;
  const char* name;
  std::vector<SetFeatureCommand> commands;
};

enum class HalStatus { kOk, kError, kDead };

// Backend boundary to the retained stock Xiaomi displayfeature HAL. The
// device adapter implements this over NDK binder; host tests substitute fakes.
class DisplayFeatureBackend {
 public:
  virtual ~DisplayFeatureBackend() = default;

  virtual HalStatus setFeature(int32_t displayId, int32_t feature, int32_t value,
                               int32_t cookie) = 0;
};

enum class ApplyResult { kOk, kUnknownMode, kHalUnavailable, kHalError, kHalDead, kPersistError };

class DashLiveDisplayCore {
 public:
  // Returns a connected backend, or nullptr while the HAL is unavailable.
  using HalFactory = std::function<std::unique_ptr<DisplayFeatureBackend>()>;
  using PropertyGet = std::function<std::string(const std::string&)>;
  using PropertySet = std::function<bool(const std::string&, const std::string&)>;

  explicit DashLiveDisplayCore(HalFactory hal_factory = nullptr,
                               PropertyGet property_get = nullptr,
                               PropertySet property_set = nullptr);

  static const std::vector<ColorProfile>& profiles();
  static const ColorProfile* findProfile(int32_t id);

  void setHalFactory(HalFactory hal_factory);

  int32_t currentModeId();
  int32_t defaultModeId();
  ApplyResult setMode(int32_t id, bool makeDefault);

  // Warm eye-care (displayfeature SCREEN_EYECARE), toggled through
  // kEyeCareProperty by DashEyeCare. Night Light owns persistence;
  // the service only applies the property value and replays it on connect.
  using Clock = std::chrono::steady_clock;
  ApplyResult applyEyeCareFromProperty(Clock::time_point now = Clock::now());
  bool eyeCareTransitionPending();

  // Monitor-thread interface. ensureConnected() connects when the link is
  // down and then applies the persisted default (first connect) or replays
  // the current profile (HAL reconnect). No-op while connected.
  ApplyResult ensureConnected();
  void awaitDisconnected();
  void notifyHalDeath();

 private:
  ApplyResult ensureConnectedLocked();
  ApplyResult applyLocked(int32_t id);
  ApplyResult applyEyeCareLocked(int32_t level);
  int32_t persistedDefaultLocked();
  int32_t requestedEyeCareLocked();
  void markDisconnectedLocked();

  std::mutex mutex_;
  std::condition_variable disconnected_cv_;
  HalFactory hal_factory_;
  PropertyGet property_get_;
  PropertySet property_set_;
  std::unique_ptr<DisplayFeatureBackend> hal_;
  bool hal_connected_ = false;
  // Nothing applied yet: the first successful connect applies the persisted
  // default instead of replaying current_id_.
  bool applied_ = false;
  int32_t current_id_ = kDefaultModeId;
  int32_t eyecare_level_ = 0;
  int32_t eyecare_target_ = 0;
  int32_t eyecare_start_level_ = 0;
  Clock::time_point eyecare_start_time_{};
  bool eyecare_transition_ = false;
};

}  // namespace dash
