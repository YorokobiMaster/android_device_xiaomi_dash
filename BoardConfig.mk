# Copyright (C) 2026 @YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

DEVICE_PATH := device/xiaomi/dash

# Architecture. Stock dash userspace is 64-bit only.
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-a
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_VARIANT := generic
TARGET_CPU_VARIANT_RUNTIME := generic
TARGET_SUPPORTS_32_BIT_APPS := false
TARGET_SUPPORTS_64_BIT_APPS := true

# Platform identity established by the stock vendor properties and recovery
# configuration.
TARGET_BOARD_PLATFORM := mt6991
TARGET_BOOTLOADER_BOARD_NAME := mt6991
TARGET_NO_BOOTLOADER := true

# The supplied OSS kernel tree does not contain the complete dash
# device-module/config integration, so this stage builds no boot images.
TARGET_NO_KERNEL := true

# Stock images use a 4096-byte page size.
BOARD_KERNEL_PAGESIZE := 4096
BOARD_FLASH_BLOCK_SIZE := 262144

# Build the Lineage-owned framework partitions as separate ext4 filesystems.
TARGET_USERIMAGES_USE_EXT4 := true
TARGET_COPY_OUT_PRODUCT := product
TARGET_COPY_OUT_SYSTEM_EXT := system_ext
TARGET_COPY_OUT_VENDOR := vendor
TARGET_COPY_OUT_ODM := odm
TARGET_COPY_OUT_SYSTEM_DLKM := system_dlkm
TARGET_COPY_OUT_VENDOR_DLKM := vendor_dlkm
TARGET_COPY_OUT_ODM_DLKM := odm_dlkm
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_SYSTEM_EXTIMAGE_FILE_SYSTEM_TYPE := ext4

# These partitions remain stock EROFS images; the product configuration below
# explicitly disables rebuilding them.
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_ODMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_SYSTEM_DLKMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_VENDOR_DLKMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_ODM_DLKMIMAGE_FILE_SYSTEM_TYPE := erofs

# Stock dash logical-partition allocations. Fixed bounds keep the requested
# inode counts; build_image otherwise trims dynamically sized ext4 images back
# to roughly the installed inode usage.
BOARD_SYSTEMIMAGE_PARTITION_SIZE := 7299997696
BOARD_PRODUCTIMAGE_PARTITION_SIZE := 1199996928
BOARD_SYSTEM_EXTIMAGE_PARTITION_SIZE := 479997952

# Development inode headroom within those measured bounds.
BOARD_SYSTEMIMAGE_EXTFS_INODE_COUNT := 65536
BOARD_PRODUCTIMAGE_EXTFS_INODE_COUNT := 32768
BOARD_SYSTEM_EXTIMAGE_EXTFS_INODE_COUNT := 32768

# Do not define BOARD_SUPER_PARTITION_SIZE or dynamic-partition group sizes
# in this stage. Per-partition sizes above come from the stock LP metadata;
# image file sizes are not a substitute for the physical super layout.
