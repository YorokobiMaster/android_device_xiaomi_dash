#!/bin/bash
# Copyright (C) 2026 GitHub @YorokobiMaster
# SPDX-License-Identifier: Apache-2.0
set -eu
test_output=tmp/disposable-anytime/dash-power-host-tests
mkdir -p "$test_output"
sources=device/xiaomi/dash/power/src/me/sandai/dashpower
tests=device/xiaomi/dash/power/tests/me/sandai/dashpower
prebuilts/jdk/jdk21/linux-x86/bin/javac -d "$test_output" \
    "$sources/PowerPolicy.java" \
    "$sources/ThermalConfigStore.java" \
    "$sources/ThermalLifecycle.java" \
    "$sources/ThermalScenarioPolicy.java" \
    "$sources/ThermalProfiles.java" \
    "$sources/ThermalRequestState.java" \
    "$tests/PowerPolicyTest.java" \
    "$tests/ThermalConfigStoreTest.java" \
    "$tests/ThermalLifecycleTest.java" \
    "$tests/ThermalScenarioPolicyTest.java" \
    "$tests/ThermalProfilesTest.java" \
    "$tests/ThermalRequestStateTest.java"
for test in PowerPolicyTest ThermalConfigStoreTest ThermalRequestStateTest ThermalLifecycleTest \
        ThermalScenarioPolicyTest ThermalProfilesTest; do
    prebuilts/jdk/jdk21/linux-x86/bin/java -cp "$test_output" "me.sandai.dashpower.$test"
done
