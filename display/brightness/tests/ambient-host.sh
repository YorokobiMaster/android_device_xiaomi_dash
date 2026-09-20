#!/bin/bash
# Copyright (C) 2026 GitHub @YorokobiMaster
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
root=$(cd "$(dirname "$0")/../../../../../.." && pwd)
cd "$root"
base=device/xiaomi/dash/display/brightness
classes=${OUT_DIR:-out}/host/linux-x86/obj/JAVA_LIBRARIES/dash-brightness-ambient-host_intermediates/classes
junit=prebuilts/devtools/tools/lib/junit-4.12.jar
hamcrest=prebuilts/devtools/tools/lib/hamcrest-core-1.3.jar
mkdir -p "$classes"
# Compile the actual platform spline, not an Android stub or a test replacement.
javac -cp "$junit" -d "$classes" \
    frameworks/base/core/java/android/util/Spline.java \
    frameworks/base/services/core/java/com/android/server/display/DeviceBrightnessCurvePolicy.java \
    frameworks/base/services/core/java/com/android/server/display/DeviceShortTermModelPolicy.java \
    "$base/src/me/sandai/server/display/DashAmbientEstimator.java" \
    "$base/src/me/sandai/server/display/DashBrightnessCurvePolicy.java" \
    "$base/src/me/sandai/server/display/DashShortTermModelPolicy.java" \
    "$base/src/me/sandai/server/display/ThermalBrightnessTable.java" \
    "$base/src/me/sandai/server/display/ContentBrightness.java" \
    "$base/src/me/sandai/server/display/HdrBrightness.java" \
    "$base/src/me/sandai/server/display/ThermalCaps.java" \
    "$base/tests/src/me/sandai/server/display/DashAmbientEstimatorTest.java" \
    "$base/tests/src/me/sandai/server/display/DashBrightnessCurvePolicyTest.java" \
    "$base/tests/src/me/sandai/server/display/HdrBrightnessTest.java" \
    "$base/tests/src/me/sandai/server/display/DashShortTermModelPolicyTest.java" \
    "$base/tests/src/me/sandai/server/display/ThermalCapsTest.java" \
    "$base/tests/me/sandai/server/display/BrightnessCapsTest.java"
java -cp "$classes:$junit:$hamcrest" org.junit.runner.JUnitCore \
    me.sandai.server.display.DashAmbientEstimatorTest \
    me.sandai.server.display.DashBrightnessCurvePolicyTest \
    me.sandai.server.display.HdrBrightnessTest \
    me.sandai.server.display.DashShortTermModelPolicyTest \
    me.sandai.server.display.ThermalCapsTest
java -cp "$classes" me.sandai.server.display.BrightnessCapsTest \
    "$base/config/multi_factor_thermal_brightness_control.xml"
