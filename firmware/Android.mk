# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

LOCAL_PATH := $(call my-dir)

ifeq ($(TARGET_DEVICE),dash)
# Stock OS4.0.4.0.XPLCNXM boot logo.
$(call add-radio-file,logo.img)
endif
