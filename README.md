# 同传翻译 · Android

独立原生 Kotlin 安卓应用。代码、构建工具、文档和安装包都位于本目录。最低 Android 10（API 29），目标 Android 15（API 35）。沿用 Windows 版的实时同传接口、60 种语言能力表和纸白/浅绿/紫色配色；手机端固定为纯文字字幕，不播放译文语音。v1.1.1 起悬浮字幕改为纯译文滚动显示（像视频字幕一样），原文在应用内和历史记录中查看。

GitHub 仓库：<https://github.com/cyhddyx/simultaneous-translator-android>（源码与文档在 `main`；安装包在 Releases 页面）。

## 安装使用

安装包：`releases/v1.1.1/simultaneous-translator-android-1.1.1-release.apk`（versionName 1.1.1 / versionCode 3），同目录提供 `SHA256SUMS.txt`。这是使用持久本地发布证书签名的 APK，可以直接复制到手机安装；覆盖升级必须使用同一证书，本地设置和历史会保留。

1. 安装并打开应用，在「设置」填写与接口对应的 API Key。
2. 默认地址为 `wss://maas.qianwenaiapi.com/api-ws/v1/realtime`，模型为 `qwen3.8-livetranslate-flash-realtime`，与桌面版一致。
3. 在「使用场景」选择面对面（麦克风）、收听（手机声音）或双向，并在「设置」选择收听/发言的目标语言后保存。手机端只输出文字字幕，没有译文播报、音色或语音转语音选项。
4. 在「同传」点击开始并授予录音权限。悬浮字幕默认开启，未授权时会引导开启系统「显示在其他应用上层」权限；也可以拒绝，拒绝后只用应用内字幕，之后可在同传页的「悬浮字幕」按钮重新开启。
5. 开始后切换其他应用，悬浮字幕会显示在最上层：只显示译文，最多约 3 行，新译文出现在底部、旧译文向上滚动，正在翻译的实时译文以浅色显示在最后一行；双向模式带「收听｜」「发言｜」前缀。可拖动标题移动、A− / A+ 调整译文字号、收起或展开；点「隐藏」后可从通知栏「显示字幕」恢复（Android 13 及以上若未授予通知权限，可用同传页的「悬浮字幕」按钮恢复）。原文不在悬浮窗显示，可在应用内同传页和「历史」的会话记录中查看。
6. 通过常驻通知停止同传、静音麦克风；回到「历史」查看、导出字幕。

首次安装默认：麦克风输入、自动识别源语言、译文为英语、只显示文字字幕、悬浮字幕默认开启（纯译文滚动，最多约 3 行）。没有内置密钥、演示字幕或离线模型。音频经加密连接发送到所配置的模型服务，费用由服务商收取；本应用不保存录音。

## 功能

- 麦克风实时同传、手机媒体声音捕获、双向独立会话，所有场景都只输出原文/译文字幕。
- 自动识别源语言，60 种文字目标语言。
- 应用内原文/译文流式字幕，原文始终可在同传页回看。
- 跨应用悬浮字幕：`SYSTEM_ALERT_WINDOW` + `TYPE_APPLICATION_OVERLAY`，由前台服务持有。只显示译文，最多约 3 行：已完成译文向上滚动、最新译文在底部，正在翻译的实时译文以浅色非加粗显示在最后一行，完成后变为绿色加粗；双向模式加「收听｜」「发言｜」前缀。可拖动标题移动、A− / A+ 调整译文字号（16–30）、收起/展开，隐藏后可从通知恢复；窗口高度按字号重算，远小于半屏；位置按相对坐标记忆，横屏和旋转后仍保持在可见区域；收到第一段译文前只留标题，不显示占位文案。
- 原文只出现在应用内同传页、历史和导出文本中，悬浮窗不显示原文；设置页已移除「悬浮窗同时显示原文」开关，旧版本保存的该偏好不再读取。
- 网络异常自动退避重连，静默保活，静音时停止发送麦克风帧。
- 带停止、隐藏/显示字幕操作的前台服务通知；发言模式的通知另有静音按钮，后台继续翻译。
- Android Keystore AES-GCM 加密保存 API Key，禁用系统备份。
- SQLite 本地保存最近 100 次会话，通过系统文件选择器导出 UTF-8 文本，清空前确认。
- 纯字幕版：不播放译文语音，没有音色复刻或语音转语音；旧版本保存的语音偏好会被忽略并在保存设置时清理。

## 安卓平台边界

