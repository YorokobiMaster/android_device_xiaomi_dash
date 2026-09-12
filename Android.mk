# Copyright (C) 2026 GitHub @YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

LOCAL_PATH := $(call my-dir)
ifeq ($(TARGET_DEVICE),dash)

# Install the retained platform contents using Android's file-copy rules.
# Modules and modules.load are installed separately by the core depmod rules.
dash_platform_root := $(DASH_KERNEL_INPUTS)/platform
dash_platform_files := $(filter-out $(dash_platform_root)/lib/modules/modules.load,$(shell find $(dash_platform_root) -type f))
dash_platform_outputs := $(patsubst $(dash_platform_root)/%,$(TARGET_VENDOR_RAMDISK_OUT)/%,$(dash_platform_files))
$(foreach src,$(dash_platform_files),$(eval $(call copy-one-file,$(src),$(patsubst $(dash_platform_root)/%,$(TARGET_VENDOR_RAMDISK_OUT)/%,$(src)))))

define dash-platform-symlink
$(TARGET_VENDOR_RAMDISK_OUT)/$(1):
	@mkdir -p $$(dir $$@)
	ln -sfn '$(2)' '$$@'
endef
dash_platform_links := $(shell find $(dash_platform_root) -type l)
$(foreach src,$(dash_platform_links),$(eval $(call dash-platform-symlink,$(patsubst $(dash_platform_root)/%,%,$(src)),$(shell readlink '$(src)'))))
dash_platform_outputs += $(patsubst $(dash_platform_root)/%,$(TARGET_VENDOR_RAMDISK_OUT)/%,$(dash_platform_links))

# Preserve empty mount-point directories as well as files and symlinks.
dash_platform_dirs := $(patsubst $(dash_platform_root)%,$(TARGET_VENDOR_RAMDISK_OUT)%,$(shell find $(dash_platform_root) -type d))
dash_platform_dirs_stamp := $(call intermediates-dir-for,PACKAGING,dash_platform)/dirs.stamp
$(dash_platform_dirs_stamp): PRIVATE_DIRS := $(dash_platform_dirs)
$(dash_platform_dirs_stamp):
	@mkdir -p $(PRIVATE_DIRS) $(dir $@)
	touch $@
$(dash_platform_outputs): | $(dash_platform_dirs_stamp)
ALL_DEFAULT_INSTALLED_MODULES += $(dash_platform_outputs)

$(PRODUCT_OUT)/vbmeta.img: $(DASH_STOCK_INPUTS)/vbmeta_vendor.img
endif
