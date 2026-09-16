# 应用温控后端接口（给 Kimi）

Copyright (C) 2026 GitHub @YorokobiMaster. Apache-2.0.

前端负责原生设置页面；选表、持久化、前台跟踪和 sysfs 写入均由后端负责。接口版本 3。当前验收进度见 `docs/dash/power-hal/reports/thermal-backend.md`，不能把接口文档当成已部署证明。

## 接入

Soong 模块 `dash-thermal-client` 提供 `me.sandai.dashpower.IDashThermalService`，前端添加到 `static_libs`。源码在 `power/aidl/me/sandai/dashpower/IDashThermalService.aidl`，不要另写一份 AIDL。

使用平台 API：`IDashThermalService.Stub.asInterface(ServiceManager.checkService("dash_thermal"))`。服务不存在、Binder 死亡或 `ready=false` 时显示不可用；重连后重读配置。调用在后台线程执行，不能阻塞设置页面主线程。

客户端必须平台签名且拥有 `android.permission.WRITE_SECURE_SETTINGS`；读取其他用户配置还需 `INTERACT_ACROSS_USERS_FULL`。服务查找已开放给 `system_app` / `platform_app` 域；若前端使用其他 SELinux 域，需另核对。APK 的包名前缀用 `me.sandai`，权限检查不依赖包名字符串。

所有 userId 都必须是具体用户编号，不接受 CURRENT/ALL 等负数。应用配置按 `(userId, packageName)` 隔离，包必须安装于该用户。全机只能同时选一张表。

## 方法与返回值

| 方法 | 契约 |
| --- | --- |
| `getApiVersion()` | 返回 3；新增方法前检查版本，旧方法的 transaction 编号不变。 |
| `getState(userId)` | 返回下表 Bundle；读取该用户的开关和覆盖。 |
| `getAppPolicy(userId, packageName)` | 返回 `defaultProfile`、`overrideProfile`、`selectedProfile`（int）和 `stockGroup`（String）。selected 是配置解析结果。 |
| `setAppProfile(userId, packageName, profileId)` | `-1` 删除覆盖、恢复自动；其他值必须属于可手选枚举。成功返回表示文件同步、原子替换及父目录同步均已完成，随后异步重算当前前台；不表示 daemon 已加载。 |
| `setEnabled(userId, enabled)` | 原子保存开关，保留该用户覆盖；关闭当前用户功能后异步请求 normal。 |
| `setPerformanceMode(userId, enabled)` | 保存均衡(false)/性能(true)，异步重算当前前台；只影响自动策略，保留总开关和全部手动覆盖。工作资料应使用主用户模式，不接受向资料用户设置独立模式。 |
| `notifyCameraRecordState(recording, quality, fps)` | 仅供平台签名的 `com.miui.powerkeeper` 相机兼容桥调用；把原厂相机的录像广播转换为场景输入。普通设置客户端不得调用。 |

`getState` 字段：

| 字段 | 类型 / 含义 |
| --- | --- |
| version | int，接口版本 3 |
| ready | boolean，默认数据和监听初始化完成；独立于 daemon 健康状态，不表示监听就绪或已加载 |
| enabled | boolean，所查询用户的配置开关 |
| performanceMode | boolean，所查询用户（工作资料取其主用户）的模式；默认 false，表示配置选择，不是 daemon 加载确认 |
| currentUserId | int，当前前台系统用户 |
| foregroundUserId / foregroundPackage | int / String，目标应用；不属于所查询用户时返回 -1 / 空串 |
| requestedProfile | int，当前观测生命周期内最近成功写入节点的请求值；生命周期改变、不可用或 resync 后未提交时为 -1 |
| daemonRunning | boolean，最近一次 init 属性观测为 running；不是 watch-ready ACK |
| requestEpoch | long，本后端的请求代际，生命周期变化或手动 resync 时递增，不是 daemon PID |
| lifecycleError | String，属性缺失/不可访问、非 running 或 native 读取错误；空串仅表示该次生命周期观测正常 |
| watcherError | String，原生属性观察线程的错误；失败后停止自动观察，直接采样或手动 resync 不清除此错误 |
| cameraWatcherError | String，CameraManager 关闭/崩溃恢复监听的注册错误 |
| persistenceError | String，所查询用户最近一次保存错误，后续成功保存才清除；不因节点写入成功而清除 |
| appliedConfirmed | boolean，当前恒 false：没有承诺 daemon 的加载 ACK |
| reason | String：starting、automatic、override、disabled、profile-disabled、screen-off、locked、user-locked、no-focused-app、error |
| error | String，最近一次运行错误；空串表示最近一次重算未报错 |
| eventError | String，通话或电源事件源最近一次采样错误；失败的输入按关闭处理 |
| scenarioId | int，原厂场景树仲裁结果；手动覆盖时为 -1 |
| cameraElement | int，录像输入元素：1=4K60、2=4K30/8K、99=关闭/其他规格 |
| cameraUserId | int，当前录像输入所属用户；无输入时为 -1 |
| offHook / lowTempCharge / reverseCharge | boolean，最近一次参与仲裁的事件输入 |
| selectableProfiles | int[]，`[-1,0,7,11,19,20,25]` |
| overrides | Bundle，包名键 → int 档位，仅所查询用户 |

