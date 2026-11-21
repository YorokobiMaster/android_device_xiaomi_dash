#!/bin/bash
# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0
set -eu

# Use a checkout containing the hook changes on the stock framework baseline.
baseline=$(git -C frameworks/base rev-parse --verify --end-of-options \
    "${1:-22e17976919409f808980c16610fa967f3b35cd5}^{commit}")
power_interface=services/core/java/com/android/server/power/DevicePowerManagerInternal.java
hooks=$(
    git -C frameworks/base diff --no-color --no-ext-diff "$baseline" -- \
        "$power_interface" \
        services/core/java/com/android/server/power/PowerManagerService.java \
        services/core/java/com/android/server/wm/ActivityRecord.java \
        services/core/java/com/android/server/wm/ActivityTaskManagerInternal.java \
        services/core/java/com/android/server/wm/ActivityTaskManagerService.java \
        services/core/java/com/android/server/wm/DisplayPolicy.java \
        services/proguard.flags \
        services/tests/powerservicetests/src/com/android/server/power/PowerManagerServiceTest.java \
        || exit $?
    if [ -f "frameworks/base/$power_interface" ] &&
            ! git -C frameworks/base ls-files --error-unmatch "$power_interface" >/dev/null 2>&1; then
        git -C frameworks/base diff --no-color --no-index -- /dev/null "$power_interface" \
            || test "$?" = 1
    fi
)
{
    printf '# LineageOS 24.0 hooks for device power and resumed activity events.\n'
    printf '# Stock baseline: %s\n\n' "$baseline"
    printf '%s\n' "$hooks" | sed 's/^ $//'
} > device/xiaomi/dash/power/patches/frameworks-base.patch
