# Copyright (C) 2026 @YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

# 64-bit-only phone framework partition composition.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)
$(call inherit-product, device/xiaomi/dash/device.mk)

# dash builds are bleeding-edge; brand them as such.
LINEAGE_BUILDTYPE := RAWHIDE

$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

# MindTheGapps, trimmed to Play services + Play Store (vendor/gapps, baklava branch).
$(call inherit-product, vendor/gapps/arm64/arm64-vendor.mk)

# Keep this narrow overlay ahead of the Lineage common SystemUI overlay.
PRODUCT_PACKAGE_OVERLAYS := \
    device/xiaomi/dash/aov/overlay \
    $(PRODUCT_PACKAGE_OVERLAYS)

TARGET_FORCE_OTA_PACKAGE := false

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
