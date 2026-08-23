/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "../DashVibratorAdapter.h"

#include <aidl/android/hardware/vibrator/BnVibratorCallback.h>
#include <gtest/gtest.h>

#include <cstdlib>
#include <memory>
#include <utility>

using namespace aidl::android::hardware::vibrator;

namespace {
class FakeCallback final : public BnVibratorCallback {
 public:
  int completions = 0;

  ::ndk::ScopedAStatus onComplete() override {
    ++completions;
    return ::ndk::ScopedAStatus::ok();
  }
};

class FakeVibrator final : public BnVibrator {
 public:
  int calls = 0;
  int32_t timeout_ms = 0;
  int32_t always_on_id = 0;
  float amplitude = 0.0f;
  bool external_control = false;
  Effect effect = Effect::CLICK;
  EffectStrength strength = EffectStrength::LIGHT;
  CompositePrimitive primitive = CompositePrimitive::NOOP;
  std::vector<CompositeEffect> composite;
  std::shared_ptr<IVibratorCallback> callback;
  ::ndk::ScopedAStatus next_status = ::ndk::ScopedAStatus::ok();

  ::ndk::ScopedAStatus getCapabilities(int32_t* out) override {
    ++calls;
    *out = 17;
    return TakeStatus();
  }
  ::ndk::ScopedAStatus off() override {
    ++calls;
    return TakeStatus();
  }
  ::ndk::ScopedAStatus on(int32_t timeout, const std::shared_ptr<IVibratorCallback>& cb) override {
    ++calls;
    timeout_ms = timeout;
    callback = cb;
    return TakeStatus();
  }
  ::ndk::ScopedAStatus perform(Effect value, EffectStrength value_strength,
                               const std::shared_ptr<IVibratorCallback>& cb,
                               int32_t* out) override {
    ++calls;
    effect = value;
    strength = value_strength;
    callback = cb;
    *out = 18;
    return TakeStatus();
  }
  ::ndk::ScopedAStatus getSupportedEffects(std::vector<Effect>* out) override {
    ++calls;
    *out = {effect};
    return TakeStatus();
  }
  ::ndk::ScopedAStatus setAmplitude(float value) override {
    ++calls;
    amplitude = value;
    return TakeStatus();
  }
  ::ndk::ScopedAStatus setExternalControl(bool value) override {
    ++calls;
    external_control = value;
    return TakeStatus();
  }
  ::ndk::ScopedAStatus getCompositionDelayMax(int32_t* out) override {
    ++calls;
    *out = 19;
    return TakeStatus();
  }
  ::ndk::ScopedAStatus getCompositionSizeMax(int32_t* out) override {
    ++calls;
    *out = 20;
    return TakeStatus();
  }
  ::ndk::ScopedAStatus getSupportedPrimitives(std::vector<CompositePrimitive>* out) override {
    ++calls;
    *out = {primitive};
    return TakeStatus();
  }
  ::ndk::ScopedAStatus getPrimitiveDuration(CompositePrimitive value, int32_t* out) override {
    ++calls;
    primitive = value;
    *out = 21;
    return TakeStatus();
  }
  ::ndk::ScopedAStatus compose(const std::vector<CompositeEffect>& value,
                               const std::shared_ptr<IVibratorCallback>& cb) override {
    ++calls;
    composite = value;
    callback = cb;
    return TakeStatus();
  }
  ::ndk::ScopedAStatus getSupportedAlwaysOnEffects(std::vector<Effect>* out) override {
    ++calls;
    *out = {effect};
    return TakeStatus();
  }
  ::ndk::ScopedAStatus alwaysOnEnable(int32_t id, Effect value,
                                       EffectStrength value_strength) override {
    ++calls;
    always_on_id = id;
    effect = value;
    strength = value_strength;
    return TakeStatus();
  }
  ::ndk::ScopedAStatus alwaysOnDisable(int32_t id) override {
    ++calls;
    always_on_id = id;
    return TakeStatus();
  }

 private:
  ::ndk::ScopedAStatus TakeStatus() {
    auto status = std::move(next_status);
    next_status = ::ndk::ScopedAStatus::ok();
    return status;
  }
};

struct CookieCollector {
  std::vector<void*> cookies;
  binder_status_t status = STATUS_OK;

  binder_status_t operator()(AIBinder*, AIBinder_DeathRecipient*, void* cookie) {
    if (status == STATUS_OK) {
      cookies.push_back(cookie);
    }
    return status;
  }

