# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

DEVICE_PATH := device/xiaomi/dash

# Shared hardware implementation.
DASH_HARDWARE_PATH ?= hardware/xiaomi/dash
$(call inherit-product, $(DASH_HARDWARE_PATH)/dash.mk)
$(call inherit-product, $(DASH_HARDWARE_PATH)/kernel/device.mk)
$(call inherit-product, $(DASH_HARDWARE_PATH)/boot/device.mk)

# Device features
$(call inherit-product, $(DEVICE_PATH)/fod/fod.mk)
$(call inherit-product, $(DEVICE_PATH)/display/brightness/brightness.mk)
$(call inherit-product, $(DEVICE_PATH)/display/livedisplay/livedisplay.mk)
$(call inherit-product, $(DASH_HARDWARE_PATH)/power/power.mk)
$(call inherit-product, $(DASH_HARDWARE_PATH)/ims/ims.mk)
$(call inherit-product, $(DEVICE_PATH)/face/face.mk)

# Device-specific system_server startup list.
$(call inherit-product, $(DEVICE_PATH)/device-services.mk)

# Product additions
$(call inherit-product-if-exists, vendor/xiaomi/dash-miuicamera/miuicamera.mk)

# Dynamic-partition device.
PRODUCT_USE_DYNAMIC_PARTITIONS := true
PRODUCT_USE_DYNAMIC_PARTITION_SIZE := true

# Keep the stock super layout; build partition images rather than super.img.
PRODUCT_BUILD_SUPER_PARTITION := false

# Match the stock payload's zstd / COW v2 format.
PRODUCT_VIRTUAL_AB_COMPRESSION_METHOD := zstd
PRODUCT_VIRTUAL_AB_COW_VERSION := 2
PRODUCT_VIRTUAL_AB_COMPRESSION_FACTOR := 4096
$(call inherit-product, $(SRC_TARGET_DIR)/product/generic_ramdisk.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/virtual_ab_ota/vabc_features.mk)

# The retained vendor image cannot carry these runtime settings.
PRODUCT_SYSTEM_PROPERTIES += \
    ro.virtual_ab.enabled=true \
    ro.virtual_ab.compression.enabled=true \
    ro.virtual_ab.userspace.snapshots.enabled=true \
    ro.virtual_ab.batch_writes=true \
    ro.virtual_ab.ublk.enabled=false

PRODUCT_PACKAGES += \
    update_engine \
    update_verifier
PRODUCT_PACKAGES_DEBUG += update_engine_client

# A performance flagship powered by a nerfed previous-gen MTK flagship SoC.
PRODUCT_SHIPPING_API_LEVEL := 36
PRODUCT_TARGET_VNDK_VERSION := 35

# Retain the stock vendor-side images.
PRODUCT_BUILD_VENDOR_IMAGE := false
PRODUCT_BUILD_ODM_IMAGE := false
PRODUCT_BUILD_ODM_DLKM_IMAGE := false

PRODUCT_BUILD_DTBO_IMAGE := false
PRODUCT_BUILD_DEBUG_BOOT_IMAGE := false
PRODUCT_BUILD_DEBUG_VENDOR_BOOT_IMAGE := false

# Permit OTA packaging without building a source recovery fstab.
PRODUCT_BUILD_GENERIC_OTA_PACKAGE := true

DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE := \
    $(DEVICE_PATH)/framework_compatibility_matrix.xml

PRODUCT_SOONG_NAMESPACES += \
    $(DEVICE_PATH) \
    vendor/xiaomi/dash

# Properties
$(call inherit-product, $(DEVICE_PATH)/configs/properties.mk)

# Display
PRODUCT_PACKAGES += \
    DashRefreshRate \
    dash-displayfeature-compat

# Init
PRODUCT_PACKAGES += \
    init.dash-system_ext.rc

# HIDL compatibility
PRODUCT_PACKAGES += \
    android.hidl.allocator@1.0-service \
    libhidltransport

# Overlays
DEVICE_PACKAGE_OVERLAYS += \
    $(DEVICE_PATH)/overlay

PRODUCT_PACKAGES += \
    DashFrameworkResOverlay \
    DashSettingsProviderOverlay \
    DashTelecommOverlay \
    DashTelephonyOverlay \
    DashWifiOverlay