- 悬浮字幕依赖系统「显示在其他应用上层」权限；开始同传时会引导开启。部分系统界面和支付、DRM 等安全页面可能会隐藏悬浮窗；权限被撤销时悬浮字幕停止显示并在同传页提示，重新授权后可再次开启。悬浮窗只显示译文，需要对照原文时请回到应用内或历史记录查看。
- 手机声音使用 MediaProjection + AudioPlaybackCapture，仅能捕获允许录制的媒体/游戏声音；通话、DRM 或目标应用禁用捕获时可能无声。应用排除自身 UID。
- 安卓无法提供 Windows VB-CABLE 虚拟麦克风，不能把译文注入微信、会议或游戏应用的麦克风。
- 双向模式同时采集手机声音和麦克风；其他应用独占麦克风时可能无法同时录音，外放时麦克风可能再次拾取扬声器声音，建议佩戴耳机。
- 不包含桌面托盘、全局快捷键或自动更新。
- 系统杀进程、撤销录音/采集授权后，需要回到应用重新开始；不会自行恢复录音或悬浮字幕。锁屏和省电策略仍受设备厂商影响。
- 真实服务效果需要有效 API Key 和手机实测；悬浮字幕也需在实体手机与各厂商系统上确认，尤其是安全页面隐藏悬浮窗的情况。自动验证范围见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。

## 构建

Android Studio 可直接打开本目录。需要 JDK 17 或 21、Android SDK 35。工程使用 Gradle 8.7 Wrapper、Android Gradle Plugin 8.6.1、Kotlin 2.0.21、OkHttp 4.12.0。

```powershell
cd E:\Project\translation\android
# 首次缺少 SDK 时执行，按提示阅读并接受 Google SDK 许可：
.\scripts\setup-sdk.ps1
# 构建签名发行 APK，同时运行测试和 Lint：
.\scripts\build.ps1
# 调试包：
.\scripts\build.ps1 -Debug
# 直接执行检查：
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

脚本优先使用 JAVA_HOME，其次从 PATH 查找 Java。SDK 路径保存到本机 `local.properties`。本次本地 SDK 位于 `.tooling/sdk`，不要求更改系统环境变量。

首次发行构建生成 `.signing/translator-release.p12` 和 `signing.properties`。请私下备份这两个文件，后续覆盖安装必须使用同一证书；不要提交 Git 或随 APK 分发。脚本会复用已有签名文件，不会覆盖旧证书。没有签名配置时直接执行 `assembleRelease` 只生成未签名包。

## 结构

- `app/src/main/java/com/cyh/translator/`：原生界面、前台服务、模型协议、音频采集、密钥和历史实现。
- `app/src/main/java/com/cyh/translator/SubtitleOverlay.kt`：跨应用纯译文滚动字幕窗口（`TYPE_APPLICATION_OVERLAY`），由前台服务持有并随服务销毁清理。
- `app/src/main/java/com/cyh/translator/SubtitlePresentation.kt`：应用内字幕文本、悬浮窗纯译文滚动（最近 3 行 + 实时译文）与相对位置的计算和恢复。
- `app/src/main/assets/translation_languages.json`：从 Windows 版复制的语言能力表，独立打包。
- `app/src/test/`：协议、字幕配对、实时通道联调、纯译文滚动与悬浮位置几何测试。
- `app/src/androidTest/`：导航与界面重建、Keystore、语言表、旧语音设置迁移、麦克风采集、前台服务，以及 `OverlaySmokeTest.kt` 在 DeskClock 上方验证纯译文滚动字幕（不含原文、高度受限）。
- `scripts/`：SDK 安装、签名初始化、构建脚本。
- `docs/`：协议、验证记录和截图。
- `releases/v1.1.1/`：APK 和 SHA-256 校验文件。
- `.tooling/`：本地 SDK、模拟器及构建辅助资料，不提交 Git。

安卓构建不依赖 Windows 工程，也没有修改 Windows 源码和发布链路。后续更新语言能力表时需同步两平台数据。

## GitHub 推送与发布

```powershell
cd E:\Project\translation\android
git add -A
git commit -m "说明这次改了什么"
git push                      # main 已跟踪 origin/main
```

- 仓库：<https://github.com/cyhddyx/simultaneous-translator-android>。当前 `main` 已推送 v1.1.1 源码与文档；v1.1.1 的 APK、`SHA256SUMS.txt`、发行说明已作为 **GitHub Release v1.1.1** 的附件发布。
- `git push` 只推源码和文档，不会自动上传安装包；每次发版后到 GitHub Releases 手动（或用 API/`gh release create`）上传 `releases/v<版本>/` 下的 APK 与 `SHA256SUMS.txt`。
- `.tooling/`（本地 SDK/模拟器，约 10 GB）、`app/build/`、`.gradle/`、`.kotlin/`、`local.properties` 都已被 `.gitignore` 排除。
- 签名材料 `.signing/translator-release.p12` 和 `signing.properties` 已被 `.gitignore` 排除，**不要提交**；请离线备份，后续覆盖升级必须用同一证书。
- `.github/` 下目前没有 CI；需要的话可以再加 GitHub Actions 自动跑 `:app:testDebugUnitTest` 与 `:app:lintDebug`。
