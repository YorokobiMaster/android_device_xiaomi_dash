# dash 电源/温控后端续接

2026-09-16。工作目录 `/home/kokutoumikan/lineage-23.2`。
目的：记录场景覆盖的证据、实现状态和剩余上机验收。

## 2026-09-16 接盘结果

- 已实现原厂 305 的 63 条 `setting.xml` 规则、max-id 仲裁和 SwitchProcessor 映射；
  源码表已逐项与原 XML 比对一致。
- 已接入通话摘机、原厂相机 4K60/4K30/8K、低温充电、实际反向供电和抖音前台。
- 相机原广播定向到 `com.miui.powerkeeper`，因此增加同包名的平台签名兼容 APK，校验广播
  UID 属于原厂相机，再通过 API v3 通知唯一后端。
- 用户已决定：特殊事件只在应用设置为自动时参与；任何手动应用档位都直接胜出。
- IEC、SpecialCScenario、播放高帧和 SPTM_2 仍无可靠 Lineage 状态源，当前固定为关闭。
- 已写 host 回归源码并补 SELinux 源码；遵照用户要求，没有编译、安装或刷机。

## 用户现在需要什么

性能开关和应用选表已做，但用户发现原厂事件场景 B、复合场景 C 没有自动接入，
担心手机实际缺失对应策略。用户提出可能需要把小米那套搬来，询问路线。
上一轮建议：复用原厂 setting.xml 场景匹配规则，在现有后端适配事件输入，
而不是直接移植整个 PowerKeeper。**这只是建议，用户尚未批准具体移植方案或范围。**
本轮只要求写 handoff；下一轮先看现有证据和状态来源缺口，再与用户确定范围。

用户明确要求：**别编译，等会一起上机。** 此约束继续有效；不要自行编译、安装或刷机。
前端已移交其他 agent；不要修改其正在工作的界面文件，也不要擅自派更多子代理。

## 提交与工作区

设备仓库：`/home/kokutoumikan/lineage-23.2/device/xiaomi/dash`。

- 最新本轮提交：`9d271ac64899afddc56f9c27c342255d29274c68`
  `dash: add power backend and balanced/performance mode controls`。
- 该提交将此前未跟踪的后端主体、测试、构建接入、SELinux 接入和这次模式/UI 改动一起纳入；
  不是仅改一个开关。未 push。
- 框架配套改动以 `power/patches/frameworks-base.patch` 分发；不是把上游框架仓库一起提交了。
- 交接写入时前端已有新的未提交修改（MainActivity、ThermalServiceClient、activity_main、strings）；
  它们是在上述提交之后出现的，不属于本代理待清理内容。
- 还有相机、唤醒、分区等未提交改动。先 git status，不能 reset/清理或混入本任务提交。
- 系统根目录不是单一 Git 仓库。docs 下的报告是本地证据；设备仓库下 power 文档随设备代码保存。

先读本目录 `FRONTEND_HANDOFF.md`、`THERMAL_API.md`，需要运行细节再读 `README.md`。

## 已定产品规则，不要重新询问

- Performance Mode 初始关闭，默认均衡；旧配置缺字段也按均衡。
- 只切换自动策略；手动应用覆盖优先。
- 未分类始终 normal（0），性能模式也不变为 50。
- class0 自动 7/57；video 11/61；普通游戏 19/18；yuanshen 组 19/20；xingtie 组 19/25。
- navigation 两边都是 10；其余固定分类保留现有结果。
- Battery 标题 `Performance Mode`；说明原文：
  `Relaxes selected scheduling limits. May increase battery usage.`
- 标准 QS 磁贴、仪表盘单色矢量，SystemUI 负责状态颜色。当前实现加入可添加列表，未强改用户布局。
- 现有总温控关闭时模式保留，自动选表暂停；性能入口不擅自重开总温控。
- 手动应用覆盖优先于全局模式和所有特殊事件；特殊事件只在自动模式参与。

## 当前代码实际覆盖

`power/src/me/sandai/dashpower/`：

