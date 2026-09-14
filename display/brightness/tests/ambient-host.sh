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
    "$base/src/me/sandai/server/display/DashAmbientEstimator.java" \
    "$base/tests/src/me/sandai/server/display/DashAmbientEstimatorTest.java"
java -cp "$classes:$junit:$hamcrest" org.junit.runner.JUnitCore \
    me.sandai.server.display.DashAmbientEstimatorTest
