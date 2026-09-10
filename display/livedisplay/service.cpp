/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android-base/logging.h>
#include <sys/system_properties.h>

#include <chrono>
#include <memory>
#include <thread>

#include "DashDisplayModes.h"
#include "XiaomiDisplayFeatureClient.h"

namespace {

using aidl::vendor::lineage::livedisplay::IDisplayModes;

// The stock displayfeature HAL is a class-hal service on the same boot, but
// init must never block on it: the LiveDisplay interface is published first,
// and this loop keeps retrying. Fast retries cover the boot window; slow
// retries cover a vendor HAL restart much later.
[[noreturn]] void monitorLoop(const std::shared_ptr<DashDisplayModes>& modes) {
  dash::DashLiveDisplayCore& core = modes->core();
  int failures = 0;
  for (;;) {
    core.awaitDisconnected();
    while (core.ensureConnected() != dash::ApplyResult::kOk) {
      ++failures;
      std::this_thread::sleep_for(failures <= 10 ? std::chrono::seconds(1)
                                                 : std::chrono::seconds(10));
    }
    failures = 0;
  }
}

// The eye-care controller lives in DashEyeCare, which writes
// kEyeCareProperty directly. This loop applies every change to the
// vendor HAL; the connect/reconnect replay covers boot and HAL restarts.
[[noreturn]] void eyeCareWatchLoop(const std::shared_ptr<DashDisplayModes>& modes) {
  dash::DashLiveDisplayCore& core = modes->core();
  const prop_info* pi = nullptr;
  uint32_t serial = 0;
  for (;;) {
    if (pi == nullptr) {
      pi = __system_property_find(dash::kEyeCareProperty);
      if (pi == nullptr) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        continue;
      }
      serial = __system_property_serial(pi);
      core.applyEyeCareFromProperty();
    }
    // Wake at approximately 60 Hz only while fading. A property change wakes
    // immediately and retargets from the last successfully applied level.
    const timespec frame{0, 16'000'000};
    const timespec* timeout = core.eyeCareTransitionPending() ? &frame : nullptr;
    uint32_t next_serial = serial;
    if (__system_property_wait(pi, serial, &next_serial, timeout)) {
      serial = next_serial;
    }
    core.applyEyeCareFromProperty();
  }
}

}  // namespace

int main() {
  ABinderProcess_setThreadPoolMaxThreadCount(4);
  ABinderProcess_startThreadPool();

  auto modes = ndk::SharedRefBase::make<DashDisplayModes>();
  std::weak_ptr<DashDisplayModes> weak_modes = modes;
  modes->core().setHalFactory([weak_modes]() -> std::unique_ptr<dash::DisplayFeatureBackend> {
    auto client = std::make_unique<XiaomiDisplayFeatureClient>([weak_modes] {
      if (auto locked = weak_modes.lock()) {
        locked->core().notifyHalDeath();
      }
    });
    if (auto status = client->connect(); !status.isOk()) {
      LOG(WARNING) << "Xiaomi displayfeature HAL not ready: " << status.getDescription();
      return nullptr;
    }
    return client;
  });

  const std::string instance = std::string(IDisplayModes::descriptor) + "/default";
  binder_status_t result =
      AServiceManager_addService(modes->asBinder().get(), instance.c_str());
  if (result != STATUS_OK) {
    LOG(ERROR) << "Failed to publish " << instance << ": " << result;
    return 1;
  }

  std::thread(monitorLoop, modes).detach();
  std::thread(eyeCareWatchLoop, modes).detach();
  ABinderProcess_joinThreadPool();
  return 0;
}
