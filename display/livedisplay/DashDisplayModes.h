/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <aidl/vendor/lineage/livedisplay/BnDisplayModes.h>
#include <android/binder_auto_utils.h>

#include "DashLiveDisplayCore.h"

// Thin vendor.lineage.livedisplay.IDisplayModes adapter over
// DashLiveDisplayCore. All state lives in the core; this class only
// translates AIDL types and status codes.
class DashDisplayModes final
    : public aidl::vendor::lineage::livedisplay::BnDisplayModes {
 public:
  DashDisplayModes(dash::DashLiveDisplayCore::PropertyGet property_get = nullptr,
                   dash::DashLiveDisplayCore::PropertySet property_set = nullptr);

  ::ndk::ScopedAStatus getDisplayModes(
      std::vector<aidl::vendor::lineage::livedisplay::DisplayMode>* out) override;
  ::ndk::ScopedAStatus getCurrentDisplayMode(
      aidl::vendor::lineage::livedisplay::DisplayMode* out) override;
  ::ndk::ScopedAStatus getDefaultDisplayMode(
      aidl::vendor::lineage::livedisplay::DisplayMode* out) override;
  ::ndk::ScopedAStatus setDisplayMode(int32_t modeID, bool makeDefault) override;

  dash::DashLiveDisplayCore& core() { return core_; }

 private:
  dash::DashLiveDisplayCore core_;
};
