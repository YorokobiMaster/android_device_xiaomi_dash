# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

# Lineage charging controls for the stock kernel.
PRODUCT_PACKAGES += \
    dash-fast-charge \
    dash-health
DEVICE_PACKAGE_OVERLAYS += $(DEVICE_PATH)/health/overlay
$(call soong_config_set,dash,charging_settings_lib,//device/xiaomi/dash:dash-charging-speed-settings)
