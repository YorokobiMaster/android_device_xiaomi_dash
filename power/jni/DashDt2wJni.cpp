/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "DashDt2wJni"

#include <jni.h>
#include <log/log.h>

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

namespace {

constexpr char kTouchDevice[] = "/dev/xiaomi-touch";
constexpr int kTouchId = 0;
constexpr uint8_t kSetCurrentValue = 0;
constexpr uint16_t kDoubleTapMode = 14;
constexpr uint16_t kTouchSuspendMode = 27;

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
    int fd;
    do {
        fd = open(kTouchDevice, O_RDWR | O_CLOEXEC);
    } while (fd < 0 && errno == EINTR);
    if (fd < 0) {
        ALOGE("Failed to open %s: %s", kTouchDevice, strerror(errno));
        return false;
    }

    int result;
    do {
        result = ioctl(fd, kSelectTouchId, kTouchId);
    } while (result < 0 && errno == EINTR);
    if (result < 0) {
        ALOGE("Failed to select touch panel %d: %s", kTouchId, strerror(errno));
        close(fd);
        return false;
    }

    CommonData request = {
            .touchId = kTouchId,
            .command = kSetCurrentValue,
            .mode = kDoubleTapMode,
            .dataLength = 1,
            .data = {enabled ? 1 : 0},
    };
    do {
        result = ioctl(fd, kCommonData, &request);
    } while (result < 0 && errno == EINTR);
    int savedErrno = errno;
    close(fd);

    if (result < 0) {
        ALOGE("Failed to set Xiaomi touch mode %u: %s", kDoubleTapMode,
              strerror(savedErrno));
        return false;
    }
    if (request.data[0] != 0) {
        ALOGE("Xiaomi touch mode %u failed with driver result %d", kDoubleTapMode,
              request.data[0]);
        return false;
    }

    ALOGI("Xiaomi touch mode %u set to %d", kDoubleTapMode, enabled);
    return true;
}

int waitForTouchStateChange() {
    static int fd = -1;

    if (fd < 0) {
        do {
            fd = open(kTouchDevice, O_RDONLY | O_CLOEXEC);
        } while (fd < 0 && errno == EINTR);
        if (fd < 0) {
            ALOGE("Failed to open %s for touch state: %s", kTouchDevice,
                  strerror(errno));
            return -1;
        }

        int result;
        do {
            result = ioctl(fd, kSelectTouchId, kTouchId);
        } while (result < 0 && errno == EINTR);
        if (result < 0) {
            ALOGE("Failed to select touch panel for state events: %s",
                  strerror(errno));
            close(fd);
            fd = -1;
            return -1;
        }
    }

    for (;;) {
        pollfd descriptor = {
                .fd = fd,
                .events = POLLRDNORM,
                .revents = 0,
        };
        int result;
        do {
            result = poll(&descriptor, 1, -1);
        } while (result < 0 && errno == EINTR);
        if (result < 0 || (descriptor.revents & (POLLERR | POLLHUP | POLLNVAL))) {
            ALOGE("Failed waiting for touch state: %s",
                  result < 0 ? strerror(errno) : "device poll error");
            close(fd);
            fd = -1;
            return -1;
        }

        CommonData event = {};
        ssize_t length;
        do {
            length = read(fd, &event, sizeof(event));
        } while (length < 0 && errno == EINTR);
        if (length < 0) {
            ALOGE("Failed to read touch event: %s", strerror(errno));
            close(fd);
            fd = -1;
            return -1;
        }
        if (length == 0 || event.mode != kTouchSuspendMode ||
                event.command != kSetCurrentValue || event.dataLength < 1) {
            continue;
        }

        ALOGI("Touch state changed to %d", event.data[0]);
        return event.data[0];
    }
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_me_sandai_dashdt2w_DashDt2wApplication_nativeSetDoubleTapWake(
        JNIEnv*, jclass, jboolean enabled) {
    return setDoubleTapWake(enabled == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_sandai_dashdt2w_DashDt2wApplication_nativeWaitForTouchStateChange(
        JNIEnv*, jclass) {
    return waitForTouchStateChange();
}
