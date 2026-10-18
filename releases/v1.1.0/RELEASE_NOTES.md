# 同传翻译 Android 1.1.0

安卓独立版本 v1.1.0（versionCode 2），支持 Android 10 及以上。

新增跨应用悬浮字幕：同传时可在其他应用上方显示字幕；拖动标题移动，A− / A+ 调整译文字号（16–30），可收起/展开；点「隐藏」后可从通知栏「显示字幕」恢复；设置里可开关「悬浮窗同时显示原文」；位置按相对坐标记忆并适配横屏/旋转；收到第一段真实字幕前悬浮窗只显示状态行；未授权时可拒绝并只保留应用内字幕。

手机端改为纯字幕：移除译文语音播放、语音转语音和音色复刻；实时接口固定 `output_modalities=["text"]` 并忽略 `response.audio.delta`；旧版本保存的语音偏好会被忽略并在保存设置时清理。API Key、历史记录和语言设置升级后保留。

发行包：`simultaneous-translator-android-1.1.0-release.apk`，391,231 字节，SHA-256 `8ec839950ff4a320f910a81ca07ad73928b8b2ac0b79b35bcda97cd80487fade`，APK Signature Scheme v2。覆盖升级必须使用同一签名（本地发布证书，与 v1.0.0 相同），签名不同需先卸载；与调试签名包不能互相覆盖。安装包与 `SHA256SUMS.txt` 位于 `releases/v1.1.0/`。

自动化验证：28 项 JVM 单元/联调测试、7 项 API 35 模拟器仪器测试通过，Lint 0 errors；原始结果与完整测试范围见 `docs/VERIFICATION.md`。真实 API Key 云端翻译、实体手机悬浮窗与系统声音采集仍需实际环境验证。安卓不支持向其他应用注入虚拟麦克风音频。

源码和说明：../../README.md；完整测试范围：../../docs/VERIFICATION.md。
