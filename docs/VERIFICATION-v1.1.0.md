# Android v1.1.0 验证记录

日期：2026-10-18（Asia/Shanghai）。工程：`E:\Project\translation\android`。版本名称 1.1.0 / versionCode 2，包名 `com.cyh.translator`，最低 Android 10（API 29），目标 Android 15（API 35）。本记录对应评审修复后的最终发行构建。

## 已完成

| 检查 | 结果 |
| --- | --- |
| Release 构建、R8 优化、签名 | 成功（`scripts/build.ps1`，BUILD SUCCESSFUL；含 `minifyReleaseWithR8`、`lintVitalRelease`、签名与打包），生成可侧载 APK |
| JVM 单元/联调测试 | 28/28 通过（CaptionAssemblerTest 10、ProtocolTest 8、SubtitlePresentationTest 9、RealtimeChannelTest 1） |
| Android 15 / API 35 模拟器仪器测试 | 7/7 通过，27.715 秒（AppSmokeTest 6、OverlaySmokeTest 1） |
| Android Lint | 0 errors，3 warnings（均为 `androidx.test:*` 存在更新版本的 GradleDependency 提示） |
| APK 签名校验 | apksigner verify 通过；APK Signature Scheme v2 有效（v1/v3/v3.1/v4 未使用）；证书 DN `CN=Simultaneous Translator Android`，证书 SHA-256 `9cfe922fff953dbdfded7ebbceeab2640f82d359efb914af37d8c62d0dbf5756`（RSA 3072） |
| 发行 APK 安装启动 | 在 API 35 模拟器 emulator-5580（sdk_gphone64_x86_64）安装发行 APK 成功并启动，版本 1.1.0（versionCode 2），状态栏与导航正常；实测调试签名包与发行签名包不能互相覆盖，换签名安装需先卸载 |
| 界面与截图检查 | 同传、设置、历史空状态三页截图均来自该发行 APK；悬浮字幕真实显示在 DeskClock 闹钟界面之上（原文+译文+控制条）；横屏同样覆盖其他应用且不越界 |
| Windows 隔离 | `windows/` Git 工作区保持干净，HEAD `0da6dbe` 未变 |

## 本轮变更（v1.1.0）

- 新增跨应用悬浮字幕：由前台服务持有 `TYPE_APPLICATION_OVERLAY` 窗口（`SYSTEM_ALERT_WINDOW`），同传时显示在其他应用上方；支持拖动标题移动、A− / A+ 调整译文字号（16–30）、收起/展开、隐藏后从通知栏「显示字幕」恢复；设置可开关「悬浮窗同时显示原文」；位置按相对坐标记忆并适配横屏/旋转；未授权时引导开启，用户可拒绝并只保留应用内字幕。
- 手机端移除语音相关功能：删除译文语音播放（AudioTrack）、语音转语音与音色复刻；实时接口固定 `output_modalities=["text"]`，`response.audio.delta` 被忽略；SettingsStore 忽略并在保存时清理旧语音偏好。界面与文档不再提供语音播报选项。

评审后额外修复：

1. 悬浮窗在收到第一段真实原文/译文前只显示状态行与通道标题，不再把应用内等待占位文案（「原文会显示在这里」「正在聆听…」）覆盖到其他应用；应用内界面仍保留占位提示。
2. 悬浮窗瞬时失败（旋转、锁屏、系统对话框等）每 1.5 秒自动重试，最多 5 次；仍失败才在同传页提示手动重新开启。
3. 窗口被系统摘除时连续两次检测后自动移除并重建；移除窗口改为无条件 `removeViewImmediate`，避免旋转后残留，移除失败写入 Log。
4. 测试补充 A− 字号用例与 `response.audio.delta` 丢弃断言；README 补通知权限边界，PROTOCOL 修正前台服务类型的 API 级别描述。

截图说明：`overlay-landscape.png` 中原文被隐藏，是测试中关闭「显示原文」开关的预期结果，仅显示译文。

## 测试覆盖

JVM 测试覆盖：

- 纯文字协议：`session.update` 的 `output_modalities` 固定为 `["text"]`，两个通道都不发送 voice/音色复刻字段；`session.updated` 返回非纯 text 模态时拒绝；WSS 地址与查询参数校验；原文字幕语言不再受语音能力限制。
- 字幕配对：增量文本、先原文/先译文、重复完成事件、FIFO 配对、重连后清理旧字幕、未配对译文保留、积压上限。
- 字幕呈现与悬浮位置：实时与最终字幕一致、先到译文可见、通道互不串字幕、新原文不复用旧译文；等待占位文案不被判定为真实字幕内容（`hasContent`）；相对坐标在旋转和窗口尺寸变化后仍可见、越界坐标收敛、非有限坐标安全兜底。
- 实时通道：本地 TLS WebSocket 模拟服务验证鉴权请求、收到 session.updated 前不发送音频、PCM Base64 内容、静音丢弃音频、字幕事件与 session.finish；断言 `response.audio.delta` 不会变成字幕或错误。

