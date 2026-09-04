/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <aidl/vendor/xiaomi/hardware/displayfeature_aidl/IDisplayFeature.h>
#include <android/binder_auto_utils.h>
#include <android/binder_ibinder.h>
#include <android/binder_status.h>

#include <functional>
#include <memory>

#include "DashLiveDisplayCore.h"

// NDK client of the stock
// vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature/default instance.
// The remote interface version and hash are pinned: the wire contract was
// recovered from the exact stock build in docs/dash/livedisplay-v1-contract.md
// and any drift means the retained vendor changed underneath us.
class XiaomiDisplayFeatureClient final : public dash::DisplayFeatureBackend {
 public:
  using DeathCallback = std::function<void()>;
  using Lookup = std::function<::ndk::SpAIBinder(const char*)>;
  using Link = std::function<binder_status_t(AIBinder*, AIBinder_DeathRecipient*, void*)>;

  explicit XiaomiDisplayFeatureClient(DeathCallback death_callback, Lookup lookup = nullptr,
                                      Link link = nullptr);

  // Resolves the stock service, verifies the pinned version/hash and links to
  // death. Must succeed once before setFeature is used.
  ::ndk::ScopedAStatus connect();

  dash::HalStatus setFeature(int32_t displayId, int32_t feature, int32_t value,
                             int32_t cookie) override;

 private:
  struct Cookie {
    DeathCallback death_callback;
  };

  static void onDeath(void* cookie);
  static void onUnlinked(void* cookie);

  std::shared_ptr<aidl::vendor::xiaomi::hardware::displayfeature_aidl::IDisplayFeature> remote_;
  ::ndk::ScopedAIBinder_DeathRecipient death_recipient_;
  DeathCallback death_callback_;
  Lookup lookup_;
  Link link_;
};
