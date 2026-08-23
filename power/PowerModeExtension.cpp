/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include <aidl/android/hardware/power/BnPower.h>
#include <android-base/logging.h>
#include <android-base/unique_fd.h>

#include <cstddef>
#include <cstdint>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <unistd.h>

namespace aidl::google::hardware::power::impl::pixel {

using ::aidl::android::hardware::power::Mode;

namespace {

constexpr char kTouchDevice[] = "/dev/xiaomi-touch";
constexpr int kTouchId = 0;
constexpr uint8_t kSetCurrentValue = 0;
constexpr uint16_t kDoubleTapMode = 14;

struct CommonData {
  int8_t touchId;
  uint8_t command;
  uint16_t mode;
  uint16_t dataLength;
  int32_t data[256];
};

static_assert(offsetof(CommonData, data) == 8);
static_assert(sizeof(CommonData) == 1032);

constexpr unsigned long kSelectTouchId = _IO('T', 3);
constexpr unsigned long kCommonData = _IOWR('T', 0, CommonData);

bool setDoubleTapWake(bool enabled) {
  ::android::base::unique_fd fd(
      TEMP_FAILURE_RETRY(open(kTouchDevice, O_RDWR | O_CLOEXEC)));
  if (fd.get() < 0) {
    PLOG(ERROR) << "Failed to open " << kTouchDevice;
    return false;
  }

  if (TEMP_FAILURE_RETRY(ioctl(fd.get(), kSelectTouchId, kTouchId)) < 0) {
    PLOG(ERROR) << "Failed to select touch panel " << kTouchId;
    return false;
  }

  CommonData request = {
      .touchId = kTouchId,
      .command = kSetCurrentValue,
      .mode = kDoubleTapMode,
      .dataLength = 1,
      .data = {enabled ? 1 : 0},
  };
  if (TEMP_FAILURE_RETRY(ioctl(fd.get(), kCommonData, &request)) < 0) {
    PLOG(ERROR) << "Failed to set Xiaomi touch mode " << kDoubleTapMode;
    return false;
  }
  if (request.data[0] != 0) {
    LOG(ERROR) << "Xiaomi touch mode " << kDoubleTapMode
               << " failed with driver result " << request.data[0];
    return false;
  }

  LOG(INFO) << "Xiaomi touch mode " << kDoubleTapMode << " set to " << enabled;
  return true;
}

} // namespace

bool isDeviceSpecificModeSupported(Mode type, bool *_aidl_return) {
  if (type != Mode::DOUBLE_TAP_TO_WAKE) {
    return false;
  }

  *_aidl_return = true;
  return true;
}

bool setDeviceSpecificMode(Mode type, bool enabled) {
  if (type != Mode::DOUBLE_TAP_TO_WAKE) {
    return false;
  }

  setDoubleTapWake(enabled);
  return true;
}

} // namespace aidl::google::hardware::power::impl::pixel
