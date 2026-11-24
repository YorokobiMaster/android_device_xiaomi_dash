/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>
#include <android/binder_interface_utils.h>
#include <health-impl/Health.h>
#include <health/utils.h>

#include <algorithm>
#include <memory>
#include <string>

namespace {

using aidl::android::hardware::health::BatteryCapacityLevel;
using aidl::android::hardware::health::BatteryStatus;
using aidl::android::hardware::health::HalHealthLoop;
using aidl::android::hardware::health::Health;
using aidl::android::hardware::health::HealthInfo;
using aidl::android::hardware::health::IHealth;

constexpr char kAverageCurrentPath[] = "/sys/class/power_supply/bms/av_current";

bool readAverageCurrent(int32_t* currentUa) {
    std::string content;
    if (!android::base::ReadFileToString(kAverageCurrentPath, &content)) {
        PLOG(ERROR) << "Read BMS average current";
        return false;
    }
    int32_t currentMa;
    if (!android::base::ParseInt(android::base::Trim(content), &currentMa)) {
        LOG(ERROR) << "Invalid BMS average current";
        return false;
    }
    // The external gauge reports mA, negative while charging. IHealth uses
    // uA, positive for current entering the battery.
    *currentUa = -currentMa * 1000;
    return true;
}

class DashHealth : public Health {
  public:
    using Health::Health;

    ndk::ScopedAStatus getCurrentAverageMicroamps(int32_t* currentUa) override {
        *currentUa = 0;
        if (!readAverageCurrent(currentUa)) {
            return ndk::ScopedAStatus::fromServiceSpecificError(IHealth::STATUS_UNKNOWN);
        }
        return ndk::ScopedAStatus::ok();
    }

  protected:
    void UpdateHealthInfo(HealthInfo* info) override {
        info->batteryCurrentAverageMicroamps = 0;
        const bool hasAverageCurrent = readAverageCurrent(&info->batteryCurrentAverageMicroamps);

        // Never propagate the stock MT6379 estimate: it uses an unrelated
        // internal gauge current and can report months to charge this battery.
        info->batteryChargeTimeToFullNowSeconds =
                HealthInfo::BATTERY_CHARGE_TIME_TO_FULL_NOW_SECONDS_UNSUPPORTED;
        if (info->batteryCapacityLevel == BatteryCapacityLevel::FULL ||
            info->batteryCapacityLevel == BatteryCapacityLevel::UNKNOWN) {
            info->batteryChargeTimeToFullNowSeconds = 0;
            return;
        }
        if (!hasAverageCurrent || info->batteryStatus != BatteryStatus::CHARGING ||
            info->batteryCurrentAverageMicroamps <= 0 || info->batteryFullChargeUah <= 0) {
            return;
        }

        // Percent * uAh / uA * 3600 / 100 gives seconds to 100%, using the
        // learned full capacity and the external gauge's moving average.
        const int64_t remainingPct = 100 - info->batteryLevel;
        info->batteryChargeTimeToFullNowSeconds = std::max<int64_t>(
                1, remainingPct * info->batteryFullChargeUah * 36 /
                           info->batteryCurrentAverageMicroamps);
    }
};

}  // namespace

int main() {
    auto config = std::make_unique<healthd_config>();
    android::hardware::health::InitHealthdConfig(config.get());
    auto service = ndk::SharedRefBase::make<DashHealth>("default", std::move(config));
    auto loop = std::make_shared<HalHealthLoop>(service, service);
    return loop->StartLoop();
}
