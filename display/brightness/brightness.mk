# Copyright (C) 2026 GitHub @YorokobiMaster
# SPDX-License-Identifier: Apache-2.0
PRODUCT_PACKAGES += dash-brightness
PRODUCT_SYSTEM_SERVER_JARS_EXTRA += system_ext:dash-brightness

PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/display/brightness/config/display_id_4627039422300187648.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/displayconfig/display_id_4627039422300187648.xml \
    $(DEVICE_PATH)/display/brightness/config/multi_factor_thermal_brightness_control.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/displayconfig/multi_factor_thermal_brightness_control.xml
