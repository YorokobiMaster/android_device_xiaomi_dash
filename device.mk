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
    $(DEVICE_PATH) \
    $(DEVICE_PATH)/power \
    $(DEVICE_PATH)/vintf \
    hardware/lineage/interfaces/power-libperfmgr \
    vendor/xiaomi/dash

# Bluetooth profiles exposed by the stock phone product.
PRODUCT_PRODUCT_PROPERTIES += \
    bluetooth.profile.a2dp.source.enabled=true \
    bluetooth.profile.asha.central.enabled=true \
    bluetooth.profile.avrcp.target.enabled=true \
    bluetooth.profile.bas.client.enabled=true \
    bluetooth.profile.gatt.enabled=true \
    bluetooth.profile.hfp.ag.enabled=true \
    bluetooth.profile.hid.device.enabled=true \
    bluetooth.profile.hid.host.enabled=true \
    bluetooth.profile.map.server.enabled=true \
    bluetooth.profile.opp.enabled=true \
    bluetooth.profile.pan.nap.enabled=true \
    bluetooth.profile.pan.panu.enabled=true \
    bluetooth.profile.pbap.server.enabled=true \
    bluetooth.profile.sap.server.enabled=true

PRODUCT_PACKAGES += DashFod

PRODUCT_PACKAGES += \
    DashFrameworkResOverlay \
    DashRefreshRate \
    DashSettingsOverlay \
    DashWifiOverlay \
    ImsService \
    MtkGbaService \
    android.hardware.power-service.lineage-libperfmgr \
    dash-ims-appcompat \
    mediatek-common \
    mediatek-ims-base \
    dash-ims-telephony-metrics \
    dash-displayfeature-compat \
    dash_powerhint.json \
    dash-unavailable-features \
    dash-vibrator-adapter \
    init.dash-system_ext.rc \
    libhidltransport \
    libmtk_vt_wrapper \
    libvcodec_cap \
    libvcodec_capenc \
    privapp-permissions-mediatek-ims \
    sysconfig-com.mediatek.ims \
    vendor.mediatek.hardware.videotelephony-V1-ndk \
    vendor.mediatek.hardware.videotelephony@1.0

# MediaTek's retained IMS and GBA services load these classes from the boot
# class path. Keep the device additions after the common platform jars.
PRODUCT_BOOT_JARS_EXTRA += \
    system_ext:mediatek-common \
    system_ext:mediatek-ims-base \
    system_ext:dash-ims-telephony-metrics

DEVICE_PACKAGE_OVERLAYS += \
    $(DEVICE_PATH)/overlay
