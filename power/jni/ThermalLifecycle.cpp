/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <sys/system_properties.h>

static jlong readSample(const prop_info* property) {
    if (property == nullptr) return 0;
    jlong sample = 0;
    __system_property_read_callback(property,
            [](void* cookie, const char*, const char* value, uint32_t serial) {
                *static_cast<jlong*>(cookie) = (static_cast<jlong>(serial) << 2) | 1
                        | (strcmp(value, "running") == 0 ? 2 : 0);
            }, &sample);
    return sample;
}

static void throwWaitFailure(JNIEnv* env, const char* message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_sandai_dashpower_ThermalLifecycle_nativeSample(JNIEnv*, jclass) {
    return readSample(__system_property_find("init.svc.mi_thermald"));
}

extern "C" JNIEXPORT void JNICALL
Java_me_sandai_dashpower_ThermalLifecycle_nativeAwaitChange(
        JNIEnv* env, jclass, jlong expectedSample) {
    // Snapshot before lookup so creation between an absent lookup and wait cannot be missed.
    const uint32_t globalSerial = __system_property_area_serial();
    const prop_info* property = __system_property_find("init.svc.mi_thermald");
    if (readSample(property) != expectedSample) return;
    if (property == nullptr && globalSerial == UINT32_MAX) {
        // Bionic's global Wait error path returns -1 as bool (true); do not enter a spin loop.
        throwWaitFailure(env, "Cannot access system property serial area");
        return;
    }
    const uint32_t serial = property == nullptr ? globalSerial
            : static_cast<uint32_t>(expectedSample >> 2);
    uint32_t newSerial = 0;
    if (!__system_property_wait(property, serial, &newSerial, nullptr)) {
        throwWaitFailure(env, "Cannot wait for init.svc.mi_thermald lifecycle");
    }
}
