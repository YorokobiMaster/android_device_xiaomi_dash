/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <aidl/android/media/audio/BnHalAdapterVendorExtension.h>

namespace aidl::android::media::audio {

class DashAudioParameterParser : public BnHalAdapterVendorExtension {
  private:
    ndk::ScopedAStatus parseVendorParameterIds(
            IHalAdapterVendorExtension::ParameterScope scope, const std::string& rawKeys,
            std::vector<std::string>* ids) override;

    ndk::ScopedAStatus parseVendorParameters(
            IHalAdapterVendorExtension::ParameterScope scope,
            const std::string& rawKeysAndValues,
            std::vector<hardware::audio::core::VendorParameter>* syncParameters,
            std::vector<hardware::audio::core::VendorParameter>* asyncParameters) override;

    ndk::ScopedAStatus parseBluetoothA2dpReconfigureOffload(
            const std::string& rawValue,
            std::vector<hardware::audio::core::VendorParameter>* parameters) override;

    ndk::ScopedAStatus parseBluetoothLeReconfigureOffload(
            const std::string& rawValue,
            std::vector<hardware::audio::core::VendorParameter>* parameters) override;

    ndk::ScopedAStatus processVendorParameters(
            IHalAdapterVendorExtension::ParameterScope scope,
            const std::vector<hardware::audio::core::VendorParameter>& parameters,
            std::string* rawKeysAndValues) override;
};

}  // namespace aidl::android::media::audio
