package com.cyh.translator

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OverlaySmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(80) }
        fail("Expected overlay condition was not reached")
    }
    private fun state(condition: (SessionSnapshot) -> Boolean): Boolean {
        var result = false; instrumentation.runOnMainSync { result = condition(RuntimeState.snapshot) }; return result
    }
    private fun node(value: String): AccessibilityNodeInfo? = automation.windows.asSequence()
        .mapNotNull { it.root }.flatMap { it.findAccessibilityNodeInfosByText(value).asSequence() }
        .firstOrNull { it.text?.toString() == value || it.contentDescription?.toString() == value }
    private fun click(value: String) {
        await { node(value) != null }
        assertTrue(requireNotNull(node(value)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    /** Null while rotation recreates the window; await() retries instead of aborting the test. */
    private fun bounds(): Rect? {
        val window = automation.windows.firstOrNull { it.root?.findAccessibilityNodeInfosByText("拖动悬浮字幕")?.isNotEmpty() == true }
            ?: return null
        return Rect().also { window.getBoundsInScreen(it) }
    }
    private fun screenshot(context: Context, name: String) {
        val bitmap = requireNotNull(automation.takeScreenshot())
        context.getExternalFilesDir(null)!!.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun captionAssembler(): CaptionAssembler {
        val channel = "speak"
        return CaptionAssembler(channel, { caption ->
            val snapshot = RuntimeState.snapshot
            RuntimeState.publish(snapshot.copy(captions = snapshot.captions.filterNot { it.id == caption.id } + caption))
        }, { s, t ->
            val snapshot = RuntimeState.snapshot
            RuntimeState.publish(snapshot.copy(sources = if (s == null) snapshot.sources else snapshot.sources + (channel to s),
                translations = if (t == null) snapshot.translations else snapshot.translations + (channel to t)))
        })
    }
    private fun caption(assembler: CaptionAssembler, index: Int, source: String, translation: String) {
        assembler.accept(JSONObject("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"overlay-source-$index\",\"transcript\":\"$source\"}"))
        assembler.accept(JSONObject("{\"type\":\"response.text.done\",\"response_id\":\"overlay-response-$index\",\"text\":\"$translation\"}"))
    }
    /** The service retries its unreachable socket and clears partials on every reset, so re-assert the live line. */
    private fun awaitPartial(assembler: CaptionAssembler, text: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            instrumentation.runOnMainSync {
                assembler.accept(JSONObject("{\"type\":\"response.text.delta\",\"response_id\":\"overlay-partial-5\",\"delta\":\"$text\"}"))
            }
            if (node(text) != null) return
            Thread.sleep(60)
            if (node(text) != null) return
        }
        fail("Expected live translation line was not reached")
    }
    @Test fun rollingTranslationSubtitleFloatsAboveOtherApps() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        val previous = store.load(); val previousKey = store.apiKey()
        val enabled = store.overlayEnabled(); val size = store.overlayTextSize(); val position = store.overlayPosition()
        val oldFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        assertTrue(Settings.canDrawOverlays(context))
        store.save(AppSettings(endpoint = "wss://127.0.0.1:9/realtime"), "overlay-test-key")
        store.setOverlayEnabled(true); store.setOverlayTextSize(20)
        store.setOverlayPosition(OverlayPosition())
        val screenHeight = context.resources.displayMetrics.heightPixels
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.startForegroundService(Intent(activity, TranslationService::class.java).setAction(TranslationService.ACTION_START)) }
                await { state { it.active && it.overlayVisible } }
                // Nothing translated yet: the window keeps only its title instead of covering the screen.
                await { bounds() != null }
                val emptyBounds = requireNotNull(bounds())
                assertTrue("Empty overlay must stay a small title bar", emptyBounds.height() < screenHeight / 6)
                // Feed real protocol events through the caption assembler; no cloud calls or fabricated production history.
                lateinit var assembler: CaptionAssembler
                instrumentation.runOnMainSync {
                    assembler = captionAssembler()
                    caption(assembler, 1, "Hello, this is an overlay test.", "你好，这是跨应用字幕测试。")
                    caption(assembler, 2, "The original is never shown.", "原文不会出现在悬浮窗。")
                }
                // Android Settings deliberately hides non-system overlays. Use a normal app.
                context.startActivity(Intent("android.intent.action.SHOW_ALARMS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                await { automation.rootInActiveWindow?.packageName?.toString()?.contains("deskclock") == true }
                await { node("你好，这是跨应用字幕测试。") != null }
                assertTrue(state { it.overlayVisible })
                // Translation-only: the source transcript must never be rendered over another app.
                assertNull(node("Hello, this is an overlay test."))
                assertNull(node("The original is never shown."))
                screenshot(context, "overlay-over-clock.png")

                // Rolling box: four finalized captions plus the live partial line keep only the newest lines.
                instrumentation.runOnMainSync {
                    caption(assembler, 3, "Sentence three.", "第三句译文。")
                    caption(assembler, 4, "Sentence four.", "第四句译文。")
                }
                await { node("第四句译文。") != null }
                awaitPartial(assembler, "第五句实时译文…")
                assertNull("Oldest caption must scroll out of the box", node("你好，这是跨应用字幕测试。"))
                assertNull(node("Hello, this is an overlay test."))
                val rollingBounds = requireNotNull(bounds())
                assertTrue("Overlay must stay a compact caption box", rollingBounds.height() < screenHeight / 4)
                screenshot(context, "overlay-rolling.png")

                // The original real-device complaint: a long translated sentence must not fill half the screen.
                val longTranslation = "这是一条很长的译文，用来验证悬浮窗在长句换行时仍然保持固定高度，不会像旧版本那样把窗口撑到半个屏幕，并且最新一行始终保持在框内可见。"
                instrumentation.runOnMainSync { caption(assembler, 5, "A very long sentence.", longTranslation) }
                await { node(longTranslation) != null }
                // Let the overlay finish a layout pass so the box scrolls to the newest line before the screenshot.
                Thread.sleep(400)
                val longBounds = requireNotNull(bounds())
                assertTrue("Long captions must stay inside the fixed caption box", longBounds.height() < screenHeight / 3)
                screenshot(context, "overlay-long.png")

                click("增大字幕字号"); await { store.overlayTextSize() == 22 }
                click("缩小字幕字号"); await { store.overlayTextSize() == 20 }
                val expanded = requireNotNull(bounds())
                click("收起字幕内容"); await { node("展开字幕内容") != null && (bounds()?.height() ?: Int.MAX_VALUE) < expanded.height() }
                assertNull(node(longTranslation))
                click("展开字幕内容"); await { node(longTranslation) != null }

                // Retry the gesture: expanding the panel can reposition the window after the
                // handle was measured, so each attempt re-reads the current handle bounds.
                await {
                    val before = store.overlayPosition()
                    val handle = node("拖动悬浮字幕") ?: return@await false
                    val handleRect = Rect().also { handle.getBoundsInScreen(it) }
                    val start = SystemClock.uptimeMillis()
                    val x = handleRect.centerX().toFloat(); val y = handleRect.centerY().toFloat()
                    fun inject(action: Int, at: Long, pointY: Float) {
                        val event = MotionEvent.obtain(start, at, action, x, pointY, 0)
                        automation.injectInputEvent(event, true); event.recycle()
                    }
                    inject(MotionEvent.ACTION_DOWN, start, y)
                    inject(MotionEvent.ACTION_MOVE, start + 80, y - 180)
                    inject(MotionEvent.ACTION_UP, start + 160, y - 180)
                    Thread.sleep(200)
                    store.overlayPosition().y < before.y
                }

                click("隐藏悬浮字幕，可从通知恢复")
                await { state { !it.overlayVisible && it.active } }
                await { context.getSystemService(NotificationManager::class.java).activeNotifications.any { n -> n.notification.actions?.any { it.title == "显示字幕" } == true } }
                val action = context.getSystemService(NotificationManager::class.java).activeNotifications
                    .flatMap { it.notification.actions?.toList().orEmpty() }.first { it.title == "显示字幕" }
                action.actionIntent.send()
                await { state { it.overlayVisible } && node(longTranslation) != null }

                automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
                await {
                    val b = bounds() ?: return@await false
                    if (node(longTranslation) == null) return@await false
                    val screen = automation.takeScreenshot() ?: return@await false
                    val fits = screen.width > screen.height && b.left >= 0 && b.top >= 0 && b.right <= screen.width && b.bottom <= screen.height
                    screen.recycle(); fits
                }
                screenshot(context, "overlay-landscape.png")
                context.startService(Intent(context, TranslationService::class.java).setAction(TranslationService.ACTION_STOP))
                await { state { !it.active && !it.overlayVisible } }
                await { node("拖动悬浮字幕") == null }
            }
        } finally {
            context.stopService(Intent(context, TranslationService::class.java))
            automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            store.clearKey(); store.save(previous, previousKey)
            store.setOverlayEnabled(enabled); store.setOverlayTextSize(size); store.setOverlayPosition(position)
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
        }
    }
}
