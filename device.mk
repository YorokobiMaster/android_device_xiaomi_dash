# Copyright (C) 2026 @YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

DEVICE_PATH := device/xiaomi/dash

# The stock device is an A/B dynamic-partition device. This stage builds the
# Lineage-owned framework partitions without packaging an OTA or super image.
PRODUCT_USE_DYNAMIC_PARTITIONS := true
PRODUCT_USE_DYNAMIC_PARTITION_SIZE := true

# dash launched on Android 16 while retaining an Android 15 vendor/VNDK
# contract. These levels are intentionally separate.
PRODUCT_SHIPPING_API_LEVEL := 36
PRODUCT_TARGET_VNDK_VERSION := 35

# Retain the stock vendor-side images for this stage.
PRODUCT_BUILD_VENDOR_IMAGE := false
PRODUCT_BUILD_ODM_IMAGE := false
PRODUCT_BUILD_SYSTEM_DLKM_IMAGE := false
PRODUCT_BUILD_VENDOR_DLKM_IMAGE := false
PRODUCT_BUILD_ODM_DLKM_IMAGE := false

DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE := \
    $(DEVICE_PATH)/framework_compatibility_matrix.xml

PRODUCT_SOONG_NAMESPACES += \
    $(DEVICE_PATH)