  ~CookieCollector() {
    release();
  }

  void release() {
    for (void* cookie : cookies) {
      DashVibratorAdapter::releaseCookieForTest(cookie);
    }
    cookies.clear();
  }
};

TEST(DashVibratorAdapterTest, ForwardsFrozenV1ArgumentsOutputsAndCallback) {
  auto fake = ndk::SharedRefBase::make<FakeVibrator>();
  auto callback = ndk::SharedRefBase::make<FakeCallback>();
  CookieCollector links;
  auto adapter = ndk::SharedRefBase::make<DashVibratorAdapter>(
      [fake](const char*) { return fake->asBinder(); },
      [&links](AIBinder* binder, AIBinder_DeathRecipient* recipient, void* cookie) {
        return links(binder, recipient, cookie);
      });

  ASSERT_TRUE(adapter->connect().isOk());
  int32_t value = 0;
  std::vector<Effect> effects;
  std::vector<CompositePrimitive> primitives;
  CompositeEffect effect;
  effect.primitive = CompositePrimitive::CLICK;
  effect.scale = 0.5f;
  effect.delayMs = 4;
  const std::vector<CompositeEffect> composite = {effect};

  EXPECT_TRUE(adapter->getCapabilities(&value).isOk());
  EXPECT_EQ(value, 17);
  EXPECT_TRUE(adapter->off().isOk());
  EXPECT_TRUE(adapter->on(123, callback).isOk());
  EXPECT_EQ(fake->timeout_ms, 123);
  EXPECT_EQ(fake->callback->asBinder().get(), callback->asBinder().get());
  EXPECT_TRUE(fake->callback->onComplete().isOk());
  EXPECT_EQ(callback->completions, 1);
  EXPECT_TRUE(adapter->perform(Effect::THUD, EffectStrength::STRONG, callback, &value).isOk());
  EXPECT_EQ(fake->effect, Effect::THUD);
  EXPECT_EQ(fake->strength, EffectStrength::STRONG);
  EXPECT_EQ(value, 18);
  EXPECT_TRUE(adapter->getSupportedEffects(&effects).isOk());
  EXPECT_EQ(effects, std::vector<Effect>({Effect::THUD}));
  EXPECT_TRUE(adapter->setAmplitude(0.75f).isOk());
  EXPECT_FLOAT_EQ(fake->amplitude, 0.75f);
  EXPECT_TRUE(adapter->setExternalControl(true).isOk());
  EXPECT_TRUE(fake->external_control);
  EXPECT_TRUE(adapter->getCompositionDelayMax(&value).isOk());
  EXPECT_EQ(value, 19);
  EXPECT_TRUE(adapter->getCompositionSizeMax(&value).isOk());
  EXPECT_EQ(value, 20);
  EXPECT_TRUE(adapter->getSupportedPrimitives(&primitives).isOk());
  EXPECT_EQ(primitives, std::vector<CompositePrimitive>({CompositePrimitive::NOOP}));
  EXPECT_TRUE(adapter->getPrimitiveDuration(CompositePrimitive::CLICK, &value).isOk());
  EXPECT_EQ(fake->primitive, CompositePrimitive::CLICK);
  EXPECT_EQ(value, 21);
  EXPECT_TRUE(adapter->compose(composite, callback).isOk());
  EXPECT_EQ(fake->composite, composite);
  EXPECT_EQ(fake->callback->asBinder().get(), callback->asBinder().get());
  EXPECT_TRUE(adapter->getSupportedAlwaysOnEffects(&effects).isOk());
  EXPECT_EQ(effects, std::vector<Effect>({Effect::THUD}));
  EXPECT_TRUE(adapter->alwaysOnEnable(7, Effect::TICK, EffectStrength::STRONG).isOk());
  EXPECT_EQ(fake->always_on_id, 7);
  EXPECT_EQ(fake->effect, Effect::TICK);
  EXPECT_EQ(fake->strength, EffectStrength::STRONG);
  EXPECT_TRUE(adapter->alwaysOnDisable(7).isOk());
  EXPECT_EQ(fake->calls, 15);
}

TEST(DashVibratorAdapterTest, ReturnsRemoteStatusWithoutRetryOrReplay) {
  auto fake = ndk::SharedRefBase::make<FakeVibrator>();
  fake->next_status = ::ndk::ScopedAStatus::fromServiceSpecificError(42);
  CookieCollector links;
  auto adapter = ndk::SharedRefBase::make<DashVibratorAdapter>(
      [fake](const char*) { return fake->asBinder(); },
      [&links](AIBinder* binder, AIBinder_DeathRecipient* recipient, void* cookie) {
        return links(binder, recipient, cookie);
      });

  ASSERT_TRUE(adapter->connect().isOk());
  EXPECT_EQ(adapter->off().getServiceSpecificError(), 42);
  EXPECT_EQ(fake->calls, 1);
}

TEST(DashVibratorAdapterTest, FrozenMetadataMatchesExpectedContract) {
  EXPECT_EQ(IVibrator::version, 1);
  EXPECT_EQ(IVibrator::hash, "eeab78b6096b029f424ab5ce9c2c4ef1249a5cb0");
}

TEST(DashVibratorAdapterTest, RejectsMissingService) {
  auto missing = ndk::SharedRefBase::make<DashVibratorAdapter>(
      [](const char*) { return ::ndk::SpAIBinder(); });
  EXPECT_EQ(missing->connect().getStatus(), STATUS_NAME_NOT_FOUND);
}

TEST(DashVibratorAdapterTest, FailedLinkStatusIsReturned) {
  auto fake = ndk::SharedRefBase::make<FakeVibrator>();
  CookieCollector links;
  links.status = STATUS_UNKNOWN_ERROR;
  auto adapter = ndk::SharedRefBase::make<DashVibratorAdapter>(
      [fake](const char*) { return fake->asBinder(); },
      [&links](AIBinder* binder, AIBinder_DeathRecipient* recipient, void* cookie) {
        return links(binder, recipient, cookie);
      });
  EXPECT_EQ(adapter->connect().getStatus(), STATUS_UNKNOWN_ERROR);
  EXPECT_TRUE(links.cookies.empty());
}

TEST(DashVibratorAdapterTest, DeathInvokesFatalBoundaryWithoutReconnect) {
  auto fake = ndk::SharedRefBase::make<FakeVibrator>();
  int lookups = 0;
  int deaths = 0;
  CookieCollector links;
  auto adapter = ndk::SharedRefBase::make<DashVibratorAdapter>(
      [fake, &lookups](const char*) {
        ++lookups;
        return fake->asBinder();
      },
      [&links](AIBinder* binder, AIBinder_DeathRecipient* recipient, void* cookie) {
        return links(binder, recipient, cookie);
      },
      [&deaths] { ++deaths; });

  ASSERT_TRUE(adapter->connect().isOk());
  ASSERT_EQ(links.cookies.size(), 1u);
  DashVibratorAdapter::notifyDeathForTest(links.cookies.front());
  EXPECT_EQ(deaths, 1);
  EXPECT_EQ(lookups, 1);
  EXPECT_EQ(fake->calls, 0);
}

TEST(DashVibratorAdapterTest, DefaultDeathActionExitsProcess) {
  ASSERT_EXIT(
      {
        auto fake = ndk::SharedRefBase::make<FakeVibrator>();
        CookieCollector links;
        auto adapter = ndk::SharedRefBase::make<DashVibratorAdapter>(
            [fake](const char*) { return fake->asBinder(); },
            [&links](AIBinder* binder, AIBinder_DeathRecipient* recipient, void* cookie) {
              return links(binder, recipient, cookie);
            });
        if (!adapter->connect().isOk() || links.cookies.size() != 1u) {
          _exit(EXIT_SUCCESS);
        }
        DashVibratorAdapter::notifyDeathForTest(links.cookies.front());
      },
      ::testing::ExitedWithCode(EXIT_FAILURE), "");
}

TEST(DashVibratorAdapterTest, DeathCookieDoesNotReferenceDestroyedAdapter) {
  auto fake = ndk::SharedRefBase::make<FakeVibrator>();
  int deaths = 0;
  CookieCollector links;
  {
    auto adapter = ndk::SharedRefBase::make<DashVibratorAdapter>(
        [fake](const char*) { return fake->asBinder(); },
        [&links](AIBinder* binder, AIBinder_DeathRecipient* recipient, void* cookie) {
          return links(binder, recipient, cookie);
        },
        [&deaths] { ++deaths; });
    ASSERT_TRUE(adapter->connect().isOk());
  }
  ASSERT_EQ(links.cookies.size(), 1u);
  DashVibratorAdapter::notifyDeathForTest(links.cookies.front());
  EXPECT_EQ(deaths, 1);
  links.release();
}
}  // namespace
