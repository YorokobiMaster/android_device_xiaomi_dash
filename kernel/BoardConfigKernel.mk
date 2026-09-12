# Copyright (C) 2026 GitHub @YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

DASH_KLEAF_OUT := kernel/xiaomi/dash/bazel-bin/kernel_device_modules-6.6
DASH_KERNEL_INPUTS := vendor/xiaomi/dash/kernel-inputs
DASH_STOCK_INPUTS := vendor/xiaomi/dash/stock-305

TARGET_NO_KERNEL := false
TARGET_KERNEL_SOURCE := kernel/xiaomi/dash/kernel-6.6
TARGET_KERNEL_CONFIG := gki_defconfig
TARGET_KERNEL_VERSION := 6.6
TARGET_FORCE_PREBUILT_KERNEL := true
TARGET_PREBUILT_KERNEL := $(DASH_KLEAF_OUT)/mgk_64_k66_kernel_aarch64.user/Image.lz4
BOARD_KERNEL_IMAGE_NAME := Image.lz4

BOARD_USES_GENERIC_KERNEL_IMAGE := true
BOARD_BOOT_HEADER_VERSION := 4
BOARD_BOOTIMAGE_PARTITION_SIZE := 67108864
BOARD_VENDOR_BOOTIMAGE_PARTITION_SIZE := 67108864
BOARD_INIT_BOOT_IMAGE_PARTITION_SIZE := 8388608
BOARD_KERNEL_BASE := 0x00000000
BOARD_KERNEL_CMDLINE := bootopt=64S3,32N2,64N2
BOARD_RAMDISK_USE_LZ4 := true
BOARD_INCLUDE_DTB_IN_BOOTIMG := true
BOARD_PREBUILT_DTBIMAGE_DIR := $(DASH_KERNEL_INPUTS)
BOARD_MKBOOTIMG_ARGS += --header_version 4 --kernel_offset 0x80000000
BOARD_MKBOOTIMG_ARGS += --ramdisk_offset 0xa6f00000 --tags_offset 0x87c80000
BOARD_MKBOOTIMG_ARGS += --dtb_offset 0x87c80000
BOARD_INIT_BOOT_HEADER_VERSION := 4
BOARD_MKBOOTIMG_INIT_ARGS += --header_version $(BOARD_INIT_BOOT_HEADER_VERSION)

TARGET_NO_RECOVERY := true
BOARD_MOVE_RECOVERY_RESOURCES_TO_VENDOR_BOOT := true
BOARD_INCLUDE_RECOVERY_RAMDISK_IN_VENDOR_BOOT := true
BOARD_VENDOR_RAMDISK_FRAGMENT.recovery.PREBUILT := $(DASH_KERNEL_INPUTS)/vendor_ramdisk01

BOARD_USES_VENDOR_DLKMIMAGE := true
include $(DEVICE_PATH)/kernel/modules.mk

# Build only the top-level vbmeta from current image descriptors.
# Vendor stays on stock 305; retain its descriptor without rebuilding it.
BOARD_AVB_ENABLE := true
BOARD_AVB_KEY_PATH := vendor/lineage-priv/keys/avb.pk8
BOARD_AVB_ALGORITHM := SHA256_RSA4096
BOARD_AVB_MAKE_VBMETA_IMAGE_ARGS += --flags 3
BOARD_AVB_MAKE_VBMETA_IMAGE_ARGS += --include_descriptors_from_image $(DASH_STOCK_INPUTS)/vbmeta_vendor.img
