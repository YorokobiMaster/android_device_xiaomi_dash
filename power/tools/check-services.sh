#!/bin/bash
# Copyright (C) 2026 GitHub @YorokobiMaster
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
# Inspect installed DEX directly, not the unshrunk compilation headers.
services_jar=${1:-out/target/product/dash/system/framework/services.jar}
out/host/linux-x86/bin/dexdump -l xml "$services_jar" | awk '
/<package name=/ { split($0, a, "\""); package = a[2] }
/<class name=/ { split($0, a, "\""); cls = a[2] }
/<method name=/ {
    split($0, a, "\""); method = a[2]; params = ""; result = ""
}
/^ return=/ { split($0, a, "\""); result = a[2] }
/<parameter name=/ {
    split($0, a, "\""); params = params (params == "" ? "" : ",") a[4]
}
/<\/method>/ {
    if (package == "com.android.server.power")
        found[cls ":" method "(" params "):" result] = 1
}
END {
    n = split("DevicePowerManagerInternal:setListener(com.android.server.power.DevicePowerManagerInternal$Listener):void DevicePowerManagerInternal:onFling(int,int):void DevicePowerManagerInternal$Listener:onStateChanged(boolean,boolean):void DevicePowerManagerInternal$Listener:onLaunch(boolean):void DevicePowerManagerInternal$Listener:onFling(int,int):void", required, " ")
    for (i = 1; i <= n; i++) {
        if (!found[required[i]]) {
            print "FAIL: missing DEX method " required[i] > "/dev/stderr"
            failed = 1
        }
    }
    if (failed) exit 1
    print "PASS: all 5 device power interface DEX signatures retained"
}'