- DashPowerService / PowerPolicy / MtkPowerClient：启动 hint 21，滑动 hint 900，
  有限时长、释放、HAL 重连隔离；亮灭屏、省电策略、运行开关过滤。不是完整 stock 启动冷热分类。
- ThermalBackend：前台任务/焦点、用户、亮灭屏/锁屏、包卸载、配置、事件采样及 daemon 生命周期。
  后端唯一写 sconfig；没有事件时熄屏/锁屏/无有效前台回到 0。
- ThermalScenarioPolicy：固定 305 场景树与最终映射；缺失的四类事件源保持关闭。
- ThermalConfigStore：原有原子保存流程；同一每用户 JSON 增加 performanceMode。
- ThermalLifecycle / ThermalRequestState：原有生命周期观察和有限重提交；写节点成功不是加载 ACK。

对外只有 `dash_thermal` Binder 服务，共享 AIDL API v3：追加相机兼容通知，
getState 增加 performanceMode；旧 transaction 编号保留。GUI 无分类副本、无独立开关存储。
Settings 用 EntriesProvider/ProviderSwitch 注入；QS 用 TileService；两入口通过共享
ContentObserver URI 通知后重读后端。详细权限、工作资料语义、失败契约见 THERMAL_API。

## B/C 缺口——不要把“表存在”说成“自动生效”

B：通话 5、视频通话 14、4K/8K 16/17、高帧 26/76、低温充电 27、反向充电 29、
抖音特殊状态 28 等，依赖事件。C：IEC 500/501、游戏与通话/微信等复合场景 700/701/702。
当前没有完整事件输入、场景仲裁和退出恢复；相机包名最多静态到 15，不代表录像细分已做。
原厂 daemon 保留表，不会因此替我们的 Java 后端产生这些场景输入。
若另一个写者偶尔写特殊 ID，现有前台重算仍可能覆盖；不要假定可以让两个写者各管一半。

截图还将 SPTM 13 列入 B，但 dash 原厂树的 SPTM_2=98 条件可匹配 noLimits 6；
不能按截图表名直接实现为写 13。具体可达性以 odm 树和 SwitchProcessor 为准。

## 已查明的 stock 链路

报告目录 `/home/kokutoumikan/lineage-23.2/docs/dash/power-hal/reports/`：

1. **先读 `powerkeeper-mode-conversion.md`**：最新纠正过的模式转换与初始化证据。
2. `stock-default-profile-mapping.md`：分类、13 个输入元素、场景树、最终映射和逆向偏移。
   §4.3 已纠正；其他早期概括仍需参照下面的纠错点，不能照单全收。
3. `thermal-design.md`：历史提案，不是当前产品决策；顶部有模式证据更新。

链路：包名/事件 → 13 个元素 → setting.xml 所有匹配分支取最大场景 ID
→ SwitchProcessor 二次映射 → sconfig → 原厂 mi_thermald。

13 个输入按序为 SZForegroundGroup、HighFpsTopActivity、Screen、Call、Camera、
PowerMode、IEC、SpecialCScenario、Fps、LowTempCharge、SpecialForeground_3、
ReverseChargeState、SPTM_2。0 是通配，98/99 通常表示开启/关闭，具体元素可有其他值。
这是一套数据驱动匹配，不必手写所有状态组合；但每个元素的真实语义和输入来源仍需适配。

关键纠错：

- enhance 归内部 2/BALANCE，只有 performance→1 才在正常事件处理后开启 per（98）。
- PowerKeeper 内部 Provider 在用户配置缺失时写 enhance；normal 输入也规范化成 enhance。
  不要再说 Provider 初始化在 DEX 外，或 enhance 表示性能。
- initCurrentState 的非 2→98 与事件的仅 1→98 判定不同；注册监听时立即补发当前模式，
  在 thermal_enhance_mode_enable=true 下会收敛。不能拿初始化单函数推断长期状态。
