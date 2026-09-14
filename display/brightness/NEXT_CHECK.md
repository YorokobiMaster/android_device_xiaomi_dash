# 灰度回调修复：下次检查

2026-09-14。本次代码已审查，但最终修改没有完成编译或上机验证。用户正在合并其他小修，要求停止定向构建；本轮构建日志 `tmp/disposable-anytime/brightness-gray-final-build.log` 记录 kati exit 143，不是编译通过，也不是已确认源码编译错误。

## 本次修改

- 每次新 HAL 连接：注册 ID-0 callback，发送 `setFeature(0, 13, 1, 255)`，然后按自动亮度状态发送 mode 56 启用。
- 自动/手动切换时更新采样状态；灰度回调检查 histogram enabled 和 generation，拒绝跨启停边界排队的旧回调。
- 普通异常断连不发送显示 OFF。实际非交互转换才通知 OFF。
- `stop()` 仅失效 session、取消任务、停止采样、注销连接和退出 worker，不把策略销毁伪装成实际熄屏。
- dump 增加 callbackRegistered、displayStateSynced、histogramEnabled、grayCallbackCount、lastGrayCallbackUptimeMillis。
- HBM/BCBC 放行逻辑未修改。

## 下次合并构建后

从源码根目录使用现有 bp4a 环境和 out；至少确认 `dash-brightness` 编译成功。部署后记录实际 build date/版本，不能拿旧产物或手动探针结果替代新代码验证。

1. **亮屏＋自动亮度**：检查 dumpsys display 中 DashBrightnessPolicy。callbackRegistered/displayStateSynced/histogramEnabled 应为 true；收到有效内容样本后 gray 在 0..255，计数增加、时间戳更新。静态画面不要求每秒都有回调。
2. **熄屏再亮屏**：确认 mode 56 停止与新连接按 13=ON → 56=enable 重放；无需手动 Binder 探针即可再次取得灰度。
3. **自动→手动→自动**：关闭采样后 gray=-1，旧排队回调不能恢复它；重开后接受新样本。
4. **HAL 重连**：仅在获准重启 HAL 的测试中执行。确认重注册、重放启用顺序、恢复灰度。不要把 service 存在等同回调有效。
5. **异常/销毁**：用 fake HAL/生命周期测试模拟亮屏时启用失败及 policy.stop()；不得发送 mode 13=OFF；检查过期回调不再 publish、poll 不再继续。实际正常熄屏仍应通知 OFF。
6. 检查 Enforcing 下相关 AVC、DisplayFeature setup/cleanup 错误及 system_server 崩溃；保留完整命令、时间戳、dump/log 摘录。

既有手动探针记录位于源码根目录 `tmp/disposable-anytime/brightness-gray-lifecycle-20260914/probe-summary.md`：两种生命周期中手动重放能使 gray=-1→173。它只证明 vendor 协议，不证明本次 Java 修改已经自动执行成功。此前验收：`docs/dash/brightness-runtime-acceptance-20260914.md`。