调用异常时不显示“保存成功”。原子替换之后的目录同步也可能失败：此时持久化未确认，但新文件可能已经可见，异常不承诺回滚；重新读取配置以确认当前内容。后端会失效相关缓存并重新协调，保存错误与节点提交错误分别保留。

不要把 `requestedProfile` 或 `selectedProfile` 显示成“已生效”。修改成功后重读应用配置即可更新列表；前台状态无需不断轮询。

## 首版默认与手选

2026-09-16：用户确定默认均衡；模式只转换自动策略，手动覆盖优先，未分类始终 normal。
模式保存在同一个 `/data/system_de/<user>/dash-thermal.json`，增加可选布尔字段
`performanceMode`；旧版本 1 文件缺少该字段时按 false 读取，旧覆盖保留。
配置 schema 仍为 1，API 版本为 3；两者独立。
2026-09-17：覆盖恢复为固定表 ID，可选集合为 `{-1,0,7,11,19,20,25}`。
未发布的 schema 2 组 ID 方案已撤销，不保留迁移或兼容处理。手动覆盖不随模式切换。

| ID | 原厂名 | 建议手选名称 |
| --- | --- | --- |
| -1 | 自动（不写 -1 到节点） | 自动 |
| 0 | normal | 常规 |
| 7 | class0 | Class 0 |
| 11 | video | 视频 |
| 19 | mgame | 游戏 M |
| 20 | yuanshen | 游戏 Y |
| 25 | xingtie | 游戏 X |

自动映射（均衡 / 性能）：

| 分类 | 均衡 | 性能 |
| --- | ---: | ---: |
| game / pubg / game2 | 19 | 18 |
| yuanshen（含王者） | 19 | 20 |
| xingtie（含原神） | 19 | 25 |
| class0 | 7 | 57 |
| video | 11 | 61 |
| navigation | 10 | 10 |
| camera / evaluation / huanji / demo / arvr | 15 / 6 / 1 / 12 / 9 | 相同 |
| 未分类及其他 | 0 | 0 |

305 个包的分类随系统安装于 `/system_ext/etc/dash-power/thermal-packages.json`。
前端只消费 getAppPolicy，不复制分类或这张模式映射表。

自动结果可能是 57、61 等不在手选菜单中的功能表；「自动」副标题可以显示原厂名。用户显式覆盖优先。档位不是性能排行榜；X 同时改变 GPU、热插拔等策略，T/Y 的温度状态阈值不同。

## 适用范围

内置屏幕上取得焦点、请求可见（`isVisibleRequested`）的叶任务决定策略，分屏/PiP 按同一焦点规则；不等待转场提交可见性或首帧绘制。工作资料使用对应资料用户的覆盖，当前主用户关闭功能时整体暂停。手动应用覆盖直接决定目标，不参与任何事件场景；只有自动应用进入原厂 305 场景树。

已接入的事件输入为通话摘机、原厂相机 4K60/4K30/8K 录像、低温充电、反向供电和抖音前台。低温/反充直接读取内核状态并由 power_supply uevent 触发重算；通话读取系统聚合通话状态；相机兼容 APK 接收原厂定向到 `com.miui.powerkeeper` 的广播，接收器由相机自有 signature 权限保护。录像状态按用户隔离，并在 CameraManager 确认全部相机释放、用户停止或切换离开该用户时清除。屏灭、锁屏或无有效前台时维持 0 基线，但通话、低温和反充仍可按场景树产生非零结果。未接入 IEC、SpecialCScenario、播放高帧和 SPTM_2 的 Lineage 状态源。

