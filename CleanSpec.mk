# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

ifeq ($(TARGET_DEVICE),dash)
# The shared installer puts Bluetooth in the vendor ramdisk and system_dlkm.
$(call add-clean-step, rm -f $(TARGET_OUT_VENDOR_DLKM)/lib/modules/bluetooth.ko)
endif
