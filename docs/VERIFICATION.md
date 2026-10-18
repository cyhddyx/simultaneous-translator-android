# Android v1.1.1 验证记录

日期：2026-10-18（Asia/Shanghai）。工程：`E:\Project\translation\android`。版本名称 1.1.1 / versionCode 3，包名 `com.cyh.translator`，最低 Android 10（API 29），目标 Android 15（API 35）。本记录对应「真机反馈驱动的纯译文滚动字幕」最终发行构建（含评审后修复）。

## 已完成

| 检查 | 结果 |
| --- | --- |
| Release 构建、R8 优化、签名 | 成功（`scripts/build.ps1`，BUILD SUCCESSFUL；含 `minifyReleaseWithR8`、`lintVitalRelease`、签名与打包），生成可侧载 APK |
| JVM 单元/联调测试 | 35/35 通过（CaptionAssemblerTest 10、ProtocolTest 8、SubtitlePresentationTest 16、RealtimeChannelTest 1） |
| Android 15 / API 35 模拟器仪器测试 | 7/7 通过，30.932 秒（AppSmokeTest 6、OverlaySmokeTest 1） |
| Android Lint | 0 errors，3 warnings（均为 `androidx.test:*` 存在更新版本的 GradleDependency 提示） |
| APK 签名校验 | apksigner verify 通过；APK Signature Scheme v2 有效（v1/v3/v3.1/v4 未使用）；证书 DN `CN=Simultaneous Translator Android`，证书 SHA-256 `9cfe922fff953dbdfded7ebbceeab2640f82d359efb914af37d8c62d0dbf5756`（RSA 3072） |
| 发行 APK 安装启动 | 在 API 35 模拟器 emulator-5580（sdk_gphone64_x86_64）安装发行 APK 成功并启动，版本 1.1.1（versionCode 3），状态栏与导航正常；实测调试签名包与发行签名包不能互相覆盖，换签名安装需先卸载 |
| APK 冷启动（force-stop 后） | `am start -W`：`Status: ok` / `LaunchState: COLD` / `TotalTime: 1945`（`WaitTime: 2005`），记录见 `verification/v1.1.1/install-launch.txt` |
| 界面与截图检查 | 同传、设置、历史等 8 张截图均来自该发行 APK；悬浮字幕在 DeskClock 闹钟界面之上只显示译文；滚动框保持约 3 行、空内容时窗口高度小于屏幕 1/6、长句时小于屏幕 1/3；横屏覆盖其他应用且不越界 |
| Windows 隔离 | `windows/` Git 工作区保持干净，HEAD `0da6dbe` 未变 |

## 本轮变更（v1.1.1，真机反馈驱动）

- v1.1.0 已在实体手机 + 真实 API Key 下可用（悬浮字幕可覆盖 bilibili 直播），但反馈悬浮窗同时显示原文和译文、长句会占掉半屏以上。v1.1.1 改为参考 YouTube / ViiTor 的纯译文滚动字幕。
- 悬浮窗只显示译文：最多 3 行——已完成译文（绿色加粗）在前，实时译文（浅色非加粗）占最后一行，合计取最新 3 行；新译文追加到底部，旧行向上滚出。行数超出时字幕框内可滚动、滚动条隐藏，并自动保持最新一行可见；字幕框不超过可用高度 1/3，标题与内边距另计，默认字号下整窗约屏幕 1/6。
- 双向模式两个通道的译文同时显示，前缀为「收听｜」「发言｜」；标题第一行「同传字幕」，第二行显示状态（正在翻译 / 正在连接 / 重连中 / 已静音）。
- 原文不再进入悬浮窗，只保留在应用内同传页、历史会话记录和导出文本中；设置页移除「悬浮窗同时显示原文」开关，旧版本保存的该偏好不再读取。
- 保留拖动标题移动、A− / A+ 字号（16–30）、收起/展开、隐藏后从通知「显示字幕」恢复、相对坐标位置记忆、横屏适配与权限引导。
- v1.1.0 的评审修复继续保留：瞬时失败每 1.5 秒自动重试（最多 5 次）、窗口被系统摘除两轮检测后自动重建、无条件 `removeViewImmediate`、`response.audio.delta` 丢弃断言等。
- 截图说明：`overlay-rolling.png` 注入 4 条已完成译文 + 1 条实时译文，只显示最新 3 行且无滚动条；`overlay-long.png` 注入 100+ 字长句，窗口仍被限制在约 3 行框内、远小于半屏；`settings-subtitles.png` 滚动到设置页「字幕显示」区，确认没有「悬浮窗同时显示原文」开关（`settings.png` 为设置页顶部，不再用于该证明）。截图标题显示「重连中」是仪器测试使用本地不可达 WSS（`wss://127.0.0.1:9/realtime`）与合成协议事件所致，不调用云端计费模型。