305 的 63 条 `setting.xml` 规则按所有命中项取最大场景 ID，再执行 SwitchProcessor 映射。未分类应用保持 0，不因性能模式进入 50。原有 launch/fling 的省电过滤继续保留；没有频率调节或关闭温控接口。

服务失效没有租约兜底：system_server/thermal worker 不运行时，daemon 可能保留旧表。thermal 使用独立 worker，不再排在同步 boost HAL 调用后面；配置写盘仍与温控决策共用配置锁。不能宣称任意故障后限时恢复。

后端通过固定 init 属性的值与 serial 观测 daemon 生命周期，用专用线程阻塞于 bionic 属性等待通知，不依赖显式 Binder property poke，也不轮询节点。属性 revision 改变会失效旧请求缓存；即使两次都读到 running，也能识别其间的属性变化。观察线程若遇 native 读取/等待错误会停止并保留 watcherError；手动 resync 仍直接采样与提交，但恢复自动观察需要重启后端。属性不存在/不可访问或非 running 时不把旧请求当作当前 daemon 的提交。启动和恢复会重算当前策略；每个新 running 生命周期在首次提交外，仅于 1、3、10 秒安排有限重提交。每次重新读取当前前台与开关，不重放旧应用或旧档位；普通应用事件不会重新补充重试预算。

running/serial 不是 inotify 监听就绪或加载 ACK。监听晚于重试窗口建立、或 daemon 已缓存 ID 但加载失败时，仍不能保证交付；不会来回切换其他表规避 daemon 的去重。`appliedConfirmed` 保持 false。调试 shell 的 resync 可主动开始新一轮有限重提交，生产加载与 Enforcing 真机行为需独立验收。

## Battery 开关与快捷磁贴

两个入口都在现有 DashThermal APK 中：

- `PerformanceModeProvider` 通过 SettingsLib 的 EntriesProvider/ProviderSwitch 协议，
  在 Battery 页面注入原生开关；不是复制 Settings 页面，也不修改上游 Settings。
- `PerformanceModeTileService` 是标准 QS TileService，在磁贴编辑列表可添加；
  仅提供 24dp 单色矢量图标，状态色和容器由 SystemUI 处理，不硬编码参考图的青色。
- `PerformanceModeClient` 是这两个入口共用的同步 Binder 访问辅助类，不持有模式缓存。
  Provider 在 Binder 线程调用，磁贴在专用 worker 调用；原应用页面继续使用已有异步客户端。
- 配置保存后，后端向 AIDL 常量 PERFORMANCE_STATE_URI 发 ContentResolver 通知；
  Settings 现有动态开关观察器、磁贴的 ContentObserver 都重新读后端，不轮询，不各存一份状态。
- 总温控关闭时模式仍保留，磁贴显示不可用；开启总温控后恢复按已保存模式选表。
  性能入口不擅自重开总温控。系统省电对原 launch/fling 的过滤不变，不自动覆盖模式。

API 继续只有一个 dash_thermal 服务、一份共享 AIDL、一份配置文件和后端唯一映射。
当前 Bundle 字段仍靠字符串约定；风险在于未来静默改字段或在 UI 复制策略。
本轮用版本 3 和末尾追加方法保持旧 transaction 编号。

2026-09-16：事件场景源码已完成静态核对；Java 规则表与 305 `setting.xml` 的 63 条规则逐项一致。新增 host 回归源码尚未执行。按用户要求未编译、未安装、未上机验证。统一构建上机时检查两入口同步、模式重启持久化、手动覆盖优先、自动基础/per 切换，以及各事件进入和退出后的恢复。

## 调试

userdebug/eng 的 root/shell 可使用：

```sh
adb shell cmd dash_thermal status
adb shell cmd dash_thermal get 0 com.tencent.tmgp.sgame
adb shell cmd dash_thermal set 0 com.tencent.tmgp.sgame 19
adb shell cmd dash_thermal set 0 com.tencent.tmgp.sgame -1
adb shell cmd dash_thermal enable 0 false
adb shell cmd dash_thermal enable 0 true
adb shell cmd dash_thermal resync
```

resync 只重新提交当前策略，不改变已保存配置。前端使用 Binder，不执行 shell 命令，也不申请 root。
