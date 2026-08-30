/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "DashAudioParameterParser"

#include "DashAudioParameterParser.h"

#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

using aidl::android::media::audio::DashAudioParameterParser;
using aidl::android::media::audio::IHalAdapterVendorExtension;

int main() {
    const auto parser = ndk::SharedRefBase::make<DashAudioParameterParser>();
    const std::string instance =
            std::string(IHalAdapterVendorExtension::descriptor) + "/default";
    const binder_status_t status =
            AServiceManager_addService(parser->asBinder().get(), instance.c_str());
    if (status != STATUS_OK) {
        LOG(ERROR) << "failed to register " << instance << ": " << status;
        return EXIT_FAILURE;
    }

    LOG(INFO) << "registered " << instance;
    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;
}
