/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "DashDisplayModes.h"

#include <android-base/logging.h>
#include <android-base/properties.h>

using aidl::vendor::lineage::livedisplay::DisplayMode;

namespace {

::ndk::ScopedAStatus toStatus(dash::ApplyResult result) {
  switch (result) {
    case dash::ApplyResult::kOk:
      return ::ndk::ScopedAStatus::ok();
    case dash::ApplyResult::kUnknownMode:
      return ::ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    case dash::ApplyResult::kHalUnavailable:
      return ::ndk::ScopedAStatus::fromStatus(STATUS_NAME_NOT_FOUND);
    case dash::ApplyResult::kHalDead:
      return ::ndk::ScopedAStatus::fromStatus(STATUS_DEAD_OBJECT);
    case dash::ApplyResult::kHalError:
    case dash::ApplyResult::kPersistError:
      break;
  }
  return ::ndk::ScopedAStatus::fromStatus(STATUS_UNKNOWN_ERROR);
}

DisplayMode toAidl(int32_t id) {
  const dash::ColorProfile* profile = dash::DashLiveDisplayCore::findProfile(id);
  return {profile->id, profile->name};
}

}  // namespace

DashDisplayModes::DashDisplayModes(dash::DashLiveDisplayCore::PropertyGet property_get,
                                   dash::DashLiveDisplayCore::PropertySet property_set)
    : core_(nullptr, property_get ? std::move(property_get)
                                  : dash::DashLiveDisplayCore::PropertyGet(
                                        [](const std::string& name) {
                                          return android::base::GetProperty(name, "");
                                        }),
            property_set ? std::move(property_set)
                         : dash::DashLiveDisplayCore::PropertySet(
                               [](const std::string& name, const std::string& value) {
                                 return android::base::SetProperty(name, value);
                               })) {}

::ndk::ScopedAStatus DashDisplayModes::getDisplayModes(std::vector<DisplayMode>* out) {
  out->clear();
  for (const dash::ColorProfile& profile : dash::DashLiveDisplayCore::profiles()) {
    out->push_back({profile.id, profile.name});
  }
  return ::ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus DashDisplayModes::getCurrentDisplayMode(DisplayMode* out) {
  *out = toAidl(core_.currentModeId());
  return ::ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus DashDisplayModes::getDefaultDisplayMode(DisplayMode* out) {
  *out = toAidl(core_.defaultModeId());
  return ::ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus DashDisplayModes::setDisplayMode(int32_t modeID, bool makeDefault) {
  return toStatus(core_.setMode(modeID, makeDefault));
}
