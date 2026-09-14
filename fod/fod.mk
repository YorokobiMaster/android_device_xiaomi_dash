# Copyright (C) 2026 GitHub @YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

PRODUCT_PACKAGES += DashFod DashPinLowOverlay dash-fod
PRODUCT_SYSTEM_SERVER_JARS_EXTRA += system_ext:dash-fod

# Resource arrays replace rather than merge. Keep FOD registered whether or not
# the optional power service is enabled, without changing its implementation.
ifeq ($(DASH_ENABLE_POWER_HOOKS),true)
DEVICE_PACKAGE_OVERLAYS := $(DEVICE_PATH)/fod/overlay-power $(DEVICE_PACKAGE_OVERLAYS)
else
DEVICE_PACKAGE_OVERLAYS := $(DEVICE_PATH)/fod/overlay $(DEVICE_PACKAGE_OVERLAYS)
endif
