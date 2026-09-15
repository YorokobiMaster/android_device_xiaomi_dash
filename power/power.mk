# Copyright (C) 2026 GitHub @YorokobiMaster
# SPDX-License-Identifier: Apache-2.0
ifeq ($(DASH_ENABLE_POWER_HOOKS),true)
PRODUCT_PACKAGES += dash-power dash-thermal-packages DashThermal
PRODUCT_SYSTEM_SERVER_JARS_EXTRA += system_ext:dash-power
DEVICE_PACKAGE_OVERLAYS += $(DEVICE_PATH)/power/overlay
endif
