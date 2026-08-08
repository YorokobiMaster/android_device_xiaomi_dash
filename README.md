# LineageOS bring-up for Redmi Turbo 5 Max (`dash`)

This is the first-stage LineageOS 23.2 device target.

Verified stock facts used here:

- platform: MediaTek `mt6991`
- userspace ABI: arm64 only
- boot image header: v4
- kernel page size: 4096 bytes
- device shipping API: 36
- stock vendor/VNDK generation: Android 15 / API 35
- partition scheme: A/B dynamic partitions

The target builds separate ext4 `system.img`, `system_ext.img`, and
`product.img` images. Stock retains ownership of `vendor`, `odm`,
`system_dlkm`, `vendor_dlkm`, `odm_dlkm`, and `mi_ext`. This stage does not
build or package boot, init_boot, vendor_boot, vbmeta, super, or an OTA.

Current validation target:

```sh
source build/envsetup.sh
lunch lineage_dash-bp2a-userdebug
m systemimage systemextimage productimage
```
