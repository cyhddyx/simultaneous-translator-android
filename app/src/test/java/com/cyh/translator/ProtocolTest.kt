package com.cyh.translator

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private val text = ChannelConfig("speak", "en")
    @Test fun textConfigurationDoesNotRequestVoice() {
        val config = Protocol.configure(text).getJSONObject("session")
        assertEquals("[\"text\"]", config.getJSONArray("output_modalities").toString())
        assertEquals("en", config.getJSONObject("translation").getString("language"))
        assertFalse(config.has("enable_voice_clone")); assertFalse(config.has("input_audio_transcription"))
    }
    @Test fun bothChannelsRequestOnlyTextWithoutVoiceParameters() {
        listOf("listen", "speak").forEach { id ->
            val config = Protocol.configure(text.copy(id = id)).getJSONObject("session")
            assertEquals("[\"text\"]", config.getJSONArray("output_modalities").toString())
            listOf("voice", "enable_voice_clone", "voice_clone_options").forEach { assertFalse(config.has(it)) }
        }
    }
    @Test fun rejectsModalityMismatch() {
        assertThrows(IllegalArgumentException::class.java) { Protocol.checkAccepted(JSONObject("{\"session\":{\"output_modalities\":[\"text\",\"audio\"]}}")) }
    }
    @Test fun urlPreservesQueryAndReplacesModel() {
        assertEquals("wss://example.com/api?region=cn&model=new", Protocol.socketUrl("wss://example.com/api?region=cn&model=old", "new"))
    }
    @Test fun rejectsInsecureOrCredentialedEndpoints() {
        listOf("ws://example.com", "https://example.com", "wss://user:secret@example.com", "wss://example.com/#fragment").forEach {
            assertThrows(IllegalArgumentException::class.java) { Protocol.socketUrl(it, "model") }
        }
    }
    @Test fun formerlyTextOnlyLanguagesAreAvailableWithoutSpecialRestrictions() {
        val settings = AppSettings(speak = text.copy(target = "yue"))
        settings.validate(listOf(Language("yue", "粤语", false)))
    }
    @Test fun textAcknowledgementIsAccepted() {
        Protocol.checkAccepted(JSONObject().put("session", Protocol.configure(text).getJSONObject("session")))
    }
    @Test fun emptyTextFallsBackToTranscript() {
        assertEquals("字幕", Protocol.text(JSONObject("{\"text\":\"\",\"transcript\":\"字幕\"}"), "text"))
    }
}
