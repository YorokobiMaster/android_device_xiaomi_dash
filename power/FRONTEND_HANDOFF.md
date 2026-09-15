# Performance Mode 前端交接

2026-09-16。后端与两个入口的代码已实现；用户要求暂停编译，等待统一上机。
本次模块构建在 Soong 图生成阶段终止，新功能没有编译通过或实机验证结论。

## 已确定的产品规则

- 首次使用/旧配置缺少模式字段：均衡（Performance Mode 关闭）。
- 开关只影响自动策略；用户手动指定的应用档位优先。
- 未分类应用在两个模式下都为 normal（0）。
- 自动 class0：7/57；video：11/61；普通游戏：19/18；yuanshen 组：19/20；xingtie 组：19/25。
- 导航两个模式都为 10；其他不成对的分类保持现有表。
- Battery 原生开关标题：`Performance Mode`。
- 英文说明必须为：`Relaxes selected scheduling limits. May increase battery usage.`
- 快捷磁贴采用仪表盘图标，单色矢量，激活/未激活颜色由 SystemUI 控制。

## 代码入口

- `src/me/sandai/dashpower/ThermalBackend.java`：唯一配置/状态所有者、持久化、选表。
- `src/me/sandai/dashpower/ThermalProfiles.java`：唯一分类到基础/性能表的映射。
- `aidl/me/sandai/dashpower/IDashThermalService.aidl`：共享 Binder API v2。
- `DashThermal/src/me/sandai/dashthermal/PerformanceModeProvider.java`：通过 SettingsLib
  EntriesProvider 注入 Battery 开关；不需要修改上游 Settings。
- 同目录 `PerformanceModeTileService.java`：标准 QS 磁贴，在编辑列表可添加。
- 同目录 `PerformanceModeClient.java`：两个新入口共用的同步 Binder 访问；无独立配置缓存。
- 原有应用页面继续使用同目录 `ThermalServiceClient.java` 的异步接口。
- `DashThermal/res/drawable/ic_performance_mode.xml`：24dp 矢量图标。

完整接口、持久化和错误契约见 `THERMAL_API.md`。新 setter 追加在 AIDL 末尾，
旧 transaction 编号不变。getState 增加 performanceMode，新增 setPerformanceMode；
两个入口通过同一个 ContentObserver URI 接收更新、再读后端。
不要在 GUI 复制分类映射、直接写 sysfs，或另存一份性能开关。
现有温控总开关关闭时自动选表暂停，模式保留，QS 显示不可用。

## 待统一验证

- 编译 dash-power、dash-thermal-client、DashThermal、DashThermalBackendTests。
- Battery 开关是否正确注入；QS 可发现、添加和正常渲染。
- 两入口切换后的状态同步，界面重进/进程重建后重读状态。
- 自动基础/per 切换、未分类始终 0、手动覆盖不受影响。
- 配置重启持久化、旧配置迁移、总开关/应用覆盖保存不丢模式字段。
- Settings 与 SystemUI 的权限、Enforcing 行为和真正加载的 daemon 表。

本轮未扩展 PowerKeeper 的通话/相机/充电等完整场景状态机，也未增加新的调度参数。
性能差异来自所选原厂表；原 launch/fling 加速和省电过滤保留。
