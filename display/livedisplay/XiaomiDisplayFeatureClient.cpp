/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "XiaomiDisplayFeatureClient.h"

#include <android/binder_manager.h>

#include <string>
#include <utility>

using aidl::vendor::xiaomi::hardware::displayfeature_aidl::IDisplayFeature;

namespace {
constexpr char kRemoteName[] =
    "vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature/default";
constexpr int32_t kExpectedVersion = 2;
constexpr char kExpectedHash[] = "b25c84c1c5a4d0e73a8fec6a2341f2d98ad40ce3";
}  // namespace

XiaomiDisplayFeatureClient::XiaomiDisplayFeatureClient(DeathCallback death_callback,
                                                       Lookup lookup, Link link)
    : death_recipient_(AIBinder_DeathRecipient_new(onDeath)),
      death_callback_(std::move(death_callback)),
      lookup_(std::move(lookup)),
      link_(std::move(link)) {
  AIBinder_DeathRecipient_setOnUnlinked(death_recipient_.get(), onUnlinked);
}

::ndk::ScopedAStatus XiaomiDisplayFeatureClient::connect() {
  ::ndk::SpAIBinder binder = lookup_ ? lookup_(kRemoteName)
                                     : ::ndk::SpAIBinder(AServiceManager_checkService(kRemoteName));
  if (binder == nullptr) {
    return ::ndk::ScopedAStatus::fromStatus(STATUS_NAME_NOT_FOUND);
  }

  auto remote = IDisplayFeature::fromBinder(binder);
  if (remote == nullptr) {
    return ::ndk::ScopedAStatus::fromStatus(STATUS_BAD_TYPE);
  }

  int32_t version = 0;
  auto status = remote->getInterfaceVersion(&version);
  if (!status.isOk()) {
    return status;
  }

  std::string hash;
  status = remote->getInterfaceHash(&hash);
  if (!status.isOk()) {
    return status;
  }
  if (version != kExpectedVersion || hash != kExpectedHash) {
    return ::ndk::ScopedAStatus::fromStatus(STATUS_BAD_TYPE);
  }

  auto* cookie = new Cookie{death_callback_};
  binder_status_t link_status;
  if (link_) {
    link_status = link_(binder.get(), death_recipient_.get(), cookie);
    if (link_status != STATUS_OK) {
      delete cookie;
    }
  } else {
    link_status = AIBinder_linkToDeath(binder.get(), death_recipient_.get(), cookie);
    // The NDK invokes onUnlinked even when linkToDeath fails, so it owns
    // cookie cleanup after this call.
  }
  if (link_status != STATUS_OK) {
    return ::ndk::ScopedAStatus::fromStatus(link_status);
  }

  if (!AIBinder_isAlive(binder.get())) {
    return ::ndk::ScopedAStatus::fromStatus(STATUS_DEAD_OBJECT);
  }

  remote_ = std::move(remote);
  return ::ndk::ScopedAStatus::ok();
}

dash::HalStatus XiaomiDisplayFeatureClient::setFeature(int32_t displayId, int32_t feature,
                                                       int32_t value, int32_t cookie) {
  if (remote_ == nullptr) {
    return dash::HalStatus::kDead;
  }
  auto status = remote_->setFeature(displayId, feature, value, cookie);
  if (status.isOk()) {
    return dash::HalStatus::kOk;
  }
  if (status.getStatus() == STATUS_DEAD_OBJECT) {
    return dash::HalStatus::kDead;
  }
  return dash::HalStatus::kError;
}

void XiaomiDisplayFeatureClient::onDeath(void* raw_cookie) {
  auto* cookie = static_cast<Cookie*>(raw_cookie);
  if (cookie->death_callback) {
    cookie->death_callback();
  }
}

void XiaomiDisplayFeatureClient::onUnlinked(void* raw_cookie) {
  delete static_cast<Cookie*>(raw_cookie);
}
