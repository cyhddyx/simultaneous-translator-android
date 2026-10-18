# 协议与生命周期

参考 Windows `scripts/livetranslate.py`，Android 使用 Kotlin/OkHttp 直连同一实时接口，没有 Python 子进程或自建中转服务。Android 端固定为纯文字输出：不请求、不解码、不播放译文语音。

## 会话流程

1. 校验 WSS 地址、模型与目标语言，读取 Keystore 中的密钥。地址只接受 `wss://`，不能包含密码或片段；保留原有查询参数并替换 `model`。
2. 请求录音权限；手机声音或双向模式另请求本次 MediaProjection 授权。
3. 可见 Activity 启动前台服务并显示通知；`mediaProjection` 类型在所有支持版本按模式声明，`microphone` 类型仅在 Android 11（API 30）及以上声明（Manifest 声明 `microphone|mediaProjection`），随后获取投影对象。
4. 每个通道独立连接 WSS，鉴权为 Authorization Bearer。
5. 发送 session.update：`output_modalities` 固定为 `["text"]`，`translation.language` 为 ISO 代码；不发送 voice、音色复刻或任何输出语音相关字段。
6. 等待并验证 session.updated 后才采集、上传音频。若服务端在 session.updated 中返回 `output_modalities`，必须只含 `text`，否则结束会话并提示。输入为 16 kHz 单声道 PCM16 小端序，每帧约 100 ms（3200 字节），Base64 编码后发送 `input_audio_buffer.append`。不发送 commit、response.create 或独立识别模型配置。
7. 处理识别和翻译的 delta/text/done，以及 response.done。最终原文/译文按队列配对，允许译文先到；按 response ID 去重，兼容 text/transcript 字段差异；服务端若改用 `response.audio_transcript.*` 事件，只取其文本字段。`session.finished` 触发重连。
8. `response.audio.delta` 直接丢弃：不解码、不播放、不写入字幕。服务端返回的音频不会出现在任何界面或存储中。

## 系统声音与输入

系统声音只匹配允许采集的媒体、游戏及 unknown usage，显式排除应用自身 UID。输入使用安卓当前音频路由。采集与网络各自运行，网络或设备异常只影响本通道并给出提示；应用不创建 AudioTrack，没有译文播放入口、音色选择或播放积压逻辑。

## 恢复和停止

连接断开后使旧回调失效、标记未完成字幕，按 1、2、4、8、15 秒间隔重连。新连接重新验证配置，重连期间不积压音频：发送队列超过 10 帧时丢弃新帧。接口错误中的 idle timeout 与 1007 触发重连；鉴权（401/403）、接口或模型（400/404/422）错误，以及 response.done 状态为 failed/cancelled/incomplete 时结束会话并提示原因。

静音只对发言通道生效，在发送前丢弃音频帧。每 5 秒检查最后一次音频发送时间；空闲超过 20 秒时发送 100 ms 静音保活，不创建本地字幕或保存录音。

用户停止、投影授权撤销或服务销毁时，释放 AudioRecord、MediaProjection 和唤醒锁，取消重连，发送 session.finish 并关闭连接。服务使用 START_NOT_STICKY，进程被系统回收后不重用失效授权。单次唤醒锁最长 12 小时。

## 前台服务与悬浮字幕

- 前台服务 `TranslationService` 持有悬浮窗 `SubtitleOverlay`，Activity 不直接创建窗口。`onDestroy` 中关闭悬浮窗并注销显示监听，停止会话即移除窗口，不残留到下一次会话。
- 仅在会话进行中、悬浮字幕开关开启且已获得「显示在其他应用上层」权限时显示。窗口类型为 `TYPE_APPLICATION_OVERLAY`，带 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN`：不抢焦点，窗口外的触摸交给下方应用。
- 开始同传时 Activity 检查权限，未授权先引导到系统设置；用户拒绝后回到「仅用应用内字幕」继续，悬浮字幕保持关闭，可在同传页按钮或通知重新开启。权限被撤销时窗口移除并在同传页显示错误。
- 标题第一行固定为「同传字幕」，第二行显示状态（正在翻译 / 正在连接 / 重连中 / 已静音）；标题区域可拖动；A− / A+ 调整译文字号（16–30）；收起/展开只隐藏字幕内容，保留标题和按钮；「隐藏」把悬浮字幕开关置为关闭，之后可从通知栏「显示字幕」或同传页按钮恢复。设置页不再提供「悬浮窗同时显示原文」开关。
- 窗口位置以相对坐标（0–1）保存，屏幕旋转、窗口尺寸变化时按当前可用区域重新换算并夹在安全区域内。瞬时失败（旋转、锁屏、系统对话框等）每 1.5 秒自动重试，最多 5 次；窗口连续两次检测到被系统摘除时自动移除并重建；自动恢复失败才移除窗口并提示，需要手动重新开启。移除窗口无条件调用 `removeViewImmediate`，避免旋转后残留。
- 悬浮窗内容由 `SubtitlePresentation.rolling` 生成，只取译文：最多 3 行——已完成译文（绿色加粗）在前，实时译文（浅色非加粗）占最后一行，合计取最新 3 行；新译文追加到底部，旧行向上滚出。行数超出时字幕框内可滚动、滚动条隐藏，并自动保持最新一行可见；字幕框不超过可用高度 1/3，标题与内边距另计，默认字号下整窗约屏幕 1/6；尚未收到第一段译文时只保留标题，不显示占位文案。
- 双向模式同时显示两个通道的译文，前缀为「收听｜」「发言｜」（单通道时无前缀）。原文字幕（source）不进入悬浮窗，只保留在应用内界面、历史会话记录与导出文本中。

## 存储

密钥使用 Keystore AES-GCM，加密后保存在 SharedPreferences，禁用系统备份。字幕保存在应用私有 SQLite，保留最近 100 次会话；运行内存保留最近 100 条，主页显示最近 6 条。导出由用户通过系统文件选择器选定目标。

悬浮字幕设置同样保存在应用私有 SharedPreferences：是否自动显示（默认开）、译文字号（16–30，默认 20）、位置相对坐标（默认 0.5 / 0.72）；旧版本保存的 `overlay.showSource`（是否同时显示原文）偏好不再读取。旧版本的语音偏好（如 `*.audio`、`*.clone`）在读取时忽略，保存设置时清除，升级安装不会恢复语音输出。
