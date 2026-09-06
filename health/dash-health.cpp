/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android-base/logging.h>

#include <aidl/vendor/lineage/health/BnChargingControl.h>
#include <aidl/vendor/lineage/health/ChargingControlSupportedMode.h>

#include <fcntl.h>
#include <unistd.h>

namespace {

using aidl::vendor::lineage::health::BnChargingControl;
using aidl::vendor::lineage::health::ChargingLimitInfo;

constexpr char kInputSuspendPath[] = "/sys/class/power_supply/battery/input_suspend";

// Verified on-device: 1 suspends charger input (status flips to
// Discharging), 0 resumes. charge_control_limit is not used: in the kernel
// it only stores a thermal_level value with no charger-side effect. The
// full-charge hard crash observed while poking charge nodes was NOT
// reproduced as caused by charge_control_limit; root cause undetermined.
bool writeSuspend(bool suspend) {
  int fd = open(kInputSuspendPath, O_WRONLY);
  if (fd < 0) {
    PLOG(ERROR) << "open " << kInputSuspendPath;
    return false;
  }
  const char* value = suspend ? "1" : "0";
  bool ok = write(fd, value, 1) == 1;
  if (!ok) {
    PLOG(ERROR) << "write " << kInputSuspendPath;
  }
  close(fd);
  return ok;
}

class ChargingControl : public BnChargingControl {
 public:
  ndk::ScopedAStatus getChargingEnabled(bool* enabled) override {
    int fd = open(kInputSuspendPath, O_RDONLY);
    if (fd < 0) {
      PLOG(ERROR) << "open " << kInputSuspendPath;
      return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }
    char value = 0;
    bool ok = read(fd, &value, 1) == 1 && (value == '0' || value == '1');
    close(fd);
    if (!ok) {
      return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }
    *enabled = value == '0';
    return ndk::ScopedAStatus::ok();
  }

  ndk::ScopedAStatus setChargingEnabled(bool enabled) override {
    if (!writeSuspend(!enabled)) {
      return ndk::ScopedAStatus::fromServiceSpecificError(-1);
    }
    return ndk::ScopedAStatus::ok();
  }

  ndk::ScopedAStatus getSupportedMode(int32_t* mode) override {
    *mode = static_cast<int32_t>(
        aidl::vendor::lineage::health::ChargingControlSupportedMode::TOGGLE);
    return ndk::ScopedAStatus::ok();
  }

  ndk::ScopedAStatus setChargingDeadline(int64_t) override {
    return ndk::ScopedAStatus::fromServiceSpecificError(-1);
  }

  ndk::ScopedAStatus getChargingDeadline(int64_t*) override {
    return ndk::ScopedAStatus::fromServiceSpecificError(-1);
  }

  ndk::ScopedAStatus getChargingLimit(ChargingLimitInfo*) override {
    return ndk::ScopedAStatus::fromServiceSpecificError(-1);
  }

  ndk::ScopedAStatus setChargingLimit(const ChargingLimitInfo&) override {
    return ndk::ScopedAStatus::fromServiceSpecificError(-1);
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
