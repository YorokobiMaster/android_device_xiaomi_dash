/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "DashVibratorAdapter.h"

#include <android/binder_manager.h>
#include <android/binder_process.h>

#include <cstdlib>
#include <utility>
#include <vector>
#include <unistd.h>

using aidl::android::hardware::vibrator::CompositeEffect;
using aidl::android::hardware::vibrator::CompositePrimitive;
using aidl::android::hardware::vibrator::Effect;
using aidl::android::hardware::vibrator::EffectStrength;
using aidl::android::hardware::vibrator::IVibrator;
using aidl::android::hardware::vibrator::IVibratorCallback;

namespace {
constexpr char kRemoteName[] = "android.hardware.vibrator.IVibrator/vibratorfeature";
constexpr int32_t kVersion = 1;
constexpr char kHash[] = "eeab78b6096b029f424ab5ce9c2c4ef1249a5cb0";
}  // namespace

DashVibratorAdapter::DashVibratorAdapter(Lookup lookup, Link link, DeathAction death_action)
    : death_recipient_(AIBinder_DeathRecipient_new(onDeath)),
      lookup_(std::move(lookup)),
      link_(std::move(link)),
      death_action_(std::move(death_action)) {
  if (!death_action_) {
    death_action_ = [] { _exit(EXIT_FAILURE); };
  }
  AIBinder_DeathRecipient_setOnUnlinked(death_recipient_.get(), onUnlinked);
}

::ndk::ScopedAStatus DashVibratorAdapter::connect() {
  ::ndk::SpAIBinder binder = lookup_ ? lookup_(kRemoteName)
                                     : ::ndk::SpAIBinder(AServiceManager_checkService(kRemoteName));
  if (binder == nullptr) {
    return ::ndk::ScopedAStatus::fromStatus(STATUS_NAME_NOT_FOUND);
  }

  auto remote = IVibrator::fromBinder(binder);
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
  if (version != kVersion || hash != kHash) {
    return ::ndk::ScopedAStatus::fromStatus(STATUS_BAD_TYPE);
  }

  auto* cookie = new Cookie{death_action_};
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

::ndk::ScopedAStatus DashVibratorAdapter::getCapabilities(int32_t* out) {
  return remote_->getCapabilities(out);
}

::ndk::ScopedAStatus DashVibratorAdapter::off() {
  return remote_->off();
}

::ndk::ScopedAStatus DashVibratorAdapter::on(
    int32_t timeout_ms, const std::shared_ptr<IVibratorCallback>& callback) {
  return remote_->on(timeout_ms, callback);
}

::ndk::ScopedAStatus DashVibratorAdapter::perform(
    Effect effect, EffectStrength strength, const std::shared_ptr<IVibratorCallback>& callback,
    int32_t* out) {
  return remote_->perform(effect, strength, callback, out);
}

::ndk::ScopedAStatus DashVibratorAdapter::getSupportedEffects(std::vector<Effect>* out) {
  return remote_->getSupportedEffects(out);
}

::ndk::ScopedAStatus DashVibratorAdapter::setAmplitude(float amplitude) {
  return remote_->setAmplitude(amplitude);
}

::ndk::ScopedAStatus DashVibratorAdapter::setExternalControl(bool enabled) {
  return remote_->setExternalControl(enabled);
}

::ndk::ScopedAStatus DashVibratorAdapter::getCompositionDelayMax(int32_t* out) {
  return remote_->getCompositionDelayMax(out);
}

::ndk::ScopedAStatus DashVibratorAdapter::getCompositionSizeMax(int32_t* out) {
  return remote_->getCompositionSizeMax(out);
}

::ndk::ScopedAStatus DashVibratorAdapter::getSupportedPrimitives(
    std::vector<CompositePrimitive>* out) {
  return remote_->getSupportedPrimitives(out);
}

::ndk::ScopedAStatus DashVibratorAdapter::getPrimitiveDuration(CompositePrimitive primitive,
                                                               int32_t* out) {
  return remote_->getPrimitiveDuration(primitive, out);
}

::ndk::ScopedAStatus DashVibratorAdapter::compose(
    const std::vector<CompositeEffect>& composite,
    const std::shared_ptr<IVibratorCallback>& callback) {
  return remote_->compose(composite, callback);
}

::ndk::ScopedAStatus DashVibratorAdapter::getSupportedAlwaysOnEffects(std::vector<Effect>* out) {
  return remote_->getSupportedAlwaysOnEffects(out);
}

::ndk::ScopedAStatus DashVibratorAdapter::alwaysOnEnable(int32_t id, Effect effect,
                                                         EffectStrength strength) {
  return remote_->alwaysOnEnable(id, effect, strength);
}

::ndk::ScopedAStatus DashVibratorAdapter::alwaysOnDisable(int32_t id) {
  return remote_->alwaysOnDisable(id);
}

void DashVibratorAdapter::onDeath(void* raw_cookie) {
  static_cast<Cookie*>(raw_cookie)->death_action();
}

void DashVibratorAdapter::onUnlinked(void* raw_cookie) {
  delete static_cast<Cookie*>(raw_cookie);
}

void DashVibratorAdapter::notifyDeathForTest(void* cookie) {
  onDeath(cookie);
}

void DashVibratorAdapter::releaseCookieForTest(void* cookie) {
  onUnlinked(cookie);
}
