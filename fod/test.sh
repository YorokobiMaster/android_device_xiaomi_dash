#!/usr/bin/env bash
# Copyright (C) 2026 GitHub @YorokobiMaster
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

root=$(realpath "$(dirname "$0")/../../../..")
fod="$root/device/xiaomi/dash/fod/DashFod"
intermediates="$root/out/soong/.intermediates"
junit="$root/out/host/linux-x86/testcases/DashFodHostTest/DashFodHostTest.jar"
framework="$intermediates/frameworks/base/framework-minus-apex/android_common/combined/framework.jar"
services="$intermediates/frameworks/base/services/core/services.core/android_common/turbine-combined/services.core.jar"
extension="$intermediates/device/xiaomi/dash/fod/interfaces/vendor.xiaomi.hardware.fingerprintextension-V1-java/android_common/javac/vendor.xiaomi.hardware.fingerprintextension-V1-java.jar"
for jar in "$junit" "$framework" "$services" "$extension"; do
    if [[ ! -f "$jar" ]]; then
        printf 'Missing cached build dependency: %s\n' "$jar" >&2
        exit 1
    fi
done
work=$(mktemp -d "$root/tmp/disposable-anytime/dash-fod-test.XXXXXX")
trap 'rm -rf "$work"' EXIT

javac -proc:none -source 17 -target 17 -Xlint:-options \
    -cp "$framework:$services:$extension" -d "$work/platform" \
    "$fod"/src/me/sandai/dashfod/*.java
javac -cp "$junit" -d "$work/host" \
    "$fod/src/me/sandai/dashfod/FodController.java" \
    "$fod"/tests/src/me/sandai/dashfod/*.java
java -cp "$work/host:$junit" org.junit.runner.JUnitCore \
    me.sandai.dashfod.FodControllerTest
