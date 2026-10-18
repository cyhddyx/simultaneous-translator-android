# Android v1.0.0 验证记录

日期：2026-10-18（Asia/Shanghai）。工程：`E:\Project\translation\android`。

## 已完成

| 检查 | 结果 |
| --- | --- |
| Release 构建、R8 优化、签名 | 成功，生成可侧载 APK |
| JVM 单元/联调测试 | 19/19 通过 |
| Android 15 / API 35 模拟器仪器测试 | 5/5 通过 |
| Android Lint | 0 errors；3 条提示仅为测试依赖存在更新版本 |
| APK 签名校验 | apksigner verify 通过，APK Signature Scheme v2 |
| 发行 APK 安装启动 | 在 API 35 x86_64 模拟器成功安装、打开 |
| 界面检查 | 同传、设置、空历史界面已截图检查，文字/按钮可读，无覆盖导航或状态栏 |
| Windows 隔离 | Windows Git 工作区保持干净；语言表 SHA-256 与 Windows 版完全一致 |

JVM 测试覆盖：文字/语音模式配置、音色确认与频率、WSS 地址校验、语言能力校验、增量文本、先原文/先译文、重复完成事件、FIFO 配对、重连后清理旧字幕、积压上限。TLS WebSocket 联调使用本地证书和模拟服务，验证鉴权请求、收到 session.updated 前不发送音频、PCM Base64 内容、静音丢弃音频、字幕事件和 session.finish。

模拟器测试覆盖：底部导航、Activity 重建、Keystore 加密和重新读取、60/29 语言能力表、AudioRecord 采集到真实 16 kHz PCM 帧、前台服务启动/静音/停止。服务测试连接关闭的本地回环端口，不调用外部计费模型。麦克风测试证明安卓录音链路正常，不代表真实说话内容已被云端翻译。

原始结果在 [verification/](verification/)；截图在 [screenshots/](screenshots/)。

## 仍需实际使用验证

- 未使用真实 API Key 调用云端模型，因此没有验证当前账号的模型权限、端到端翻译内容、时延、音色复刻或实际译音质量。
- 系统声音采集代码已编译并按安卓权限/投影生命周期实现，但尚未在实体手机、媒体应用或会议场景中验证。不同应用可能禁止采集，通话与 DRM 音频可能始终无声。
- 尚未进行实体手机蓝牙/有线耳机路由、锁屏长时间运行、各厂商省电策略、Android 10–14 设备兼容性检查。
- 尚未验证真实系统文件提供器的全部导出场景；导出使用 Android Storage Access Framework。

## 复现

```powershell
cd E:\Project\translation\android
.\scripts\build.ps1
# 仅在已启动的、可丢弃的测试模拟器上运行：
.\scripts\test-emulator.ps1 -Serial emulator-5580
```

模拟器已安装发行签名 APK 时，调试签名 APK 无法覆盖安装；请另建测试模拟器或在可丢弃模拟器中卸载发行安装后重测。不要对个人手机运行会写入测试配置的仪器测试。

发行文件：`releases/v1.0.0/simultaneous-translator-android-1.0.0-release.apk`

SHA-256：`cc5041bbea9f5ab5c941e47544dd21a643500f4312e09501d298fffd527ed262`

大小：378,659 字节。包名：`com.cyh.translator`。最低 API 29，目标 API 35，版本名称 1.0.0 / versionCode 1。
