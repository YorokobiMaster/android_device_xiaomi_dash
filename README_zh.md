Xiaomi dash 的设备树
=============================================

[EN](README.md) | 简体中文

Redmi Turbo 5 Max 是一款~~拿上一代MTK旗舰芯片阉割后的~~MTK正代满血高性能旗舰。具体配置请看下文。

因为没有Poco，只能做国行版本。目前只适配了 LineageOS 24.0。

## 功能

- [x] 移动通信 / VoLTE
- [x] Wi-Fi
- [x] 蓝牙
- [x] NFC
- [x] 相机
- [x] GPS
- [x] USB OTG
- [x] 背部双环灯带
- [x] 指纹
- [x] 自动亮度
- [x] Dolby Atmos
- [x] Dolby Vision
- [x] 澎湃应用调度
- [x] 息屏显示
- [x] SELinux 强制模式
- [x] 充电
  - 快速充电
  - 反向充电
  - 限制充电
  - 旁路充电（以说明为准）
- [x] 休眠与唤醒
  - 抬起唤醒 / 放下休眠
  - 双击唤醒
  - 屏幕注意力
- [x] 加密 / 解密
- [x] 人脸解锁

## 设备详情

基本信息  | 详细参数
---------:|:---------------------------------------------------------
SoC       | 联发科 ~~正代满血旗舰SoC~~天玑 9500s (MT6991Z/ECZB)
CPU       | 八核：1x 3.73 GHz Cortex-X925 & 3x 3.30 GHz Cortex-X4 & 4x 2.40 GHz Cortex-A720
GPU       | Immortalis-G925 MC12
RAM      | 12/16 GB LPDDR5X ~~理财产品~~
ROM      | 256/512 GB / 1 TB UFS 4.1 ~~还在观望的小伙伴们你们好啊，我是存储颗粒，今天来点大家想看的东西~~
出厂系统  | Android 16 (澎湃OS 3)~~能用就行~~
电池      | 9000 mAh ~~绝赞锁容~~硅碳负极电池, 100W 有线快充 ~~真给看真实充电功率你又不高兴~~, 27W 有线反向充电 ~~可惜被拴着的好功能~~
屏幕      | 6.83" AMOLED, 1280 x 2772 (1.5K), 120 Hz, 3840 Hz PWM 调光, 杜比视界 / HDR10+ / HDR Vivid, 3500 nits 峰值亮度
后置相机  | 50 MP f/1.5 主摄 (OIS) + 8 MP f/2.2 超广角 ~~能扫码就行~~
前置相机  | 20 MP f/2.2
尺寸      | 163 x 77.9 x 8.2 mm, 219 g
防护等级  | IP66 / IP68 / IP69 / IP69K ~~图一乐~~
生物识别  | 屏下超声波指纹
连接功能    | Wi-Fi 7, ~~开倒车的~~Bluetooth 5.4, NFC, 红外遥控~~帮大忙了~~, ~~等级森严的~~USB 2.0

## Credits

- [AOSPA](https://github.com/AOSPA)
- [PixelOS](https://github.com/PixelOS-AOSP)
- [WeiguangTWK](https://github.com/WeiguangTWK) 的 LineageOS 伪装补丁
- 来自 [xiaomi-dash](https://github.com/xiaomi-dash) 的部分参考
- 我的钱包
- 以及所有热心机友与开发者

## 开源协议 (License)

[Apache-2.0](LICENSE)

## 设备外观 (Device picture)

![Redmi Turbo 5 Max](redmi_turbo_5_max.png "Redmi Turbo 5 Max")
