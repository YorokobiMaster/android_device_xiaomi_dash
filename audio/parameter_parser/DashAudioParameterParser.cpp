/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "DashAudioParameterParser.h"

namespace aidl::android::media::audio {

using hardware::audio::core::VendorParameter;

namespace {

void appendParameter(const std::string& rawValue, std::vector<VendorParameter>* parameters) {
    if (!rawValue.empty()) {
        parameters->emplace_back(VendorParameter{.id = rawValue});
    }
}

}  // namespace

ndk::ScopedAStatus DashAudioParameterParser::parseVendorParameterIds(
        IHalAdapterVendorExtension::ParameterScope /* scope */, const std::string& rawKeys,
        std::vector<std::string>* ids) {
    if (!rawKeys.empty()) {
        ids->emplace_back(rawKeys);
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus DashAudioParameterParser::parseVendorParameters(
        IHalAdapterVendorExtension::ParameterScope /* scope */,
        const std::string& rawKeysAndValues,
        std::vector<VendorParameter>* syncParameters,
        std::vector<VendorParameter>* /* asyncParameters */) {
    appendParameter(rawKeysAndValues, syncParameters);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus DashAudioParameterParser::parseBluetoothA2dpReconfigureOffload(
        const std::string& rawValue, std::vector<VendorParameter>* parameters) {
    appendParameter(rawValue, parameters);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus DashAudioParameterParser::parseBluetoothLeReconfigureOffload(
        const std::string& rawValue, std::vector<VendorParameter>* parameters) {
    appendParameter(rawValue, parameters);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus DashAudioParameterParser::processVendorParameters(
        IHalAdapterVendorExtension::ParameterScope /* scope */,
        const std::vector<VendorParameter>& parameters, std::string* rawKeysAndValues) {
    for (const auto& parameter : parameters) {
        if (parameter.id.empty()) {
            continue;
        }
        if (!rawKeysAndValues->empty()) {
            rawKeysAndValues->append(";");
        }
        rawKeysAndValues->append(parameter.id);
    }
    return ndk::ScopedAStatus::ok();
}

}  // namespace aidl::android::media::audio
