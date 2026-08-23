/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <aidl/android/hardware/vibrator/BnVibrator.h>
#include <aidl/android/hardware/vibrator/IVibrator.h>
#include <android/binder_ibinder.h>
#include <android/binder_status.h>

#include <functional>
#include <memory>

class DashVibratorAdapter final : public aidl::android::hardware::vibrator::BnVibrator {
 public:
  using Lookup = std::function<::ndk::SpAIBinder(const char*)>;
  using Link = std::function<binder_status_t(AIBinder*, AIBinder_DeathRecipient*, void*)>;
  using DeathAction = std::function<void()>;

  explicit DashVibratorAdapter(Lookup lookup = nullptr, Link link = nullptr,
                               DeathAction death_action = nullptr);

  ::ndk::ScopedAStatus getCapabilities(int32_t*) override;
  ::ndk::ScopedAStatus off() override;
  ::ndk::ScopedAStatus on(
      int32_t, const std::shared_ptr<aidl::android::hardware::vibrator::IVibratorCallback>&)
      override;
  ::ndk::ScopedAStatus perform(
      aidl::android::hardware::vibrator::Effect,
      aidl::android::hardware::vibrator::EffectStrength,
      const std::shared_ptr<aidl::android::hardware::vibrator::IVibratorCallback>&, int32_t*)
      override;
  ::ndk::ScopedAStatus getSupportedEffects(
      std::vector<aidl::android::hardware::vibrator::Effect>*) override;
  ::ndk::ScopedAStatus setAmplitude(float) override;
  ::ndk::ScopedAStatus setExternalControl(bool) override;
  ::ndk::ScopedAStatus getCompositionDelayMax(int32_t*) override;
  ::ndk::ScopedAStatus getCompositionSizeMax(int32_t*) override;
  ::ndk::ScopedAStatus getSupportedPrimitives(
      std::vector<aidl::android::hardware::vibrator::CompositePrimitive>*) override;
  ::ndk::ScopedAStatus getPrimitiveDuration(
      aidl::android::hardware::vibrator::CompositePrimitive, int32_t*) override;
  ::ndk::ScopedAStatus compose(
      const std::vector<aidl::android::hardware::vibrator::CompositeEffect>&,
      const std::shared_ptr<aidl::android::hardware::vibrator::IVibratorCallback>&) override;
  ::ndk::ScopedAStatus getSupportedAlwaysOnEffects(
      std::vector<aidl::android::hardware::vibrator::Effect>*) override;
  ::ndk::ScopedAStatus alwaysOnEnable(
      int32_t, aidl::android::hardware::vibrator::Effect,
      aidl::android::hardware::vibrator::EffectStrength) override;
  ::ndk::ScopedAStatus alwaysOnDisable(int32_t) override;

  ::ndk::ScopedAStatus connect();
  static void notifyDeathForTest(void* cookie);
  static void releaseCookieForTest(void* cookie);

 private:
  struct Cookie {
    DeathAction death_action;
  };

  static void onDeath(void* cookie);
  static void onUnlinked(void* cookie);

  std::shared_ptr<aidl::android::hardware::vibrator::IVibrator> remote_;
  ::ndk::ScopedAIBinder_DeathRecipient death_recipient_;
  Lookup lookup_;
  Link link_;
  DeathAction death_action_;
};
