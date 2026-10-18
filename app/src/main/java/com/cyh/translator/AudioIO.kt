package com.cyh.translator

import android.annotation.SuppressLint
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.projection.MediaProjection
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

class AudioCapture(
    private val projection: MediaProjection?, private val system: Boolean,
    private val onFrame: (ByteArray) -> Unit, private val onLevel: (Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val lock = Any()
    private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "capture-${if (system) "system" else "mic"}") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var local: AudioRecord? = null
            var echo: AcousticEchoCanceler? = null
            try {
                val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
                val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(minimum > 0) { "设备不支持 16 kHz 录音" }
                val builder = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(minimum * 2, 12800))
                if (system) {
                    val config = AudioPlaybackCaptureConfiguration.Builder(requireNotNull(projection))
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(Process.myUid()).build()
                    builder.setAudioPlaybackCaptureConfig(config)
                } else builder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                local = builder.build()
                check(local.state == AudioRecord.STATE_INITIALIZED) { "音频采集初始化失败" }
                if (!system && AcousticEchoCanceler.isAvailable()) {
                    echo = AcousticEchoCanceler.create(local.audioSessionId)?.apply { enabled = true }
                }
                synchronized(lock) {
                    if (!running.get()) return@thread
                    recorder = local
                    local.startRecording()
                }
                check(local.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法录音，请检查麦克风权限或是否被其他应用占用" }
                val buffer = ByteArray(3200)
                var frames = 0
                while (running.get()) {
                    val count = local.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (!running.get()) break
                    check(count >= 0) { "音频设备中断（$count），请重新开始同传" }
                    if (count == 0) continue
                    onFrame(buffer.copyOf(count - count % 2))
                    if (++frames % 3 == 0) {
                        var sum = 0.0
                        for (i in 0 until count - 1 step 2) {
                            val sample = ((buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)).toShort().toDouble() / 32768
                            sum += sample * sample
                        }
                        onLevel((sqrt(sum / maxOf(1, count / 2)) * 500).toInt().coerceIn(0, 100))
                    }
                }
            } catch (e: Exception) {
                if (running.get()) onError(e.message ?: "音频采集失败")
            } finally {
                running.set(false)
                synchronized(lock) { recorder = null }
                runCatching { local?.stop() }; echo?.release(); local?.release()
            }
        }
    }
    fun stop() {
        running.set(false)
        synchronized(lock) { runCatching { recorder?.stop() } }
    }
}

