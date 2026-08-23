/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "DashVibratorAdapter.h"

#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android-base/logging.h>

int main() {
  ABinderProcess_setThreadPoolMaxThreadCount(0);
  ABinderProcess_startThreadPool();

  auto adapter = ndk::SharedRefBase::make<DashVibratorAdapter>();
  auto status = adapter->connect();
  if (!status.isOk()) {
    LOG(ERROR) << "Failed to connect to retained vibrator HAL: " << status.getDescription();
    return 1;
  }

  const auto binder = adapter->asBinder();
  binder_status_t result = AServiceManager_addService(
      binder.get(), "android.hardware.vibrator.IVibrator/default");
  if (result != STATUS_OK) {
    LOG(ERROR) << "Failed to publish vibrator adapter: " << result;
    return 1;
  }
  ABinderProcess_joinThreadPool();
  return 0;
}
