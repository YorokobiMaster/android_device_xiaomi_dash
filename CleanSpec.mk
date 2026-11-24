# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

ifeq ($(TARGET_DEVICE),dash)
# The old Lineage installer left these files outside the new module ZIPs.
$(call add-clean-step, rm -f $(TARGET_VENDOR_RAMDISK_OUT)/lib/modules/nt38771_touch_dash.ko $(TARGET_VENDOR_RAMDISK_OUT)/lib/modules/xiaomi_touch_dash.ko $(TARGET_OUT_VENDOR_DLKM)/lib/modules/bluetooth.ko)
endif
