# Copyright (C) 2026 GitHub @YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

# Minimal arm64 GMS profile: Play services, Play Store, provisioning support,
# required permissions/sysconfig, and the framework overlays they depend on.
PRODUCT_SOONG_NAMESPACES += \
    vendor/gapps/arm64 \
    vendor/gapps/common \
    vendor/gapps/overlay

PRODUCT_PACKAGES += \
    GmsCore \
    Phonesky \
    GoogleCalendarSyncAdapter \
    GoogleContactsSyncAdapter \
    GoogleFeedback \
    GooglePartnerSetup \
    GoogleServicesFramework \
    com.google.android.dialer.support \
    com.google.android.dialer.support.xml \
    d2d_cable_migration_feature.xml \
    default-permissions-google.xml \
    default-permissions-mtg.xml \
    gapps.rc \
    gms_fsverity_cert.der \
    google-hiddenapi-package-allowlist.xml \
    google.xml \
    privapp-permissions-google-product.xml \
    privapp-permissions-google-system-ext.xml \
    privapp-permissions-mtg.xml \
    sysconfig-mtg.xml \
    GmsOverlay \
    GmsSettingsOverlay \
    GmsSettingsProviderOverlay \
    GmsSetupWizardOverlay

ifeq ($(TARGET_IS_GROUPER),)
PRODUCT_PACKAGES += \
    GoogleRestore
endif