### 评审后修复（最终构建）

1. 标题状态映射修正：提取 `SubtitlePresentation.overlayState`；网络退避后的「正在重连 · 第 N 次」现在显示「重连中」，不再误显示「正在翻译」。
2. 收起状态下窗口被系统摘除并重建时，按钮文案与描述按 collapsed 状态初始化，不再出现「收起」但内容已隐藏。
3. `rolling()` 对「已完成译文与尚未清掉的同句实时译文」去重，避免 finalize 瞬间重复显示一行。
4. 测试补强：空内容窗口高度断言、100+ 字长句仍限框、服务重连清理后重新注入实时行、A+/A− 保留；新增标题状态映射、双向前缀、finalize 去重单测。
5. 长句一次性到达时滚动修正：字幕框改为在布局完成后自动滚动到最新一行（`LimitedScrollView.onLayout`），不再因为先滚动后布局而停在长句开头；`overlay-long.png` 现在显示句尾（最新行）且底部完整可见，不再出现半行裁切。补测 3 次单跑 OverlaySmokeTest 均通过（19.497s / 12.649s / 12.052s）。

## 测试覆盖

JVM 测试覆盖：

- 纯文字协议：`session.update` 的 `output_modalities` 固定为 `["text"]`，两个通道都不发送 voice/音色复刻字段；`session.updated` 返回非纯 text 模态时拒绝；WSS 地址与查询参数校验；原文字幕语言不再受语音能力限制。
- 字幕配对：增量文本、先原文/先译文、重复完成事件、FIFO 配对、重连后清理旧字幕、未配对译文保留、积压上限。
- 应用内字幕文本：实时与最终字幕一致、先到译文可见、通道互不串字幕、新原文不复用旧译文。
- 纯译文滚动与标题状态：只取译文、忽略其他通道与只有原文的片段；最多取最新 3 行（完成译文在前、实时译文占最后一行）；同句完成译文与未清掉实时译文去重；双向前缀仅在双通道时出现；「正在重连 · 第 N 次」映射为「重连中」；无内容时不回退占位文案；相对坐标在旋转和窗口尺寸变化后仍可见、越界坐标收敛、非有限坐标安全兜底。
- 实时通道：本地 TLS WebSocket 模拟服务验证鉴权请求、收到 session.updated 前不发送音频、PCM Base64 内容、静音丢弃音频、字幕事件与 session.finish；断言 `response.audio.delta` 不会变成字幕或错误。

模拟器仪器测试覆盖：

- AppSmokeTest 6 项：底部导航与 Activity 重建（并断言设置页没有「播放译文语音」和「悬浮窗同时显示原文」选项）、Keystore 加密与重新读取、语言能力表与桌面版一致、旧语音设置迁移（仍为纯 text、无 voice 字段、旧 `*.audio`/`*.clone` 被清除、密钥保留）、AudioRecord 采集真实 16 kHz PCM 帧、前台服务启动/静音/停止。
- OverlaySmokeTest 1 项：在真实 DeskClock 闹钟界面打开时，通过无障碍窗口断言悬浮字幕的中文译文可见，且英文原文不出现在悬浮窗；空内容时窗口高度小于屏幕 1/6；注入 4 条完成译文 + 1 条实时译文后，最旧译文滚出、窗口高度小于屏幕 1/4；注入 100+ 字长句后窗口仍小于屏幕 1/3；覆盖 A+ / A− 字号、收起/展开、拖动后保存相对位置、隐藏后从通知「显示字幕」恢复、横屏后窗口不越界；服务重连清理实时行后会重新注入并断言。

以上测试不验证真实云端翻译内容、时延或实体手机上的悬浮窗表现。

