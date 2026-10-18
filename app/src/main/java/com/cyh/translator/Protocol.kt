package com.cyh.translator

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object Protocol {
    fun socketUrl(endpoint: String, model: String): String {
        require(endpoint.startsWith("wss://")) { "接口地址必须使用 wss:// 加密连接" }
        require(model.isNotBlank() && !model.any { it.isWhitespace() }) { "请填写有效模型名称" }
        val url = ("https://" + endpoint.removePrefix("wss://")).toHttpUrlOrNull()
            ?: error("接口地址格式不正确")
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "接口地址不能包含密码或片段" }
        return url.newBuilder().setQueryParameter("model", model).build().toString().replaceFirst("https://", "wss://")
    }
    fun event(type: String) = JSONObject().put("event_id", "event_${UUID.randomUUID()}").put("type", type)
    fun configure(config: ChannelConfig): JSONObject {
        val session = JSONObject().put("output_modalities", JSONArray(listOf("text")))
            .put("translation", JSONObject().put("language", config.target))
        return event("session.update").put("session", session)
    }
    fun checkAccepted(event: JSONObject) {
        val session = event.optJSONObject("session") ?: JSONObject()
        session.optJSONArray("output_modalities")?.let { modes ->
            val actual = (0 until modes.length()).map { modes.optString(it) }.toSet()
            require(actual == setOf("text")) { "模型未接受纯字幕输出模式" }
        }
    }
    fun text(event: JSONObject, preferred: String): String {
        for (key in listOf(preferred, "text", "transcript", "delta")) {
            (event.opt(key) as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }
}

/** Matches ASR and translation finals in FIFO order, as in the desktop engine.
 * Server response IDs are scoped to one connection and never reused after reset. */
class CaptionAssembler(
    private val channel: String,
    private val onCaption: (Caption) -> Unit,
    private val onPartial: (String?, String?) -> Unit
) {
    private val sources = linkedMapOf<String, String>()
    private val translations = linkedMapOf<String, String>()
    private val seenSources = linkedSetOf<String>()
    private val completed = linkedSetOf<String>()
    private val pendingSources = ArrayDeque<Caption>()
    private val orphanTranslations = ArrayDeque<Caption>()

    fun accept(event: JSONObject) {
        val type = event.optString("type")
        val item = event.optString("item_id").ifBlank { "current" }
        val response = event.optString("response_id").ifBlank { event.optString("item_id") }
        when (type) {
            "conversation.item.input_audio_transcription.delta" -> {
                val text = (sources[item] ?: "") + Protocol.text(event, "delta")
                sources[item] = text; trim(sources); onPartial(text, null)
            }
            "conversation.item.input_audio_transcription.text" -> {
                val text = Protocol.text(event, "text") + event.optString("stash")
                sources[item] = text; trim(sources); onPartial(text, null)
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val text = Protocol.text(event, "transcript").ifBlank { sources[item] ?: "" }
                sources.remove(item)
                if (text.isNotBlank() && (item == "current" || seenSources.add(item))) {
                    trim(seenSources)
                    val orphan = orphanTranslations.removeFirstOrNull()
                    val caption = orphan?.copy(source = text, status = "translated")
                        ?: Caption(UUID.randomUUID().toString(), channel, source = text)
                    if (orphan == null) pendingSources.addLast(caption)
                    onCaption(caption); onPartial("", null)
                    while (pendingSources.size > 32) onCaption(pendingSources.removeFirst().copy(status = "interrupted"))
                }
            }
            "response.text.delta", "response.audio_transcript.delta" -> {
                if (response !in completed) {
                    val text = (translations[response] ?: "") + Protocol.text(event, "delta")
                    translations[response] = text; trim(translations); onPartial(null, text)
                }
            }
            "response.text.text", "response.audio_transcript.text" -> {
                if (response !in completed) {
                    val text = Protocol.text(event, "text") + event.optString("stash")
                    translations[response] = text; trim(translations); onPartial(null, text)
                }
            }
            "response.text.done", "response.audio_transcript.done" -> finish(response, Protocol.text(event, "text"))
            "response.done" -> {
                val result = event.optJSONObject("response") ?: return
                require(result.optString("status") !in listOf("failed", "cancelled", "incomplete")) { "同传响应未完成，请重新开始" }
                val parts = StringBuilder()
                val output = result.optJSONArray("output") ?: JSONArray()
                for (i in 0 until output.length()) {
                    val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) content.optJSONObject(j)?.let { parts.append(Protocol.text(it, "text")) }
                }
                finish(result.optString("id").ifBlank { response }, parts.toString())
            }
        }
    }
    private fun finish(key: String, value: String) {
        if (key.isNotEmpty() && key in completed) return
        val text = value.ifBlank { translations[key] ?: "" }
        if (text.isBlank()) return
        translations.remove(key)
        if (key.isNotEmpty()) { completed.add(key); trim(completed) }
        val source = pendingSources.removeFirstOrNull()
        val caption = source?.copy(translation = text, status = "translated")
            ?: Caption(UUID.randomUUID().toString(), channel, translation = text, status = "awaiting_source")
        if (source == null) orphanTranslations.addLast(caption)
        onCaption(caption); onPartial(null, "")
        while (orphanTranslations.size > 32) onCaption(orphanTranslations.removeFirst().copy(status = "source_missing"))
    }
    fun reset() {
        pendingSources.forEach { onCaption(it.copy(status = "interrupted")) }
        orphanTranslations.forEach { onCaption(it.copy(status = "source_missing")) }
        pendingSources.clear(); orphanTranslations.clear(); sources.clear(); translations.clear()
        seenSources.clear(); completed.clear(); onPartial("", "")
    }
    private fun <T> trim(set: MutableSet<T>) { while (set.size > 128) set.remove(set.first()) }
    private fun <K, V> trim(map: MutableMap<K, V>) { while (map.size > 128) map.remove(map.keys.first()) }
}
