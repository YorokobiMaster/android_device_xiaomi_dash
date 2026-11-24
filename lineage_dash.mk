# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

# What's this?
PRODUCT_NAME := lineage_dash
PRODUCT_DEVICE := dash
PRODUCT_BRAND := Redmi
PRODUCT_MODEL := 2602BRT18C
PRODUCT_MANUFACTURER := Xiaomi
PRODUCT_GMS_CLIENTID_BASE := android-xiaomi

# Framework partition identity.
PRODUCT_SYSTEM_NAME := dash
PRODUCT_SYSTEM_DEVICE := dash
PRODUCT_SYSTEM_BRAND := Redmi
PRODUCT_SYSTEM_MODEL := 2602BRT18C
PRODUCT_SYSTEM_MANUFACTURER := Xiaomi

PRODUCT_RELEASE_NAME := dash
PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS :=

# 64-bit-only
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)
$(call inherit-product, device/xiaomi/dash/device.mk)
$(call inherit-product, device/xiaomi/dash/lineage.mk)

$(call inherit-product-if-exists, vendor/sandai/config/local.mk)

# bleeding-edge? probably.
LINEAGE_BUILDTYPE := RAWHIDE

# I don't like Aperture.
PRODUCT_NO_CAMERA := true
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)
