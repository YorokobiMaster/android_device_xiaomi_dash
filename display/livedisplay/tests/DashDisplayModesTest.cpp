/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "../DashLiveDisplayCore.h"

#include <gtest/gtest.h>

#include <chrono>
#include <future>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace {

using dash::ApplyResult;
using dash::DashLiveDisplayCore;
using dash::DisplayFeatureBackend;
using dash::HalStatus;

struct SetFeatureCall {
  int32_t displayId;
  int32_t feature;
  int32_t value;
  int32_t cookie;
};

bool operator==(const SetFeatureCall& lhs, const SetFeatureCall& rhs) {
  return lhs.displayId == rhs.displayId && lhs.feature == rhs.feature && lhs.value == rhs.value &&
         lhs.cookie == rhs.cookie;
}

class FakeHal final : public DisplayFeatureBackend {
 public:
  std::vector<SetFeatureCall> calls;
  // When calls.size() reaches this value the call fails once.
  size_t fail_at_call = SIZE_MAX;
  HalStatus fail_status = HalStatus::kDead;

  HalStatus setFeature(int32_t displayId, int32_t feature, int32_t value,
                       int32_t cookie) override {
    calls.push_back({displayId, feature, value, cookie});
    if (calls.size() == fail_at_call) {
      fail_at_call = SIZE_MAX;
      return fail_status;
    }
    return HalStatus::kOk;
  }
};

std::vector<SetFeatureCall> simpleSequence(int32_t feature) {
  return {{0, feature, 2, 255}};
}

std::vector<SetFeatureCall> expertSequence(int32_t gamut) {
  return {
      {0, 26, gamut, 0}, {0, 26, 255, 1}, {0, 26, 255, 2},
      {0, 26, 255, 3},   {0, 26, 0, 4},   {0, 26, 0, 5},
      {0, 26, 0, 6},     {0, 26, 0, 7},   {0, 26, 220, 8},
  };
}

// One Harness owns the fake HAL instances (one per connection) and the
// property backing store shared by every core built from it.
struct Harness {
  std::vector<FakeHal*> hals;
  bool hal_available = true;
  bool property_set_ok = true;
  // Applied to the next FakeHal the factory creates, then cleared.
  size_t arm_fail_at_call = SIZE_MAX;
  HalStatus arm_fail_status = HalStatus::kError;
  std::map<std::string, std::string> properties;

  std::unique_ptr<DashLiveDisplayCore> makeCore() {
    return std::make_unique<DashLiveDisplayCore>(
        [this]() -> std::unique_ptr<DisplayFeatureBackend> {
          if (!hal_available) {
            return nullptr;
          }
          auto fake = std::make_unique<FakeHal>();
          fake->fail_at_call = arm_fail_at_call;
          fake->fail_status = arm_fail_status;
          arm_fail_at_call = SIZE_MAX;
          hals.push_back(fake.get());
          return fake;
        },
        [this](const std::string& name) {
          auto it = properties.find(name);
          return it == properties.end() ? "" : it->second;
        },
        [this](const std::string& name, const std::string& value) {
          if (!property_set_ok) {
            return false;
          }
          properties[name] = value;
          return true;
        });
  }

  FakeHal& hal(size_t index = 0) { return *hals.at(index); }
};

TEST(DashLiveDisplayCoreTest, ListsExactlyTheFourContractProfiles) {
  const auto& profiles = DashLiveDisplayCore::profiles();
  ASSERT_EQ(profiles.size(), 4u);
  EXPECT_EQ(profiles[0].id, 0);
  EXPECT_STREQ(profiles[0].name, "Dynamic");
  EXPECT_EQ(profiles[1].id, 1);
  EXPECT_STREQ(profiles[1].name, "Natural");
  EXPECT_EQ(profiles[2].id, 2);
  EXPECT_STREQ(profiles[2].name, "DCI-P3");
  EXPECT_EQ(profiles[3].id, 3);
  EXPECT_STREQ(profiles[3].name, "sRGB");
}

TEST(DashLiveDisplayCoreTest, FirstConnectAppliesStandardDefault) {
  Harness harness;
  auto core = harness.makeCore();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  ASSERT_EQ(harness.hals.size(), 1u);
  EXPECT_EQ(harness.hal().calls, simpleSequence(2));
  EXPECT_EQ(core->currentModeId(), 1);
  EXPECT_EQ(core->defaultModeId(), 1);
}

