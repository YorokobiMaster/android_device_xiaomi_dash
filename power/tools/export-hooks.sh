#!/bin/bash
# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0
set -eu

power_hook=${1:-3aa30413ccd48d47a3d547219826bde8f7ce157d}
activity_hook=${2:-3c3b69aaa5963df44f14e448eaaabc2da7304cd2}
hooks=$(git -C frameworks/base show --format= --no-color --no-ext-diff "$power_hook" "$activity_hook")
{
    printf '# LineageOS 24.0 hooks for device power and resumed activity events.\n'
    printf '# Sources: %s, %s\n\n' "$power_hook" "$activity_hook"
    printf '%s\n' "$hooks" | sed 's/^ $//'
} > device/xiaomi/dash/power/patches/frameworks-base.patch