## 仍需实际使用验证

- v1.1.0 已由用户在实体手机 + 真实 API Key 下验证悬浮字幕可覆盖 bilibili 直播；v1.1.1 的纯译文滚动字幕尚未在实体手机复验。
- 未在自动化中调用真实云端模型，因此当前账号的模型权限、端到端翻译内容与时延仍需真实 API Key 验证。
- 悬浮字幕只在 API 35 模拟器上验证；尚未在实体手机和各厂商系统上验证，部分系统界面与支付、DRM 等安全页面可能隐藏悬浮窗，各厂商的悬浮窗与省电策略也可能影响显示。
- 尚未在实体手机上检查 Android 10–14 兼容性、刘海/挖孔/折叠屏等异形屏适配。
- 锁屏长时间运行、进程被系统回收、厂商省电策略下的前台服务表现仍未验证。
- 系统声音采集代码已编译并按安卓权限/投影生命周期实现，但尚未在实体手机、媒体应用或会议场景中验证；通话与 DRM 音频可能始终无声。
- Android 13 及以上用户拒绝通知权限后的长期运行与恢复路径只在模拟器覆盖了基本功能，未在实体机验证。
- 尚未验证真实系统文件提供器的全部导出场景；导出使用 Android Storage Access Framework（SAF）。
- 尚未在实体手机上验证蓝牙/有线耳机路由。

## 原始结果

原始结果在 [verification/v1.1.1/](verification/v1.1.1/)：`build.log`、`unit-lint-build.log`、`instrumentation.log`（`OK (7 tests)` / `Time: 30.932`）、`overlay-test.log`、4 个 `TEST-*.xml`、`lint-results.txt`、`apk-signature.txt`、`apk-badging.txt`、`apk-sha256.txt`、`apk-size.txt`、`install-launch.txt`（含安装、版本与冷启动记录）。

截图共 8 张，位于 [screenshots/v1.1.1/](screenshots/v1.1.1/)：

- `home.png`：同传页。
- `settings.png`：设置页顶部。
- `settings-subtitles.png`：滚动到「字幕显示」区的设置页，确认无「悬浮窗同时显示原文」开关（AppSmokeTest 同时断言该文案不存在）。
- `history.png`：历史空状态。
- `overlay-over-clock.png`：DeskClock 闹钟界面之上的纯译文悬浮字幕。
- `overlay-rolling.png`：多条译文滚动，最多显示最新 3 行、无滚动条。
- `overlay-long.png`：100+ 字长句仍被限制在约 3 行框内、窗口远小于半屏，并自动滚动到最新一行（句尾完整可见）。
- `overlay-landscape.png`：横屏覆盖其他应用且不越界。

v1.1.0 记录见 [VERIFICATION-v1.1.0.md](VERIFICATION-v1.1.0.md)，v1.0.0 记录见 [VERIFICATION-v1.0.0.md](VERIFICATION-v1.0.0.md)。

## 复现

```powershell
cd E:\Project\translation\android
.\scripts\build.ps1
# 仅在已启动的、可丢弃的测试模拟器上运行：
.\scripts\test-emulator.ps1 -Serial emulator-5580
```

模拟器已安装发行签名 APK 时，调试签名 APK 无法覆盖安装；请另建测试模拟器或在可丢弃模拟器中卸载发行安装后重测。不要对个人手机运行会写入测试配置的仪器测试。

## 发行文件

发行文件：`releases/v1.1.1/simultaneous-translator-android-1.1.1-release.apk`

- 大小：391,619 字节
- SHA-256：`9260d5983f088c17929fad896a74515ea92276e16eadd704361b4a3b1645ec23`（与同目录 `SHA256SUMS.txt` 一致）
- 签名：APK Signature Scheme v2；证书 DN `CN=Simultaneous Translator Android`，证书 SHA-256 `9cfe922fff953dbdfded7ebbceeab2640f82d359efb914af37d8c62d0dbf5756`（与 v1.0.0、v1.1.0 相同，可覆盖升级）；RSA 3072
- 包名：`com.cyh.translator`；最低 API 29，目标 API 35；版本名称 1.1.1 / versionCode 3
- 覆盖安装必须使用同一发布证书；与调试签名包不能互相覆盖，换签名安装需先卸载
