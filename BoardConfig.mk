# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

DEVICE_PATH := device/xiaomi/dash

# Architecture
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-a
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_VARIANT := generic
TARGET_CPU_VARIANT_RUNTIME := generic
TARGET_SUPPORTS_32_BIT_APPS := false
TARGET_SUPPORTS_64_BIT_APPS := true

# Platform identity
TARGET_BOARD_PLATFORM := mt6991
TARGET_BOOTLOADER_BOARD_NAME := mt6991
TARGET_NO_BOOTLOADER := true

DASH_HARDWARE_PATH ?= hardware/xiaomi/dash
DASH_KERNEL_WORKSPACE := kernel/platform/kernel-6.6
include $(DASH_HARDWARE_PATH)/kernel/BoardConfig.mk
include $(DASH_HARDWARE_PATH)/boot/BoardConfig.mk

# Keep Lineage's native UAPI-only Kleaf target. Soong owns the kernel image.
BOARD_USES_SOONG_KERNEL := true
TARGET_KERNEL_SOURCE := $(DASH_KERNEL_WORKSPACE)/kernel_device_modules-6.6
TARGET_KERNEL_PLATFORM_TARGET := kernel
include $(DASH_KERNEL_WORKSPACE)/build/bazel_mgk_rules/android/config.mk

TARGET_SCREEN_DENSITY := 480

# Framework partition filesystems.
TARGET_COPY_OUT_PRODUCT := product
TARGET_COPY_OUT_SYSTEM_EXT := system_ext
TARGET_COPY_OUT_VENDOR := vendor
TARGET_COPY_OUT_ODM := odm
TARGET_COPY_OUT_SYSTEM_DLKM := system_dlkm
TARGET_COPY_OUT_VENDOR_DLKM := vendor_dlkm
TARGET_COPY_OUT_ODM_DLKM := odm_dlkm
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_SYSTEM_EXTIMAGE_FILE_SYSTEM_TYPE := erofs

BOARD_USES_METADATA_PARTITION := true

# Update only ROM-owned partitions; partial payloads preserve stock partitions.
AB_OTA_UPDATER := true
AB_OTA_PARTITIONS := \
    boot \
    init_boot \
    logo \
    product \
    system \
    system_dlkm \
    system_ext \
    vbmeta \
    vendor_boot \
    vendor_dlkm
BOARD_PARTIAL_OTA_UPDATE_PARTITIONS_LIST := $(AB_OTA_PARTITIONS)
BOARD_PACK_RADIOIMAGES += logo

# HyperOS uses a 12 GiB super partition with a single main group.
BOARD_SUPER_PARTITION_SIZE := 12884901888
BOARD_SUPER_PARTITION_GROUPS := main
BOARD_MAIN_SIZE := 12874416128
BOARD_MAIN_PARTITION_LIST := product system system_dlkm system_ext vendor_dlkm

TARGET_USERIMAGES_USE_F2FS := true

# Choose a preferred file system.
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_ODMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_ODM_DLKMIMAGE_FILE_SYSTEM_TYPE := erofs

# I don't modify /vendor.
TARGET_USES_PREBUILT_VENDOR_SEPOLICY := true

BOARD_VENDOR_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/vendor
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += \
    $(DEVICE_PATH)/sepolicy/system_ext/private

include $(DASH_HARDWARE_PATH)/BoardConfig.mk

include $(DEVICE_PATH)/display/brightness/BoardConfig.mk
include $(DEVICE_PATH)/fod/BoardConfig.mk
include $(DASH_HARDWARE_PATH)/power/BoardConfig.mk
include $(DASH_HARDWARE_PATH)/ims/BoardConfig.mk
include $(DEVICE_PATH)/display/livedisplay/BoardConfig.mk

# BoardConfig is loaded separately from product inheritance.
ifeq ($(TARGET_PRODUCT),lineage_dash)
include $(DEVICE_PATH)/BoardConfigLineage.mk
endif