TEST(DashLiveDisplayCoreTest, VividAndStandardSendFeatureAndNatureColorLevel) {
  Harness harness;
  auto core = harness.makeCore();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  harness.hal().calls.clear();

  ASSERT_EQ(core->setMode(0, false), ApplyResult::kOk);
  EXPECT_EQ(harness.hal().calls, simpleSequence(0));

  harness.hal().calls.clear();
  ASSERT_EQ(core->setMode(1, false), ApplyResult::kOk);
  EXPECT_EQ(harness.hal().calls, simpleSequence(2));
}

TEST(DashLiveDisplayCoreTest, ExpertProfilesSendNineCookiesInStockOrder) {
  Harness harness;
  auto core = harness.makeCore();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  harness.hal().calls.clear();

  ASSERT_EQ(core->setMode(2, false), ApplyResult::kOk);
  EXPECT_EQ(harness.hal().calls, expertSequence(2));

  harness.hal().calls.clear();
  ASSERT_EQ(core->setMode(3, false), ApplyResult::kOk);
  EXPECT_EQ(harness.hal().calls, expertSequence(3));
}

TEST(DashLiveDisplayCoreTest, UnknownModeIdFailsWithoutSideEffects) {
  Harness harness;
  auto core = harness.makeCore();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  harness.hal().calls.clear();

  EXPECT_EQ(core->setMode(7, true), ApplyResult::kUnknownMode);
  EXPECT_TRUE(harness.hal().calls.empty());
  EXPECT_TRUE(harness.properties.empty());
  EXPECT_EQ(core->currentModeId(), 1);
}

TEST(DashLiveDisplayCoreTest, MakeDefaultControlsPersistenceOnly) {
  Harness harness;
  auto core = harness.makeCore();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);

  ASSERT_EQ(core->setMode(0, false), ApplyResult::kOk);
  EXPECT_TRUE(harness.properties.empty());
  EXPECT_EQ(core->defaultModeId(), 1);

  ASSERT_EQ(core->setMode(2, true), ApplyResult::kOk);
  EXPECT_EQ(harness.properties[dash::kPersistModeProperty], "2");
  EXPECT_EQ(core->defaultModeId(), 2);
}

TEST(DashLiveDisplayCoreTest, ServiceRestartRestoresPersistedDefault) {
  Harness harness;
  auto core = harness.makeCore();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  ASSERT_EQ(core->setMode(3, true), ApplyResult::kOk);

  auto restarted = harness.makeCore();
  ASSERT_EQ(restarted->ensureConnected(), ApplyResult::kOk);
  ASSERT_EQ(harness.hals.size(), 2u);
  EXPECT_EQ(harness.hal(1).calls, expertSequence(3));
  EXPECT_EQ(restarted->currentModeId(), 3);
  EXPECT_EQ(restarted->defaultModeId(), 3);
}

TEST(DashLiveDisplayCoreTest, MalformedPersistedDefaultFallsBackToStandard) {
  Harness harness;
  harness.properties[dash::kPersistModeProperty] = "not-a-mode";
  auto core = harness.makeCore();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  EXPECT_EQ(harness.hal().calls, simpleSequence(2));
}

TEST(DashLiveDisplayCoreTest, OutOfRangePersistedDefaultFallsBackToStandard) {
  Harness harness;
  harness.properties[dash::kPersistModeProperty] = "99";
  auto core = harness.makeCore();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  EXPECT_EQ(harness.hal().calls, simpleSequence(2));
}

TEST(DashLiveDisplayCoreTest, PartialExpertFailureDoesNotCommitState) {
  Harness harness;
  auto core = harness.makeCore();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  harness.hal().calls.clear();

  harness.hal().fail_at_call = 4;  // die on the B cookie write
  harness.hal().fail_status = HalStatus::kError;
  EXPECT_EQ(core->setMode(2, true), ApplyResult::kHalError);
  EXPECT_EQ(harness.hal().calls.size(), 4u);

  EXPECT_EQ(core->currentModeId(), 1);
  EXPECT_TRUE(harness.properties.empty());
}

