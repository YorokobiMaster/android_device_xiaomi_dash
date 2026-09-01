Device configuration for Redmi Turbo 5 Max (dash)
=============================================

EN | [简体中文](README_zh.md)

The Redmi Turbo 5 Max is a ~~performance flagship powered by a nerfed previous-gen MTK flagship SoC~~ proper current-gen, full-fat MTK performance flagship, packed with a 120 Hz 6.83-inch AMOLED display and a massive 9,000 mAh Xiaomi Jinshajiang Battery.

## TL;DR

### Known issues

- Randomly enters BROM. (Possibly related to MiTEE; not yet confirmed.)
- Some reusable HyperOS features are not yet integrated.

### Untested

* Thermal / power management
* Fast charging
* Reverse wired charging
* VoWiFi

### Working

* Cellular / VoLTE
* Wi-Fi
* Bluetooth
* NFC
* Cameras
* Audio
* Sensors
* GNSS
* Always-on Display
* Fingerprint
* USB OTG
* Suspend / wake
  * Lift-to-wake
  * Gaze-to-wake
  * Tap-to-wake
* Encryption
* SELinux enforcing

### Not planned

* Face unlock

## Device specifications

Basic     | Spec Sheet
---------:|:---------------------------------------------------------
SoC       | MediaTek Dimensity 9500s (MT6991Z/ECZB, TSMC 3 nm)
CPU       | Octa-core: 1x 3.73 GHz Cortex-X925 & 3x 3.30 GHz Cortex-X4 & 4x 2.40 GHz Cortex-A720
GPU       | Immortalis-G925 MC12
Memory    | 12/16 GB LPDDR5X RAM
Storage   | 256/512 GB/1 TB UFS 4.1
Shipped Android version | 16 (HyperOS 3)
Battery   | 9000 mAh Si/C battery, 100W wired (PPS/PD3.0), 27W reverse wired
Display   | 6.83" AMOLED, 1280 x 2772 (1.5K), 120 Hz, 3840 Hz PWM, Dolby Vision / HDR10+ / HDR Vivid, 3500 nits peak
Rear camera  | 50 MP f/1.5 wide (OIS) + 8 MP f/2.2 ultrawide
Front camera | 20 MP f/2.2
Dimensions | 163 x 77.9 x 8.2 mm, 219 g
Ingress protection | IP66/IP68/IP69/IP69K
Biometrics | Ultrasonic under-display fingerprint
Connectivity | Wi-Fi 7, Bluetooth 5.4, NFC, IR blaster, USB-C 2.0

## Device picture

![Redmi Turbo 5 Max](redmi_turbo_5_max.png "Redmi Turbo 5 Max")

## License

[Apache-2.0](LICENSE)
