package com.cyh.translator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.*
import kotlin.math.abs

/** Owned by TranslationService, never by an Activity. All methods run on the main thread.
 *  Shows translation-only rolling captions: the window stays small and never shows the source transcript. */
class SubtitleOverlay(
    private val context: Context,
    private val onStatus: (Boolean, String?) -> Unit,
    private val onHide: () -> Unit
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val store = SettingsStore(context)
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var scroll: LimitedScrollView? = null
    private var captionBox: LinearLayout? = null
    private var handle: TextView? = null
    private var collapseButton: Button? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsed = false
    private var dragging = false
    private var closed = false
    private var failed: String? = null
    private var autoRetries = 0
    private var detachedChecks = 0
    private var lastSession = ""
    private var lastStatus: Pair<Boolean, String?>? = null
    private var channelIds = emptyList<String>()
    private var renderedLines = emptyList<SubtitleLine>()
    private var renderedSize = 0
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) { dragging = false; positionWindow() }
    }
    private val permissionCheck = object : Runnable {
        override fun run() {
            if (closed) return
            // Transient failures (rotation, lockscreen, system dialogs) should heal themselves.
            if (failed != null && root == null && autoRetries < 5 && Settings.canDrawOverlays(context)) {
                autoRetries++; failed = null
            }
            render(RuntimeState.snapshot)
            handler.postDelayed(this, 1500)
        }
    }
    init {
        displays.registerDisplayListener(displayListener, handler)
        handler.postDelayed(permissionCheck, 1500)
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun status(visible: Boolean, error: String? = null) {
        if (visible) autoRetries = 0
        val value = visible to error
        if (lastStatus != value) { lastStatus = value; onStatus(visible, error) }
    }
    fun retry() { failed = null; autoRetries = 0; render(RuntimeState.snapshot) }
    fun render(snapshot: SessionSnapshot) {
        if (closed) return
        if (!snapshot.active || !store.overlayEnabled()) { remove(); status(false); return }
        if (!Settings.canDrawOverlays(context)) {
            failed = null; remove(); status(false, "尚未允许显示在其他应用上层"); return
        }
        if (lastSession != snapshot.sessionId) { lastSession = snapshot.sessionId; collapsed = false; failed = null; autoRetries = 0; remove() }
        if (failed != null) { status(false, failed); return }
        // The system can detach the window (e.g. during rotation or permission refresh); rebuild it
        // after two consecutive detached checks instead of reporting a stale "visible" state.
        if (root != null && root?.isAttachedToWindow == false) detachedChecks++ else detachedChecks = 0
        if (detachedChecks >= 2) { remove(); detachedChecks = 0 }
        val configs = store.load().channels()
        if (root == null || configs.map { it.id } != channelIds) {
            remove()
            try { create(configs) } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to create the subtitle overlay", e)
                remove(); failed = "悬浮字幕未能显示，请检查悬浮窗权限后重新开启"; status(false, failed); return
            }
        }
        // Translation-only rolling captions: last finalized lines, then the live partial line.
        val lines = SubtitlePresentation.rolling(snapshot, configs.map { it.id })
        val size = store.overlayTextSize()
        if (lines != renderedLines || size != renderedSize) {
            renderedLines = lines; renderedSize = size
            renderLines(lines)
        }
        val handleText = "同传字幕\n" + stateSummary(configs, snapshot)
        if (handle?.text.toString() != handleText) handle?.text = handleText
        status(true)
    }
    private fun stateSummary(configs: List<ChannelConfig>, snapshot: SessionSnapshot): String =
        SubtitlePresentation.overlayState(snapshot.muted, configs.map { snapshot.states[it.id] ?: "正在连接" })
    private fun renderLines(lines: List<SubtitleLine>) {
        val box = captionBox ?: return
        box.removeAllViews()
        val showChannel = channelIds.size > 1
        lines.forEach { line ->
            box.addView(captionLine(SubtitlePresentation.overlayLineText(line, showChannel), line.partial), LinearLayout.LayoutParams(-1, -2))
        }
        scroll?.visibility = if (collapsed) View.GONE else View.VISIBLE
        root?.post { positionWindow() }
    }
    private fun captionLine(value: String, partial: Boolean) = TextView(context).apply {
        text = value
        textSize = store.overlayTextSize().toFloat()
        setTextColor(if (partial) Color.rgb(233, 229, 239) else Color.rgb(207, 233, 130))
        if (!partial) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(2), 0, dp(2))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(1).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun control(value: String, description: String, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 12f; isAllCaps = false; contentDescription = description
        setTextColor(Color.WHITE); minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = GradientDrawable().apply { setColor(Color.rgb(73, 59, 96)); cornerRadius = dp(4).toFloat() }
        setOnClickListener { action() }
    }
    @SuppressLint("RtlHardcoded") // x/y persist in physical display coordinates, independent of UI locale.
    private fun create(configs: List<ChannelConfig>) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(242, 47, 39, 59)); setStroke(dp(1), Color.rgb(207, 233, 130)); cornerRadius = dp(8).toFloat()
            }
            elevation = dp(6).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root = panel
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        handle = text("同传字幕\n拖动这里", 12f, Color.WHITE, true).apply {
            contentDescription = "拖动悬浮字幕"; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0)
        }
        header.addView(handle, LinearLayout.LayoutParams(0, dp(44), 1f)); bindDrag(handle!!)
        fun add(button: Button) { header.addView(button, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) }) }
        add(control("A−", "缩小字幕字号") { store.setOverlayTextSize(store.overlayTextSize() - 2); render(RuntimeState.snapshot) })
        add(control("A+", "增大字幕字号") { store.setOverlayTextSize(store.overlayTextSize() + 2); render(RuntimeState.snapshot) })
        collapseButton = control(if (collapsed) "展开" else "收起",
            if (collapsed) "展开字幕内容" else "收起字幕内容") {
            collapsed = !collapsed
            scroll?.visibility = if (collapsed) View.GONE else View.VISIBLE
            collapseButton?.text = if (collapsed) "展开" else "收起"
            collapseButton?.contentDescription = if (collapsed) "展开字幕内容" else "收起字幕内容"
            panel.post {
                positionWindow()
                // Re-expanding must keep the newest translation visible instead of resetting to the top.
                if (!collapsed) scroll?.fullScroll(View.FOCUS_DOWN)
            }
        }.also { add(it) }
        add(control("隐藏", "隐藏悬浮字幕，可从通知恢复") { onHide() })
        panel.addView(header)
        captionBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        scroll = LimitedScrollView(context).apply {
            isFillViewport = false; isVerticalScrollBarEnabled = false; addView(captionBox)
            visibility = if (collapsed) View.GONE else View.VISIBLE
        }.also { panel.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        params = WindowManager.LayoutParams(
            minOf(dp(520), area().width), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setTitle("同传悬浮字幕")
        }
        channelIds = configs.map { it.id }
        renderedLines = emptyList(); renderedSize = 0
        measureAndPlace()
        manager.addView(panel, params)
        panel.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) panel.post { positionWindow() }
        }
    }
    private fun area(): OverlayArea {
        val margin = dp(8)
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return OverlayArea(insets.left + margin, insets.top + margin,
                maxOf(1, metrics.bounds.width() - insets.left - insets.right - margin * 2),
                maxOf(1, metrics.bounds.height() - insets.top - insets.bottom - margin * 2))
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        return OverlayArea(margin, dp(32), maxOf(1, metrics.widthPixels - margin * 2), maxOf(1, metrics.heightPixels - dp(80)))
    }
    /** Keep the caption box at roughly three lines, whatever the selected font size. */
    private fun maxCaptionHeight(): Int {
        val line = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
            store.overlayTextSize() * 1.5f, context.resources.displayMetrics)
        return (line * SubtitlePresentation.ROLLING_LINES).toInt() + dp(8)
    }
    private fun measureAndPlace() {
        val panel = root ?: return
        val layout = params ?: return
        val area = area()
        layout.width = minOf(dp(520), area.width)
        scroll?.maxHeight = minOf(maxCaptionHeight(), maxOf(dp(48), area.height / 3))
        panel.measure(View.MeasureSpec.makeMeasureSpec(layout.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(area.height, View.MeasureSpec.AT_MOST))
        val (x, y) = OverlayGeometry.place(area, layout.width, panel.measuredHeight, store.overlayPosition())
        layout.x = x; layout.y = y
    }
    private fun positionWindow() {
        if (dragging || root == null) return
        measureAndPlace(); updateWindow()
    }
    private fun updateWindow() {
        val panel = root ?: return
        if (!panel.isAttachedToWindow) return
        try { manager.updateViewLayout(panel, params) } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to update the subtitle overlay window", e)
            remove(); failed = "悬浮窗权限或屏幕状态已变化，请重新开启悬浮字幕"; status(false, failed)
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun bindDrag(handle: View) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        handle.setOnTouchListener { view, event ->
            val layout = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = layout.x; startY = layout.y; dragging = true; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) + abs(event.rawY - downY) > ViewConfiguration.get(context).scaledTouchSlop) moved = true
                    val area = area()
                    val position = OverlayGeometry.remember(area, layout.width, root?.height ?: 0,
                        startX + (event.rawX - downX).toInt(), startY + (event.rawY - downY).toInt())
                    val (x, y) = OverlayGeometry.place(area, layout.width, root?.height ?: 0, position)
                    layout.x = x; layout.y = y; updateWindow()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    if (moved) store.setOverlayPosition(OverlayGeometry.remember(area(), layout.width, root?.height ?: 0, layout.x, layout.y))
                    else if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    positionWindow()
                }
                else -> return@setOnTouchListener false
            }
            true
        }
    }
    private fun remove() {
        // A view detached during rotation can still be registered in WindowManager; always ask it to remove.
        root?.let { panel ->
            runCatching { manager.removeViewImmediate(panel) }
                .onFailure { Log.w(TAG, "Unable to remove the subtitle overlay", it) }
        }
        root = null; params = null; scroll = null; captionBox = null; handle = null; collapseButton = null
        channelIds = emptyList(); renderedLines = emptyList(); renderedSize = 0; dragging = false; detachedChecks = 0
    }
    fun close() {
        closed = true; handler.removeCallbacksAndMessages(null); displays.unregisterDisplayListener(displayListener)
        remove(); status(false)
    }
    private class LimitedScrollView(context: Context) : ScrollView(context) {
        var maxHeight = 600
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) maxHeight else View.MeasureSpec.getSize(heightMeasureSpec)
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(minOf(maxHeight, available), View.MeasureSpec.AT_MOST))
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            // Follow the newest line after the content/viewport has been laid out. Scrolling itself
            // does not request a layout, so a user can still scroll the box by hand.
            post { fullScroll(View.FOCUS_DOWN) }
        }
    }
    private companion object { const val TAG = "SubtitleOverlay" }
}
