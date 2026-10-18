package com.cyh.translator

import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Every protocol transition is serialized; audio capture never blocks the socket. */
class RealtimeChannel(
    private val settings: AppSettings, val config: ChannelConfig, private val apiKey: String,
    private val onState: (String) -> Unit,
    private val onReady: () -> Unit,
    onCaption: (Caption) -> Unit,
    onPartial: (String?, String?) -> Unit,
    private val onFatal: (String) -> Unit,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val assembler = CaptionAssembler(config.id, onCaption, onPartial)
    private var socket: WebSocket? = null
    private var generation = 0
    private var ready = false
    private var stopped = false
    private var retry = 0
    private var lastAudioAt = 0L
    private var timeout: ScheduledFuture<*>? = null
    private val queuedFrames = AtomicInteger()
    @Volatile var muted = false

    fun start() = execute {
        connect()
        executor.scheduleWithFixedDelay({
            if (!stopped && ready && System.nanoTime() - lastAudioAt > TimeUnit.SECONDS.toNanos(20)) sendPcm(ByteArray(3200))
        }, 5, 5, TimeUnit.SECONDS)
    }
    private fun execute(action: () -> Unit) {
        if (!executor.isShutdown) runCatching { executor.execute(action) }
    }
    private fun connect() {
        if (stopped) return
        val token = ++generation
        ready = false
        onState(if (retry == 0) "正在连接" else "正在重连 · 第 $retry 次")
        val request = Request.Builder().url(Protocol.socketUrl(settings.endpoint, settings.model))
            .header("Authorization", "Bearer $apiKey").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = execute {
                if (token != generation || stopped) { webSocket.cancel(); return@execute }
                webSocket.send(Protocol.configure(config).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = execute {
                if (token != generation || stopped) return@execute
                try {
                    val event = JSONObject(text)
                    when (event.optString("type")) {
                        "session.updated" -> {
                            Protocol.checkAccepted(event)
                            if (!ready) {
                                ready = true; retry = 0; lastAudioAt = System.nanoTime(); timeout?.cancel(false)
                                onState("正在翻译"); onReady()
                            }
                        }
                        "error" -> {
                            val error = event.optJSONObject("error") ?: event
                            val detail = error.optString("message", error.optString("code", "模型拒绝了请求"))
                            if (detail.contains("idle timeout", true) || error.optString("code") == "1007") reconnect()
                            else fatal(detail)
                        }
                        "session.finished" -> reconnect()
                        "response.audio.delta" -> Unit // Never decode or play unsolicited audio.
                        else -> if (ready) assembler.accept(event)
                    }
                } catch (e: Exception) { fatal(e.message ?: "同传响应格式错误") }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = execute {
                if (token == generation && !stopped) reconnect()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = execute {
                if (token != generation || stopped) return@execute
                when (response?.code) {
                    401, 403 -> fatal("API Key 无效或没有模型权限，请检查密钥与服务地域")
                    400, 404, 422 -> fatal("接口地址或模型不受支持，请检查设置")
                    else -> reconnect()
                }
            }
        })
        timeout?.cancel(false)
        timeout = executor.schedule({ if (token == generation && !ready && !stopped) reconnect() }, 25, TimeUnit.SECONDS)
    }
    private fun reconnect() {
        if (stopped) return
        generation++; ready = false; timeout?.cancel(false); socket?.cancel(); socket = null
        assembler.reset()
        val delay = minOf(15, 1 shl minOf(retry, 4)); retry++
        onState("连接中断 · ${delay}秒后重连")
        executor.schedule({ connect() }, delay.toLong(), TimeUnit.SECONDS)
    }
    fun audio(pcm: ByteArray) {
        if (queuedFrames.incrementAndGet() > 10) { queuedFrames.decrementAndGet(); return }
        if (executor.isShutdown) { queuedFrames.decrementAndGet(); return }
        execute {
            queuedFrames.decrementAndGet()
            if (ready && !stopped && !muted) sendPcm(pcm)
        }
    }
    private fun sendPcm(pcm: ByteArray) {
        val current = socket ?: return
        if (current.queueSize() > 128_000) { reconnect(); return }
        val message = Protocol.event("input_audio_buffer.append").put("audio", pcm.toByteString().base64())
        if (current.send(message.toString())) lastAudioAt = System.nanoTime() else reconnect()
    }
    private fun fatal(message: String) {
        if (stopped) return
        onFatal(message.replace(apiKey, "[密钥]").take(300))
        shutdown()
    }
    fun stop() = execute { shutdown() }
    private fun shutdown() {
        if (stopped) return
        stopped = true; ready = false; generation++; timeout?.cancel(false)
        assembler.reset()
        socket?.send(Protocol.event("session.finish").toString())
        socket?.close(1000, "Client stopped")
        val closing = socket
        client.dispatcher.executorService.execute {
            try { Thread.sleep(1200) } catch (_: InterruptedException) { }
            closing?.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
        socket = null; executor.shutdownNow()
    }
}