TEST(DashLiveDisplayCoreTest, FirstConnectApplyErrorStaysDisconnectedAndRetries) {
  Harness harness;
  // Fail the very first setFeature of the connect-time default apply with a
  // non-dead error. The core must report failure and stay disconnected so the
  // monitor loop retries instead of treating the link as established.
  harness.arm_fail_at_call = 1;
  harness.arm_fail_status = HalStatus::kError;
  auto core = harness.makeCore();

  EXPECT_EQ(core->ensureConnected(), ApplyResult::kHalError);
  ASSERT_EQ(harness.hals.size(), 1u);
  // Already disconnected: returns immediately instead of blocking.
  core->awaitDisconnected();

  // The retry must build a fresh connection and re-apply the default.
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  ASSERT_EQ(harness.hals.size(), 2u);
  EXPECT_EQ(harness.hal(1).calls, simpleSequence(2));
  EXPECT_EQ(core->currentModeId(), 1);
}

TEST(DashLiveDisplayCoreTest, PersistFailureFailsSelectionAfterApplying) {
  Harness harness;
  auto core = harness.makeCore();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  harness.hal().calls.clear();
  harness.property_set_ok = false;

  EXPECT_EQ(core->setMode(2, true), ApplyResult::kPersistError);
  // The mode was applied on the panel; only persistence failed, and the
  // caller hears about it instead of a false success.
  EXPECT_EQ(harness.hal().calls, expertSequence(2));
  EXPECT_EQ(core->currentModeId(), 2);
  EXPECT_TRUE(harness.properties.empty());
  EXPECT_EQ(core->defaultModeId(), 1);
}

TEST(DashLiveDisplayCoreTest, DeadDuringApplyReconnectsAndReplaysLastSuccess) {
  Harness harness;
  auto core = harness.makeCore();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  harness.hal().calls.clear();

  harness.hal().fail_at_call = 4;
  harness.hal().fail_status = HalStatus::kDead;
  EXPECT_EQ(core->setMode(2, true), ApplyResult::kHalDead);

  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  ASSERT_EQ(harness.hals.size(), 2u);
  // Nothing was committed, so the replay target is the previous state: the
  // standard default.
  EXPECT_EQ(harness.hal(1).calls, simpleSequence(2));
}

TEST(DashLiveDisplayCoreTest, HalDeathReplaysCurrentProfileFromCookieZero) {
  Harness harness;
  auto core = harness.makeCore();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  ASSERT_EQ(core->setMode(3, true), ApplyResult::kOk);

  core->notifyHalDeath();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  ASSERT_EQ(harness.hals.size(), 2u);
  EXPECT_EQ(harness.hal(1).calls, expertSequence(3));
}

TEST(DashLiveDisplayCoreTest, RepeatedSelectionIsIdempotent) {
  Harness harness;
  auto core = harness.makeCore();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  harness.hal().calls.clear();

  ASSERT_EQ(core->setMode(0, false), ApplyResult::kOk);
  ASSERT_EQ(core->setMode(0, false), ApplyResult::kOk);
  auto expected = simpleSequence(0);
  expected.insert(expected.end(), {0, 0, 2, 255});
  EXPECT_EQ(harness.hal().calls, expected);
  EXPECT_EQ(core->currentModeId(), 0);
}

TEST(DashLiveDisplayCoreTest, EnsureConnectedWhileConnectedIsANoOp) {
  Harness harness;
  auto core = harness.makeCore();
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  ASSERT_EQ(harness.hals.size(), 1u);
  EXPECT_EQ(harness.hal().calls, simpleSequence(2));
}

TEST(DashLiveDisplayCoreTest, UnavailableHalFailsSelectionWithoutStateChange) {
  Harness harness;
  harness.hal_available = false;
  auto core = harness.makeCore();
  EXPECT_EQ(core->ensureConnected(), ApplyResult::kHalUnavailable);
  EXPECT_EQ(core->setMode(0, true), ApplyResult::kHalUnavailable);
  EXPECT_TRUE(harness.properties.empty());
  EXPECT_EQ(core->currentModeId(), 1);
}

TEST(DashLiveDisplayCoreTest, AwaitDisconnectedTracksTheLinkState) {
  Harness harness;
  auto core = harness.makeCore();

  // Not connected yet: returns immediately.
  core->awaitDisconnected();

  ASSERT_EQ(core->ensureConnected(), ApplyResult::kOk);
  auto waiter = std::async(std::launch::async, [&] { core->awaitDisconnected(); });
  EXPECT_EQ(waiter.wait_for(std::chrono::milliseconds(200)), std::future_status::timeout);
  core->notifyHalDeath();
  EXPECT_EQ(waiter.wait_for(std::chrono::seconds(5)), std::future_status::ready);
}

}  // namespace
