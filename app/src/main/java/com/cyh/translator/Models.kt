package com.cyh.translator

import android.content.Context
import org.json.JSONObject

const val DEFAULT_ENDPOINT = "wss://maas.qianwenaiapi.com/api-ws/v1/realtime"
const val DEFAULT_MODEL = "qwen3.8-livetranslate-flash-realtime"

data class Language(val code: String, val label: String, val audio: Boolean)
object Languages {
    fun load(context: Context): List<Language> {
        val json = context.assets.open("translation_languages.json").bufferedReader().use { it.readText() }
        val array = JSONObject(json).getJSONArray("languages")
        return (0 until array.length()).map {
            val entry = array.getJSONObject(it)
            Language(entry.getString("code"), entry.getString("label"), entry.getBoolean("audio"))
        }
    }
}

enum class InputMode(val label: String) { MICROPHONE("面对面 · 麦克风"), SYSTEM("收听 · 手机声音"), DUAL("双向 · 手机声音 + 麦克风") }
data class ChannelConfig(val id: String, val target: String) {
    val label get() = if (id == "listen") "收听" else "发言"
}
data class AppSettings(
    val endpoint: String = DEFAULT_ENDPOINT,
    val model: String = DEFAULT_MODEL,
    val mode: InputMode = InputMode.MICROPHONE,
    val listen: ChannelConfig = ChannelConfig("listen", "zh"),
    val speak: ChannelConfig = ChannelConfig("speak", "en")
) {
    fun channels(): List<ChannelConfig> = when (mode) {
        InputMode.MICROPHONE -> listOf(speak)
        InputMode.SYSTEM -> listOf(listen)
        InputMode.DUAL -> listOf(listen, speak)
    }
    fun validate(languages: List<Language>) {
        Protocol.socketUrl(endpoint, model)
        channels().forEach { c ->
            require(languages.any { it.code == c.target }) { "请选择${c.label}目标语言" }
        }
    }
}

data class Caption(
    val id: String, val channel: String, val createdAt: Long = System.currentTimeMillis(),
    val source: String = "", val translation: String = "", val status: String = "translating"
)
data class SessionSnapshot(
    val active: Boolean = false, val sessionId: String = "", val startedAt: Long = 0,
    val status: String = "准备好，就开始吧", val error: String? = null,
    val muted: Boolean = false, val states: Map<String, String> = emptyMap(),
    val sources: Map<String, String> = emptyMap(), val translations: Map<String, String> = emptyMap(),
    val levels: Map<String, Int> = emptyMap(), val captions: List<Caption> = emptyList(),
    val overlayVisible: Boolean = false, val overlayError: String? = null
)

/** Main-thread state only. Activities subscribe only while visible. */
object RuntimeState {
    var snapshot = SessionSnapshot(); private set
    private val listeners = mutableSetOf<(SessionSnapshot) -> Unit>()
    fun publish(value: SessionSnapshot) { snapshot = value; listeners.toList().forEach { it(value) } }
    fun observe(listener: (SessionSnapshot) -> Unit) { listeners.add(listener); listener(snapshot) }
    fun remove(listener: (SessionSnapshot) -> Unit) { listeners.remove(listener) }
}