- 场景编号不等于最终表：navigation 场景 60→10；xingtie 场景 75→25。
- 游戏显式优化键 key_is_enable_optimize_game 优先，其次 needOptimize；
  均缺失才按场景余数 >=50 选择优化表。当前我们的后端未复刻这两个独立 stock 开关。
- 王者在 yuanshen 分类，原神在 xingtie 分类；组名不能按字面理解。
- SPTM_2=98 配合 Call=99/IEC=99 可到 6，不是总回 0。
- 熄屏不保证 stock 总回 normal（电话/视频通话/低温充电等例外）；我们的回退 0 是现有简化实现。
- 最大 ID 仲裁意味着 reverse 29 可能输给 per-normal 50；不要自行假设“特殊事件总优先”。

## 原始材料在哪里，不要重新解包或复制固件树

- `/home/kokutoumikan/lineage-23.2/tmp/disposable-anytime/dash-frequency-20260912/PowerKeeper.dex`
  和同目录 APK、powerkeeper-dash-local.json（dash 本地 overlay）。版本 4.2.00/40200，305 基线。
- 选择性反汇编：`/home/kokutoumikan/lineage-23.2/tmp/disposable-anytime/dash-power-mode/`。
  含 EventsAggregator、PowerModeListener、Provider 等；可复用而不是重扫所有类。
- 原厂实际 odm 树：
  `/home/kokutoumikan/turbo5max/source-twrp16/repack/.work/partitions/odm/etc/setting.xml`。
  不是 APK assets 的那套树；运行时云控仍可能覆盖。
- 同一 partitions 下 `odm/etc/thermal-*.conf`、`vendor/bin/mi_thermald` 已存在。
- 解密已还原：AES-128-CBC，key 是该 ELF 文件偏移 0xc84b4 起 16 字节，
  IV 是 0xc84c5 起 16 字节；用 openssl stdin/stdout 内存处理即可，无需另造整树。
- 工具：`out/host/linux-x86/bin/dexdump -d`。jadx 当时未安装，不需要为此先装工具。

本轮直接比较 class0/per-class0：结构相同，per 去掉 CPU4 的 2.1GHz 中间台阶、
CPU7 的 2.0GHz 台阶，并放宽 CPU7 部分后续限频；也有 BAT 控制和 connsys 差异。
因此“同分类的性能版本，调整/放宽部分限制”已有依据，不需要逐项证明才能作总体推断。
但不要再断言“全局性能模式提高所有应用的独立调度参数”；之前此断言已撤回。

## 验证边界

已实机验证的是较早安装产物：
`docs/dash/power-hal/reports/extreme-20260914/report.md`，Lineage incremental 1789386421，Enforcing。
王者游戏进程区间 120/120 样本实际加载 xingtie（25），CPU 三簇上限 1.4/2.8/3.3GHz，
观察到真实频率达到上限，电池 32.6→40.5°C；有 4 段调度轨迹。
GPU 利用率来源无效，未得到游戏 FPS；不能宣称帧率收益或独立调度优化。
这不是当前 9d271ac 的整体验证，也不是 B/C 事件覆盖验证。

9d271ac 新模式：静态 XML 检查通过；旧 host 检查曾通过，新 Android 持久化测试只写了源码。
构建日志 `tmp/disposable-anytime/dash-performance-build.log`，在用户叫停后已终止进程。
未完成 Android 编译、安装或新功能实测。不要为交接再次开编译。

## 建议的新会话第一步

先列一个小表：原厂元素 / 现有可用状态源 / 缺失来源 / 需用户决定的行为。
优先区分日常通话、相机与小米专用测试/充电状态；无需一次覆盖整个 PowerKeeper。
同时明确特殊事件、手动应用覆盖、全局模式之间的优先级，未经用户决定不要默默改产品语义。
若要直接移植 PowerKeeper，先列它实际依赖的小米接口与副作用；目前没有完整 APK 移植可行性结论。

用户希望明确结论、合理推断和及时行动；不要再次纠缠“所有细节未经逐项证明所以无法判断”。
也不要把路线讨论擅自变成全面重构。按项目 AGENTS.md 执行，保留共享缓存及其他 agent 改动。
