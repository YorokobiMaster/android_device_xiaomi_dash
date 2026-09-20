/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>

#include <aidl/vendor/lineage/health/BnChargingControl.h>
#include <aidl/vendor/lineage/health/ChargingControlSupportedMode.h>

namespace {

using aidl::vendor::lineage::health::BnChargingControl;
using aidl::vendor::lineage::health::ChargingLimitInfo;

constexpr char kChargeStartThresholdPath[] =
    "/sys/class/power_supply/battery/charge_control_start_threshold";
constexpr char kChargeEndThresholdPath[] =
    "/sys/class/power_supply/battery/charge_control_end_threshold";

bool readInt(const char* path, int32_t* value) {
  std::string content;
  if (!android::base::ReadFileToString(path, &content, true)) {
    PLOG(ERROR) << "read " << path;
    return false;
  }
  if (!android::base::ParseInt(android::base::Trim(content), value)) {
    LOG(ERROR) << "Invalid integer in " << path;
    return false;
  }
  return true;
}

bool writeInt(const char* path, int32_t value) {
  if (!android::base::WriteStringToFile(std::to_string(value), path, true)) {
    PLOG(ERROR) << "write " << path;
    return false;
  }
  return true;
}

class ChargingControl : public BnChargingControl {
 public:
  ndk::ScopedAStatus getChargingEnabled(bool*) override {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
  }

  ndk::ScopedAStatus setChargingEnabled(bool) override {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
  }

  ndk::ScopedAStatus getSupportedMode(int32_t* mode) override {
    *mode = static_cast<int32_t>(
        aidl::vendor::lineage::health::ChargingControlSupportedMode::LIMIT);
    return ndk::ScopedAStatus::ok();
  }

  ndk::ScopedAStatus setChargingDeadline(int64_t) override {
    return ndk::ScopedAStatus::fromServiceSpecificError(-1);
  }

  ndk::ScopedAStatus getChargingDeadline(int64_t*) override {
    return ndk::ScopedAStatus::fromServiceSpecificError(-1);
  }

  ndk::ScopedAStatus getChargingLimit(ChargingLimitInfo* limit) override {
    if (!readInt(kChargeStartThresholdPath, &limit->min) ||
        !readInt(kChargeEndThresholdPath, &limit->max)) {
      return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }
    return ndk::ScopedAStatus::ok();
  }

  ndk::ScopedAStatus setChargingLimit(const ChargingLimitInfo& limit) override {
    if (limit.min < 0 || limit.max > 100 || limit.min >= limit.max) {
      return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }
    if (!writeInt(kChargeEndThresholdPath, limit.max) ||
        !writeInt(kChargeStartThresholdPath, limit.min)) {
      return ndk::ScopedAStatus::fromServiceSpecificError(-1);
    }
    return ndk::ScopedAStatus::ok();
  }
};

}  // namespace

int main() {
  ABinderProcess_setThreadPoolMaxThreadCount(0);
  auto service = ndk::SharedRefBase::make<ChargingControl>();

  const std::string instance =
      std::string(ChargingControl::descriptor) + "/default";
  binder_status_t status =
      AServiceManager_addService(service->asBinder().get(), instance.c_str());
  if (status != STATUS_OK) {
    LOG(ERROR) << "Failed to publish " << instance << ": " << status;
    return 1;
  }

  ABinderProcess_startThreadPool();
  ABinderProcess_joinThreadPool();
  return 0;
}
