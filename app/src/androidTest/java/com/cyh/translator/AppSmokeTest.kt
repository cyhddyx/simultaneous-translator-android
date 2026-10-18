package com.cyh.translator

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    private fun allViews(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { allViews(root.getChildAt(it)) } else emptyList()
    private fun find(activity: MainActivity, text: String): TextView = allViews(activity.window.decorView)
        .filterIsInstance<TextView>().first { it.text.toString() == text }
    @Test fun navigationAndRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(find(activity, "开始同传").isEnabled)
                (find(activity, "设置") as Button).performClick()
                assertNotNull(find(activity, "同传服务"))
                assertNotNull(find(activity, "API Key"))
                assertFalse(allViews(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "播放译文语音" })
                assertFalse(allViews(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "悬浮窗同时显示原文" })
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertNotNull(find(activity, "同传服务"))
                (find(activity, "历史") as Button).performClick()
                assertNotNull(find(activity, "导出文本"))
                (find(activity, "同传") as Button).performClick()
                assertNotNull(find(activity, "听见世界 · 自在表达"))
            }
        }
    }
    @Test fun keyIsEncryptedAndSurvivesStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        val previous = store.load()
        val previousKey = store.apiKey()
        // Instrumentation runs only on the disposable test emulator, never a real user's install.
        val testKey = "instrumentation-test-key-not-a-real-credential"
        store.save(AppSettings(), testKey)
        try {
            assertEquals(testKey, SettingsStore(context).apiKey())
            val persisted = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("encryptedKey", "")!!
            assertFalse(persisted.contains(testKey)); assertTrue(persisted.contains(":"))
        } finally { store.clearKey(); store.save(previous, previousKey) }
    }
    @Test fun languageCapabilitiesMatchDesktop() {
        val languages = Languages.load(ApplicationProvider.getApplicationContext())
        assertEquals(60, languages.size); assertEquals(29, languages.count { it.audio })
        assertFalse(languages.first { it.code == "yue" }.audio)
    }
    @Test fun legacyVoiceSettingsCannotEnableAudioAndKeyIsPreserved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        val previous = store.load(); val previousKey = store.apiKey()
        try {
            store.save(previous.copy(speak = ChannelConfig("speak", "yue")), "migration-test-key")
            val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            preferences.edit().putBoolean("speak.audio", true).putBoolean("speak.clone", true).commit()
            val loaded = store.load()
            loaded.validate(Languages.load(context))
            val configuration = Protocol.configure(loaded.speak).getJSONObject("session")
            assertEquals("[\"text\"]", configuration.getJSONArray("output_modalities").toString())
            assertFalse(configuration.has("voice")); assertEquals("migration-test-key", store.apiKey())
            store.save(loaded)
            assertFalse(preferences.contains("speak.audio")); assertEquals("migration-test-key", store.apiKey())
        } finally { store.clearKey(); store.save(previous, previousKey) }
    }
    private fun grantAudio(context: Context) {
        val result = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
    }
    @Test fun microphoneProducesPcmFramesAndStops() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantAudio(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            val frames = CountDownLatch(3)
            val errors = LinkedBlockingQueue<String>()
            val capture = AudioCapture(null, false, { pcm -> if (pcm.size == 3200) frames.countDown() }, {}, { errors.offer(it) })
            try {
                capture.start()
                assertTrue("No 16 kHz PCM frames; errors=$errors", frames.await(10, TimeUnit.SECONDS))
                assertTrue(errors.isEmpty())
            } finally { capture.stop() }
        }
    }
    @Test fun foregroundServiceStartsMutesAndStopsWithoutExternalNetwork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        val previous = store.load(); val previousKey = store.apiKey()
        grantAudio(context)
        // A closed loopback port exercises reconnecting service lifecycle without contacting a provider.
        store.save(AppSettings(endpoint = "wss://127.0.0.1:9/realtime"), "instrumentation-only")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.startForegroundService(Intent(activity, TranslationService::class.java).setAction(TranslationService.ACTION_START)) }
                awaitState { it.active }
                scenario.onActivity { activity -> activity.startService(Intent(activity, TranslationService::class.java).setAction(TranslationService.ACTION_MUTE)) }
                awaitState { it.muted }
                scenario.onActivity { activity -> activity.startService(Intent(activity, TranslationService::class.java).setAction(TranslationService.ACTION_STOP)) }
                awaitState { !it.active }
                assertNull(RuntimeState.snapshot.error)
            }
        } finally {
            context.stopService(Intent(context, TranslationService::class.java))
            store.clearKey(); store.save(previous, previousKey)
        }
    }
    private fun awaitState(check: (SessionSnapshot) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var matches = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { matches = check(RuntimeState.snapshot) }
            if (matches) return
            Thread.sleep(25)
        }
        fail("Session state did not reach the expected condition")
    }
}
