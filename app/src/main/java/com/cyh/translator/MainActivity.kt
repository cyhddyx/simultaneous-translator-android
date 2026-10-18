package com.cyh.translator

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val ink = Color.rgb(47, 39, 59)
    private val plum = Color.rgb(73, 59, 96)
    private val paper = Color.rgb(245, 239, 223)
    private val white = Color.rgb(255, 252, 244)
    private val lime = Color.rgb(207, 233, 130)
    private val blue = Color.rgb(213, 233, 237)
    private val pink = Color.rgb(232, 181, 199)
    private val muted = Color.rgb(104, 94, 114)
    private lateinit var store: SettingsStore
    private lateinit var history: HistoryStore
    private lateinit var languages: List<Language>
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private var selectedTab = 0
    private var screenGeneration = 0
    private var pendingStart = false
    private var overlayPermissionPending = false
    private var startAfterOverlayPermission = false
    private var exportText = ""
    private var homeStatus: TextView? = null
    private var homeError: TextView? = null
    private var startButton: Button? = null
    private var muteButton: Button? = null
    private var overlayButton: Button? = null
    private var overlayHint: TextView? = null
    private var modeSpinner: Spinner? = null
    private var recentContainer: LinearLayout? = null
    private var captionHash = 0
    private val channelViews = mutableMapOf<String, ChannelViews>()
    private data class ChannelViews(val state: TextView, val level: ProgressBar, val source: TextView, val translation: TextView)
    private val observer: (SessionSnapshot) -> Unit = { updateHome(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this); history = HistoryStore.get(this); languages = Languages.load(this)
        selectedTab = savedInstanceState?.getInt("tab", 0) ?: 0
        pendingStart = savedInstanceState?.getBoolean("pendingStart", false) ?: false
        overlayPermissionPending = savedInstanceState?.getBoolean("overlayPermissionPending", false) ?: false
        startAfterOverlayPermission = savedInstanceState?.getBoolean("startAfterOverlayPermission", false) ?: false
        buildShell(); showTab(selectedTab)
    }
    override fun onStart() { super.onStart(); RuntimeState.observe(observer) }
    override fun onResume() {
        super.onResume()
        if (overlayPermissionPending) completeOverlayPermission()
        if (intent.getBooleanExtra("requestOverlay", false)) {
            intent.removeExtra("requestOverlay")
            store.setOverlayEnabled(true)
            if (Settings.canDrawOverlays(this)) refreshOverlay() else requestOverlayPermission(false)
        }
        updateHome(RuntimeState.snapshot)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent) }
    override fun onStop() { RuntimeState.remove(observer); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", selectedTab); outState.putBoolean("pendingStart", pendingStart)
        outState.putBoolean("overlayPermissionPending", overlayPermissionPending)
        outState.putBoolean("startAfterOverlayPermission", startAfterOverlayPermission)
        super.onSaveInstanceState(outState)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun space(parent: LinearLayout, size: Int = 12) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(size))) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun box(color: Int, stroke: Boolean = true) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(color); if (stroke) setStroke(dp(2), ink)
    }
    private fun label(value: String, size: Float = 16f, bold: Boolean = false, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(value: String, color: Int = white, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 15f; isAllCaps = false; setTextColor(ink); background = box(color)
        minHeight = dp(50); minimumHeight = dp(50); setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { action() }
    }
    private fun card(parent: LinearLayout, color: Int = white): LinearLayout = column().apply {
        background = box(color); setPadding(dp(16), dp(16), dp(16), dp(16))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2)); space(parent)
    }
    private fun paragraph(parent: LinearLayout, text: String, size: Float = 14f, color: Int = muted) {
        parent.addView(label(text, size, color = color)); space(parent, 8)
    }
    private fun scrollColumn(parent: LinearLayout, weighted: Boolean = true): LinearLayout {
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        parent.addView(scroll, if (weighted) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(-1, -1))
        return column().apply { setPadding(dp(18), dp(16), dp(18), dp(16)); scroll.addView(this) }
    }
    private fun buildShell() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        root = column().apply { setBackgroundColor(paper) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val heading = row().apply { setPadding(dp(20), dp(14), dp(20), dp(14)) }
        val icon = ImageView(this).apply { setImageResource(R.drawable.ic_launcher); contentDescription = "同传翻译" }
        heading.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
        val titles = column()
        titles.addView(label("同传翻译", 22f, true)); titles.addView(label("让交流，少一点距离。", 12f, color = muted))
        heading.addView(titles, LinearLayout.LayoutParams(0, -2, 1f)); heading.addView(label("ANDROID", 10f, true, plum))
        root.addView(heading); root.addView(View(this).apply { setBackgroundColor(ink) }, LinearLayout.LayoutParams(-1, dp(2)))
        body = FrameLayout(this); root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = row().apply { setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(white) }
        listOf("同传", "历史", "设置").forEachIndexed { index, title ->
            nav.addView(button(title) { showTab(index) }.apply { tag = index }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        root.addView(View(this).apply { setBackgroundColor(ink) }, LinearLayout.LayoutParams(-1, dp(2))); root.addView(nav)
        setContentView(root)
    }
    private fun showTab(index: Int) {
        selectedTab = index; screenGeneration++; body.removeAllViews()
        homeStatus = null; homeError = null; startButton = null; muteButton = null; modeSpinner = null; recentContainer = null; channelViews.clear()
        overlayButton = null; overlayHint = null
        val nav = root.getChildAt(root.childCount - 1) as LinearLayout
        for (i in 0 until nav.childCount) nav.getChildAt(i).background = box(if (i == index) lime else white)
        when (index) { 0 -> showHome(); 1 -> showHistory(); 2 -> showSettings() }
    }
    private fun language(code: String) = languages.find { it.code == code }?.label ?: code
    private fun showHome() {
        val page = column(); body.addView(page)
        val content = scrollColumn(page)
        val hero = card(content, lime)
        hero.addView(label("听见世界 · 自在表达", 24f, true)); space(hero, 8)
        homeStatus = label(RuntimeState.snapshot.status, 14f).also { hero.addView(it) }
        if (!store.hasKey()) {
            val setup = card(content, blue)
            setup.addView(label("第一次使用", 17f, true)); space(setup, 6)
            paragraph(setup, "填写同传服务的 API Key，选择语言后即可开始。音频通过加密连接发送至你配置的模型服务。")
            setup.addView(button("配置同传服务", white) { showTab(2) })
        }
        val settings = store.load()
        val subtitles = card(content)
        subtitles.addView(label("悬浮字幕", 17f, true)); space(subtitles, 6)
        overlayHint = label("", 13f, color = muted).also { subtitles.addView(it) }; space(subtitles, 8)
        overlayButton = button("开启悬浮字幕", blue) { toggleOverlay() }.also { subtitles.addView(it) }
        val modeCard = card(content)
        modeCard.addView(label("使用场景", 13f, true, muted))
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, InputMode.entries.map { it.label })
            setSelection(settings.mode.ordinal); minimumHeight = dp(52)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (RuntimeState.snapshot.active) return
                    val current = store.load(); val mode = InputMode.entries[position]
                    if (current.mode != mode) { store.save(current.copy(mode = mode)); showTab(0) }
                }
            }
        }.also { modeCard.addView(it) }
        paragraph(modeCard, if (settings.mode == InputMode.MICROPHONE) "用手机麦克风收音，自动识别语言并生成翻译字幕。"
            else "仅采集允许录制的媒体/游戏声音。通话、DRM 内容或禁止采集的应用可能无声。系统会在每次开始前请求授权。", 12f)
        settings.channels().forEach { config ->
            val c = card(content, if (config.id == "listen") blue else white)
            val heading = row()
            heading.addView(label(if (config.id == "listen") "收听 · 手机声音" else "发言 · 麦克风", 16f, true), LinearLayout.LayoutParams(0, -2, 1f))
            val state = label("待机", 12f, color = muted); heading.addView(state); c.addView(heading)
            space(c, 6); paragraph(c, "自动识别  →  ${language(config.target)}  ·  字幕", 12f)
            val meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = android.content.res.ColorStateList.valueOf(plum); contentDescription = "输入音量" }
            c.addView(meter, LinearLayout.LayoutParams(-1, dp(8))); space(c)
            val source = label("原文会显示在这里", 15f, color = muted).apply { setTextIsSelectable(true) }; c.addView(source); space(c, 8)
            val translation = label("等待开始同传", 22f, true).apply { setTextIsSelectable(true) }; c.addView(translation)
            channelViews[config.id] = ChannelViews(state, meter, source, translation)
        }
        homeError = label("", 14f, color = Color.rgb(150, 54, 78)).also { content.addView(it) }
        space(content)
        content.addView(label("本次记录", 16f, true)); space(content, 8)
        recentContainer = column().also { content.addView(it) }; captionHash = Int.MIN_VALUE
        val controls = row().apply { setPadding(dp(18), dp(12), dp(18), dp(12)); setBackgroundColor(paper) }
        startButton = button("开始同传", lime) {
            if (RuntimeState.snapshot.active) command(TranslationService.ACTION_STOP) else requestStart()
        }.also { controls.addView(it, LinearLayout.LayoutParams(0, dp(56), 1f)) }
        muteButton = button("静音", white) { command(TranslationService.ACTION_MUTE) }.also {
            controls.addView(it, LinearLayout.LayoutParams(dp(96), dp(56)).apply { marginStart = dp(10) })
        }
        page.addView(controls); updateHome(RuntimeState.snapshot)
    }
    private fun updateHome(snapshot: SessionSnapshot) {
        if (selectedTab != 0) return
        homeStatus?.text = snapshot.status
        homeError?.apply { text = snapshot.error ?: ""; visibility = if (snapshot.error == null) View.GONE else View.VISIBLE }
        val permitted = Settings.canDrawOverlays(this)
        overlayButton?.apply {
            isEnabled = !pendingStart && !overlayPermissionPending
            text = when {
                !permitted -> "授权并开启悬浮字幕"
                snapshot.overlayVisible -> "隐藏悬浮字幕"
                snapshot.active -> "显示悬浮字幕"
                store.overlayEnabled() -> "关闭自动悬浮"
                else -> "开启悬浮字幕"
            }
        }
        overlayHint?.text = snapshot.overlayError ?: when {
            !permitted -> "允许“显示在其他应用上层”后，切换应用也能看到字幕。"
            snapshot.overlayVisible -> "已显示。只显示译文，最多约 3 行滚动；拖动标题移动，A− / A+ 调整字号，隐藏后可从通知恢复。"
            store.overlayEnabled() -> "开始同传后自动显示在其他应用上方，只显示译文并自动滚动。"
            else -> "当前仅显示应用内字幕，可随时开启悬浮字幕。"
        }
        modeSpinner?.isEnabled = !snapshot.active && !pendingStart
        startButton?.apply { text = if (snapshot.active) "停止同传" else if (pendingStart) "等待授权…" else "开始同传"; isEnabled = !pendingStart; background = box(if (snapshot.active) pink else lime) }
        muteButton?.apply {
            visibility = if (snapshot.active && store.load().mode != InputMode.SYSTEM) View.VISIBLE else View.GONE
            text = if (snapshot.muted) "恢复" else "静音"
        }
        channelViews.forEach { (id, views) ->
            views.state.text = if (id == "speak" && snapshot.muted) "已静音" else snapshot.states[id] ?: "待机"
            views.level.progress = if (id == "speak" && snapshot.muted) 0 else snapshot.levels[id] ?: 0
            val text = SubtitlePresentation.text(snapshot, id)
            views.source.text = text.source
            views.translation.text = text.translation
        }
        if (captionHash != snapshot.captions.hashCode()) {
            captionHash = snapshot.captions.hashCode(); recentContainer?.removeAllViews()
            recentContainer?.let { list ->
                if (snapshot.captions.isEmpty()) paragraph(list, "说话后，原文与译文会成对保存在这里。完整会话可在「历史」中查看、导出。")
                snapshot.captions.takeLast(6).reversed().forEach { caption -> captionView(list, caption) }
            }
        }
    }
    private fun captionView(parent: LinearLayout, caption: Caption) {
        val c = card(parent)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(caption.createdAt))
        paragraph(c, "${if (caption.channel == "listen") "收听" else "发言"}  ·  $time${if (caption.status == "interrupted") "  ·  连接中断" else ""}", 11f)
        c.addView(label(caption.source.ifBlank { "（未收到原文）" }, 14f, color = muted).apply { setTextIsSelectable(true) }); space(c, 6)
        c.addView(label(caption.translation.ifBlank { if (caption.status == "interrupted") "此片段未完成" else "等待译文…" }, 17f, true).apply { setTextIsSelectable(true) })
    }
    private fun field(parent: LinearLayout, title: String, value: String, secret: Boolean = false): EditText {
        parent.addView(label(title, 13f, true)); space(parent, 6)
        return EditText(this).apply {
            textSize = 14f; setTextColor(ink); background = box(white); setPadding(dp(12), dp(10), dp(12), dp(10)); minHeight = dp(52)
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true); setText(value); isSaveEnabled = !secret
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            parent.addView(this, LinearLayout.LayoutParams(-1, -2)); space(parent)
        }
    }
    private fun chooseLanguage(current: String, callback: (String) -> Unit) {
        val container = column().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val search = EditText(this).apply { hint = "搜索语言名称或代码"; setSingleLine(true) }; container.addView(search)
        val list = ListView(this); container.addView(list, LinearLayout.LayoutParams(-1, dp(360)))
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, languages.map { "${it.label}  ·  ${it.code}" })
        list.adapter = adapter
        val dialog = AlertDialog.Builder(this).setTitle("目标语言 · 当前 ${language(current)}").setView(container).setNegativeButton("取消", null).create()
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { adapter.filter.filter(s) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val code = adapter.getItem(position)!!.split("  ·  ")[1]; callback(code); dialog.dismiss()
        }; dialog.show()
    }
    private fun showSettings() {
        val page = column(); body.addView(page); val content = scrollColumn(page)
        content.addView(label("按你的方式交流", 24f, true)); space(content)
        val active = RuntimeState.snapshot.active
        if (active) paragraph(content, "同传正在运行。停止后即可修改设置。", color = plum)
        var draft = store.load()
        val service = card(content)
        service.addView(label("同传服务", 18f, true)); space(service)
        paragraph(service, "沿用桌面版的 Qwen LiveTranslate 实时接口。使用对应服务商的 API Key，费用由服务商按用量收取。", 13f)
        val endpoint = field(service, "WebSocket 地址", draft.endpoint)
        val model = field(service, "同传模型", draft.model)
        val key = field(service, "API Key", "", true).apply { hint = if (store.hasKey()) "已安全保存 · 留空保留" else "粘贴服务商提供的 API Key" }
        paragraph(service, "密钥使用 Android Keystore 加密保存。应用不会保存录音；字幕历史保存在手机本地。", 12f)
        service.addView(button("获取百炼 API Key", blue) { openUrl("https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key") }); space(service, 8)
        service.addView(button("清除已保存密钥") {
            AlertDialog.Builder(this).setTitle("清除 API Key？").setMessage("之后需要重新填写密钥才能开始同传。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> store.clearKey(); key.hint = "粘贴服务商提供的 API Key"; toast("密钥已清除") }.show()
        }.apply { isEnabled = !active })
        listOf("listen", "speak").forEach { id ->
            val c = card(content, if (id == "listen") blue else white)
            val initial = if (id == "listen") draft.listen else draft.speak
            c.addView(label(if (id == "listen") "收听 · 对方 → 我" else "发言 · 我 → 对方", 18f, true)); space(c)
            fun config() = if (id == "listen") draft.listen else draft.speak
            fun update(value: ChannelConfig) { draft = if (id == "listen") draft.copy(listen = value) else draft.copy(speak = value) }
            lateinit var languageButton: Button
            languageButton = button("目标语言  ·  ${language(initial.target)}") {
                chooseLanguage(config().target) { code -> update(config().copy(target = code)); languageButton.text = getString(R.string.target_language, language(code)) }
            }.apply { isEnabled = !active }; c.addView(languageButton); space(c, 8)
            paragraph(c, "自动识别输入语言，输出翻译字幕，支持 60 种目标语言。", 12f)
        }
        val subtitleSettings = card(content)
        subtitleSettings.addView(label("字幕显示", 18f, true)); space(subtitleSettings, 8)
        paragraph(subtitleSettings, "悬浮窗只显示译文，像视频字幕一样最多保留约 3 行：新的译文出现在底部，旧译文向上滚动。原文请在「历史」的会话记录中查看。", 12f)
        paragraph(subtitleSettings, "在同传页开启悬浮字幕；窗内可拖动标题移动位置、A− / A+ 调整字号、收起或隐藏。设置即时生效。", 12f)
        endpoint.isEnabled = !active; model.isEnabled = !active; key.isEnabled = !active
        content.addView(button("保存设置", lime) {
            try {
                val updated = draft.copy(endpoint = endpoint.text.toString().trim(), model = model.text.toString().trim())
                // Validate both channels, including a currently inactive channel.
                updated.copy(mode = InputMode.DUAL).validate(languages)
                store.save(updated, key.text.toString().trim()); toast("设置已保存"); showTab(0)
            } catch (e: Exception) { message("无法保存", e.message ?: "请检查设置") }
        }.apply { isEnabled = !active })
        space(content, 20)
        paragraph(content, "Android 10 及以上 · v1.1.1 · 纯字幕版\n系统声音采集受其他应用的权限策略限制，通话或禁止录制的应用可能无声。部分安全页面会主动隐藏悬浮窗。", 12f)
    }
    private fun showHistory() {
        val generation = screenGeneration
        val page = column(); body.addView(page); val content = scrollColumn(page)
        content.addView(label("每一次交流，都有迹可循", 23f, true)); space(content)
        paragraph(content, "最近 100 次会话 · 仅保存在这台手机")
        val actions = row()
        actions.addView(button("导出文本", blue) { exportHistory() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        actions.addView(button("清空历史") {
            if (RuntimeState.snapshot.active) { message("请先停止同传", "结束会话后可以清空历史。"); return@button }
            AlertDialog.Builder(this).setTitle("清空全部历史？").setMessage("本地字幕记录将被删除，无法撤销。")
                .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                    history.worker.execute { history.clear(); runOnUiThread { if (!isDestroyed && selectedTab == 1) showTab(1) } }
                }.show()
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(8) })
        content.addView(actions); space(content)
        val loading = label("正在读取记录…", 14f, color = muted); content.addView(loading)
        history.worker.execute {
            val result = runCatching { history.load() }
            runOnUiThread {
                if (isDestroyed || generation != screenGeneration) return@runOnUiThread
                content.removeView(loading)
                result.onFailure { paragraph(content, "读取失败：${it.message}") }.onSuccess { sessions ->
                    if (sessions.isEmpty()) paragraph(content, "还没有会话记录。开始一次同传后，字幕会自动保存在这里。")
                    val date = SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault())
                    sessions.forEach { session ->
                        val c = card(content)
                        c.addView(label(date.format(Date(session.startedAt)), 18f, true)); space(c, 5)
                        paragraph(c, "${session.title} · ${session.captions.size} 条字幕", 12f)
                        paragraph(c, session.captions.lastOrNull()?.translation?.take(100) ?: "本次会话暂无字幕", 14f)
                        c.addView(button("查看会话") { showSession(session) })
                    }
                }
            }
        }
    }
    private fun showSession(session: HistorySession) {
        val scroll = ScrollView(this)
        val text = label(HistoryStore.export(listOf(session)), 15f).apply { setPadding(dp(20), dp(12), dp(20), dp(12)); setTextIsSelectable(true) }
        scroll.addView(text)
        AlertDialog.Builder(this).setTitle("会话字幕").setView(scroll).setPositiveButton("完成", null)
            .setNeutralButton("导出") { _, _ -> createExport(HistoryStore.export(listOf(session))) }.show()
    }
    private fun exportHistory() {
        history.worker.execute {
            val result = runCatching { HistoryStore.export(history.load()) }
            runOnUiThread { if (!isDestroyed) result.onSuccess { createExport(it) }.onFailure { message("导出失败", it.message ?: "无法读取历史") } }
        }
    }
    private fun createExport(text: String) {
        exportText = text
        // Persist the pending export locally so rotation/process recreation during the picker is safe.
        cacheDir.resolve("pending-export.txt").writeText(text)
        val name = "同传记录-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())}.txt"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = "text/plain"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, name) }, 30)
    }
    private fun requestStart() {
        try {
            store.load().validate(languages)
            require(store.apiKey().isNotBlank()) { "请在设置中填写 API Key" }
        } catch (e: Exception) { message("开始前还需配置", e.message ?: "请检查设置"); showTab(2); return }
        pendingStart = true; updateHome(RuntimeState.snapshot)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10); return
        }
        requestNotificationThenCapture()
    }
    private fun requestNotificationThenCapture() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11); return
        }
        requestOverlayThenCapture()
    }
    private fun requestOverlayThenCapture() {
        if (store.overlayEnabled() && !Settings.canDrawOverlays(this)) requestOverlayPermission(true) else requestCapture()
    }
    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(false); return }
        if (RuntimeState.snapshot.active) command(TranslationService.ACTION_TOGGLE_OVERLAY)
        else { store.setOverlayEnabled(!store.overlayEnabled()); updateHome(RuntimeState.snapshot) }
    }
    private fun refreshOverlay() {
        if (RuntimeState.snapshot.active) command(TranslationService.ACTION_REFRESH_OVERLAY)
        updateHome(RuntimeState.snapshot)
    }
    private fun requestOverlayPermission(forStart: Boolean) {
        overlayPermissionPending = true; startAfterOverlayPermission = forStart
        try {
            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 40)
        } catch (_: Exception) {
            overlayPermissionPending = false; pendingStart = false; updateHome(RuntimeState.snapshot)
            message("请开启悬浮窗权限", "请在系统设置 → 应用 → 同传翻译中，允许“显示在其他应用上层”或“悬浮窗”，然后重试。")
        }
    }
    private fun completeOverlayPermission() {
        if (!overlayPermissionPending) return
        val continueStart = startAfterOverlayPermission
        overlayPermissionPending = false; startAfterOverlayPermission = false
        if (Settings.canDrawOverlays(this)) {
            store.setOverlayEnabled(true); refreshOverlay()
            if (continueStart) requestCapture()
        } else if (continueStart) {
            pendingStart = false; updateHome(RuntimeState.snapshot)
            AlertDialog.Builder(this).setTitle("悬浮字幕尚未授权")
                .setMessage("没有“显示在其他应用上层”权限，字幕只能在本应用里显示。")
                .setNegativeButton("返回", null).setPositiveButton("仅用应用内字幕") { _, _ ->
                    store.setOverlayEnabled(false); pendingStart = true; requestCapture()
                }.show()
        } else { updateHome(RuntimeState.snapshot); toast("尚未开启悬浮窗权限") }
    }
    private fun requestCapture() {
        if (store.load().mode == InputMode.MICROPHONE) launchService(null, Activity.RESULT_CANCELED)
        else {
            val manager = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(manager.createScreenCaptureIntent(), 20)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!pendingStart) return
        if (requestCode == 10) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestNotificationThenCapture()
            else { pendingStart = false; updateHome(RuntimeState.snapshot); message("需要音频权限", "麦克风和系统声音采集都需要录音权限。你可以在系统应用设置中授予权限后重试。") }
        } else if (requestCode == 11) requestOverlayThenCapture()
    }
    @Deprecated("Uses platform activity result API to avoid an unnecessary activity dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 40) {
            completeOverlayPermission()
        } else if (requestCode == 20) {
            if (pendingStart && resultCode == Activity.RESULT_OK && data != null) launchService(data, resultCode)
            else { pendingStart = false; updateHome(RuntimeState.snapshot); toast("已取消系统声音授权") }
        } else if (requestCode == 30 && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            history.worker.execute {
                val result = runCatching {
                    val text = exportText.ifBlank { cacheDir.resolve("pending-export.txt").readText() }
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                    cacheDir.resolve("pending-export.txt").delete()
                }
                runOnUiThread { if (!isDestroyed) result.onSuccess { toast("字幕已导出") }.onFailure { message("导出失败", it.message ?: "文件无法写入") } }
            }
        }
    }
    private fun launchService(projection: Intent?, resultCode: Int) {
        pendingStart = false
        try {
            val intent = Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_START)
                .putExtra("projectionCode", resultCode)
            if (projection != null) intent.putExtra("projectionData", projection)
            startForegroundService(intent)
        } catch (e: Exception) { message("无法启动", e.message ?: "请检查系统权限") }
        updateHome(RuntimeState.snapshot)
    }
    private fun command(action: String) { startService(Intent(this, TranslationService::class.java).setAction(action)) }
    private fun openUrl(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("没有可用浏览器") } }
    private fun message(title: String, text: String) { AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("知道了", null).show() }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
}
