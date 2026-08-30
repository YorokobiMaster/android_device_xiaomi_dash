/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include <AudioPolicyConfig.h>
#include <AudioPolicyManager.h>
#include <EngineLibrary.h>

namespace android {

class DashAudioPolicyManager : public AudioPolicyManager {
  public:
    using AudioPolicyManager::AudioPolicyManager;

  protected:
    status_t checkAndSetVolume(IVolumeCurves& curves, VolumeSource volumeSource, int index,
                              const sp<AudioOutputDescriptor>& outputDesc,
                              DeviceTypeSet deviceTypes, bool adjustAttenuation,
                              int delayMs = 0, bool force = false) override {
        const status_t status = AudioPolicyManager::checkAndSetVolume(
                curves, volumeSource, index, outputDesc, deviceTypes, adjustAttenuation,
                delayMs, force);
        if (status != NO_ERROR || outputDesc != mPrimaryOutput || !isInCall()) {
            return status;
        }

        bool isVoiceVolumeSource;
        bool isBtScoVolumeSource;
        updateVoiceBtScoVolumeSrcForCalls(
                volumeSource, isVoiceVolumeSource, isBtScoVolumeSource);
        if (!isVoiceVolumeSource || isBtScoVolumeSource) {
            return status;
        }

        audio_devices_t volumeDevice;
        if (deviceTypes.count(AUDIO_DEVICE_OUT_SPEAKER) != 0) {
            volumeDevice = AUDIO_DEVICE_OUT_SPEAKER;
        } else if (deviceTypes.count(AUDIO_DEVICE_OUT_EARPIECE) != 0) {
            volumeDevice = AUDIO_DEVICE_OUT_EARPIECE;
        } else {
            return status;
        }

        const String8 parameters = String8::format(
                "volumeDevice=%u;volumeIndex=%d;volumeStreamType=%d",
                volumeDevice, index, AUDIO_STREAM_VOICE_CALL);
        mpClientInterface->setParameters(mPrimaryOutput->mIoHandle, parameters, delayMs);
        return status;
    }
};

extern "C" AudioPolicyInterface* createAudioPolicyManager(
        AudioPolicyClientInterface* clientInterface) {
    DashAudioPolicyManager* manager;
    media::AudioPolicyConfig aidlConfig;
    if (clientInterface->getAudioPolicyConfig(&aidlConfig) == OK) {
        auto config = AudioPolicyConfig::loadFromApmAidlConfigWithFallback(aidlConfig);
        manager = new DashAudioPolicyManager(
                config,
                loadApmEngineLibraryAndCreateEngine(
                        config->getEngineLibraryNameSuffix(), aidlConfig.engineConfig),
                clientInterface);
    } else {
        auto config = AudioPolicyConfig::loadFromApmXmlConfigWithFallback();
        manager = new DashAudioPolicyManager(
                config,
                loadApmEngineLibraryAndCreateEngine(config->getEngineLibraryNameSuffix()),
                clientInterface);
    }

    if (manager->initialize() != NO_ERROR) {
        delete manager;
        return nullptr;
    }
    return manager;
}

extern "C" void destroyAudioPolicyManager(AudioPolicyInterface* manager) {
    delete manager;
}

}  // namespace android