模拟器仪器测试覆盖：

- AppSmokeTest 6 项：底部导航与 Activity 重建（并断言设置页没有「播放译文语音」选项）、Keystore 加密与重新读取、语言能力表与桌面版一致、旧语音设置迁移（仍为纯 text、无 voice 字段、旧 `*.audio`/`*.clone` 被清除、密钥保留）、AudioRecord 采集真实 16 kHz PCM 帧、前台服务启动/静音/停止。
- OverlaySmokeTest 1 项：在真实 DeskClock 闹钟界面打开时，通过无障碍窗口断言悬浮字幕的中文译文可见；验证 A+ 与 A− 字号、收起/展开、拖动后保存相对位置、「显示原文」开关、隐藏后从通知「显示字幕」恢复，以及横屏后窗口不越界。测试使用本地不可达 WSS（`wss://127.0.0.1:9/realtime`）与合成协议事件驱动字幕，不调用外部计费模型，所以截图状态行显示「连接中断」。

以上测试不验证真实云端翻译内容、时延或实体手机上的悬浮窗表现。

## 仍需实际使用验证

- 未使用真实 API Key 调用云端模型，因此没有验证当前账号的模型权限、端到端翻译内容与时延。
- 悬浮字幕只在 API 35 模拟器上验证；尚未在实体手机和各厂商系统上验证，部分系统界面与支付、DRM 等安全页面可能隐藏悬浮窗，各厂商的悬浮窗与省电策略也可能影响显示。
- 尚未在实体手机上检查 Android 10–14 兼容性、刘海/挖孔/折叠屏等异形屏适配。
- 锁屏长时间运行、进程被系统回收、厂商省电策略下的前台服务表现仍未验证。
- 系统声音采集代码已编译并按安卓权限/投影生命周期实现，但尚未在实体手机、媒体应用或会议场景中验证；通话与 DRM 音频可能始终无声。
- Android 13 及以上用户拒绝通知权限后的长期运行与恢复路径只在模拟器覆盖了基本功能，未在实体机验证。
- 尚未验证真实系统文件提供器的全部导出场景；导出使用 Android Storage Access Framework（SAF）。
- 尚未在实体手机上验证蓝牙/有线耳机路由。

## 原始结果

原始结果在 [verification/v1.1.0/](verification/v1.1.0/)：`build.log`、`unit-lint-build.log`、`instrumentation.log`（`OK (7 tests)` / `Time: 27.715`）、`overlay-test.log`、4 个 `TEST-*.xml`、`lint-results.txt`、`apk-signature.txt`、`apk-badging.txt`、`apk-sha256.txt`、`apk-size.txt`、`install-launch.txt`。截图在 [screenshots/v1.1.0/](screenshots/v1.1.0/)：`home.png`（同传页，含悬浮字幕卡片）、`settings.png`（设置页，无语音/音色选项）、`history.png`（历史空状态）、`overlay-over-clock.png`（DeskClock 闹钟界面之上的悬浮字幕）、`overlay-landscape.png`（横屏覆盖其他应用且不越界）。v1.0.0 记录见 [VERIFICATION-v1.0.0.md](VERIFICATION-v1.0.0.md)。

## 复现

```powershell
cd E:\Project\translation\android
.\scripts\build.ps1
# 仅在已启动的、可丢弃的测试模拟器上运行：
.\scripts\test-emulator.ps1 -Serial emulator-5580
```

模拟器已安装发行签名 APK 时，调试签名 APK 无法覆盖安装；请另建测试模拟器或在可丢弃模拟器中卸载发行安装后重测。不要对个人手机运行会写入测试配置的仪器测试。

## 发行文件

发行文件：`releases/v1.1.0/simultaneous-translator-android-1.1.0-release.apk`

- 大小：391,231 字节
- SHA-256：`8ec839950ff4a320f910a81ca07ad73928b8b2ac0b79b35bcda97cd80487fade`（与同目录 `SHA256SUMS.txt` 一致）
- 签名：APK Signature Scheme v2；证书 DN `CN=Simultaneous Translator Android`，证书 SHA-256 `9cfe922fff953dbdfded7ebbceeab2640f82d359efb914af37d8c62d0dbf5756`（与 v1.0.0 相同，可覆盖升级）；RSA 3072
- 包名：`com.cyh.translator`；最低 API 29，目标 API 35；版本名称 1.1.0 / versionCode 2
- 覆盖安装必须使用同一发布证书；与调试签名包不能互相覆盖，换签名安装需先卸载
