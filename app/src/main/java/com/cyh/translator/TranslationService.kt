package com.cyh.translator

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TranslationService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val channels = ConcurrentHashMap<String, RealtimeChannel>()
    private val captures = mutableMapOf<String, AudioCapture>()
    private lateinit var overlay: SubtitleOverlay
    private val overlayObserver: (SessionSnapshot) -> Unit = { overlay.render(it) }
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentId = ""
    private var ending = false
    private lateinit var history: HistoryStore
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { if (!ending && RuntimeState.snapshot.active) fail("系统声音授权已结束，请重新开始并授权") }
    }
    override fun onCreate() {
        super.onCreate(); history = HistoryStore.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("translation", "正在同传", NotificationManager.IMPORTANCE_LOW).apply {
                description = "翻译期间显示运行状态，可以随时停止"; setSound(null, null)
            })
        overlay = SubtitleOverlay(this, onStatus = { visible, error ->
            val snapshot = RuntimeState.snapshot
            if (snapshot.overlayVisible != visible || snapshot.overlayError != error) {
                RuntimeState.publish(snapshot.copy(overlayVisible = visible, overlayError = error)); notifyState()
            }
        }, onHide = {
            SettingsStore(this).setOverlayEnabled(false); overlay.retry(); notifyState()
        })
        RuntimeState.observe(overlayObserver)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!RuntimeState.snapshot.active) startSession(intent)
            ACTION_STOP -> endSession()
            ACTION_MUTE -> {
                val snapshot = RuntimeState.snapshot
                if (snapshot.active) {
                    val muted = !snapshot.muted
                    channels["speak"]?.muted = muted
                    RuntimeState.publish(snapshot.copy(muted = muted)); notifyState()
                }
            }
            ACTION_TOGGLE_OVERLAY -> if (RuntimeState.snapshot.active) {
                SettingsStore(this).setOverlayEnabled(!RuntimeState.snapshot.overlayVisible)
                overlay.retry(); notifyState()
            } else stopSelf()
            ACTION_REFRESH_OVERLAY -> if (RuntimeState.snapshot.active) { overlay.retry(); notifyState() } else stopSelf()
            else -> if (!RuntimeState.snapshot.active) stopSelf()
        }
        // A killed process must request fresh microphone/projection consent from the activity.
        return START_NOT_STICKY
    }
    private fun startSession(intent: Intent) {
        ending = false
        try {
            val store = SettingsStore(this)
            val settings = store.load().also { it.validate(Languages.load(this)) }
            val apiKey = store.apiKey().also { require(it.isNotBlank()) { "请先配置 API Key" } }
            var types = 0
            if (settings.mode != InputMode.SYSTEM && Build.VERSION.SDK_INT >= 30) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (settings.mode != InputMode.MICROPHONE) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(1001, notification("正在连接同传服务"), types)
            if (settings.mode != InputMode.MICROPHONE) {
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>("projectionData") ?: error("请先授权系统声音采集")
                val code = intent.getIntExtra("projectionCode", Activity.RESULT_CANCELED)
                require(code == Activity.RESULT_OK) { "系统声音采集未授权" }
                projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
                    .also { it.registerCallback(projectionCallback, main) }
            }
            currentId = UUID.randomUUID().toString()
            val sessionId = currentId
            val started = System.currentTimeMillis()
            RuntimeState.publish(SessionSnapshot(true, sessionId, started, "正在连接同传服务"))
            history.begin(sessionId, started, settings.mode.label + " · " + settings.channels().joinToString(" / ") { it.target })
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "translator:session").apply {
                setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L)
            }
            settings.channels().forEach { config ->
                val id = config.id
                fun dispatch(action: () -> Unit) { main.post { if (currentId == sessionId && !ending) action() } }
                val channel = RealtimeChannel(settings, config, apiKey,
                    onState = { state -> dispatch {
                        val snapshot = RuntimeState.snapshot
                        RuntimeState.publish(snapshot.copy(states = snapshot.states + (id to state), status = "同传进行中"))
                        notifyState()
                    } },
                    onReady = { dispatch {
                        if (!captures.containsKey(id)) {
                            captures[id] = AudioCapture(projection, id == "listen",
                                onFrame = { pcm -> channels[id]?.audio(pcm) },
                                onLevel = { level -> dispatch {
                                    val snapshot = RuntimeState.snapshot
                                    RuntimeState.publish(snapshot.copy(levels = snapshot.levels + (id to level)))
                                } }, onError = { error -> dispatch { fail("${config.label}：$error") } }).also { it.start() }
                        }
                    } },
                    onCaption = { caption -> main.post {
                        // Finalize pending captions even while stop is releasing the socket.
                        history.put(sessionId, caption)
                        if (RuntimeState.snapshot.sessionId == sessionId) {
                            val snapshot = RuntimeState.snapshot
                            val rows = snapshot.captions.toMutableList()
                            val position = rows.indexOfFirst { it.id == caption.id }
                            if (position < 0) rows.add(caption) else rows[position] = caption
                            RuntimeState.publish(snapshot.copy(captions = rows.takeLast(100)))
                        }
                    } },
                    onPartial = { source, translation -> dispatch {
                        val snapshot = RuntimeState.snapshot
                        RuntimeState.publish(snapshot.copy(
                            sources = if (source == null) snapshot.sources else snapshot.sources + (id to source),
                            translations = if (translation == null) snapshot.translations else snapshot.translations + (id to translation)))
                    } },
                    onFatal = { error -> dispatch { fail("${config.label}：$error") } })
                channels[id] = channel
                channel.start()
            }
        } catch (e: Exception) { fail(e.message ?: "无法开始同传，请检查权限与配置") }
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TranslationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val mute = PendingIntent.getService(this, 2, Intent(this, TranslationService::class.java).setAction(ACTION_MUTE), PendingIntent.FLAG_IMMUTABLE)
        val showOverlay = if (Settings.canDrawOverlays(this))
            PendingIntent.getService(this, 3, Intent(this, TranslationService::class.java).setAction(ACTION_TOGGLE_OVERLAY), PendingIntent.FLAG_IMMUTABLE)
        else PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java)
            .setAction("com.cyh.translator.OVERLAY_PERMISSION").putExtra("requestOverlay", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, "translation").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("同传翻译").setContentText(text).setContentIntent(open).setOngoing(true)
            .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "停止同传", stop).build())
            .addAction(Notification.Action.Builder(null, if (RuntimeState.snapshot.overlayVisible) "隐藏字幕" else "显示字幕", showOverlay).build())
        if (channels.containsKey("speak")) builder.addAction(Notification.Action.Builder(null,
            if (RuntimeState.snapshot.muted) "恢复麦克风" else "静音麦克风", mute).build())
        return builder.build()
    }
    private fun notifyState() {
        val snapshot = RuntimeState.snapshot
        if (snapshot.active) getSystemService(NotificationManager::class.java).notify(1001,
            notification(snapshot.states.entries.joinToString(" · ") { "${if (it.key == "listen") "收听" else "发言"} ${it.value}" }))
    }
    private fun fail(message: String) { endSession(message) }
    private fun endSession(error: String? = null) {
        if (ending) return
        ending = true
        captures.values.forEach { it.stop() }; captures.clear()
        channels.values.forEach { it.stop() }; channels.clear()
        projection?.unregisterCallback(projectionCallback); projection?.stop(); projection = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        val snapshot = RuntimeState.snapshot
        RuntimeState.publish(snapshot.copy(active = false, status = if (error == null) "本次同传已结束" else "同传已停止",
            error = error, states = emptyMap(), sources = emptyMap(), translations = emptyMap(), levels = emptyMap(), muted = false,
            overlayVisible = false, overlayError = null))
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        endSession(); RuntimeState.remove(overlayObserver); overlay.close(); super.onDestroy()
    }
    companion object {
        const val ACTION_START = "com.cyh.translator.START"
        const val ACTION_STOP = "com.cyh.translator.STOP"
        const val ACTION_MUTE = "com.cyh.translator.MUTE"
        const val ACTION_TOGGLE_OVERLAY = "com.cyh.translator.TOGGLE_OVERLAY"
        const val ACTION_REFRESH_OVERLAY = "com.cyh.translator.REFRESH_OVERLAY"
    }
}
