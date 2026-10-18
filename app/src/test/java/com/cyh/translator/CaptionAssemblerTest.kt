package com.cyh.translator

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CaptionAssemblerTest {
    private val rows = linkedMapOf<String, Caption>()
    private var partialSource = ""
    private var partialTranslation = ""
    private val assembler = CaptionAssembler("speak", { rows[it.id] = it }, { s, t ->
        if (s != null) partialSource = s
        if (t != null) partialTranslation = t
    })
    private fun source(id: String, value: String) = assembler.accept(JSONObject().put("type", "conversation.item.input_audio_transcription.completed").put("item_id", id).put("transcript", value))
    private fun translated(id: String, value: String) = assembler.accept(JSONObject().put("type", "response.text.done").put("response_id", id).put("text", value))
    @Test fun pairsSourceThenTranslation() {
        source("s1", "你好"); translated("r1", "Hello")
        assertEquals(1, rows.size); assertEquals("你好", rows.values.first().source); assertEquals("Hello", rows.values.first().translation)
        assertEquals("translated", rows.values.first().status)
    }
    @Test fun pairsTranslationBeforeSourceWithoutDuplicate() {
        translated("r1", "Hello"); val id = rows.keys.first(); source("s1", "你好")
        assertEquals(listOf(id), rows.keys.toList()); assertEquals("你好", rows[id]!!.source)
    }
    @Test fun duplicateDoneEventsProduceOneCaption() {
        source("s1", "你好"); source("s1", "你好"); translated("r1", "Hello"); translated("r1", "Hello")
        assembler.accept(JSONObject("{\"type\":\"response.done\",\"response\":{\"id\":\"r1\",\"status\":\"completed\",\"output\":[{\"content\":[{\"text\":\"Hello\"}]}]}}"))
        assertEquals(1, rows.size)
    }
    @Test fun multipleUtterancesAreFifo() {
        source("s1", "一"); source("s2", "二"); translated("r1", "One"); translated("r2", "Two")
        assertEquals(listOf("一:One", "二:Two"), rows.values.map { "${it.source}:${it.translation}" })
    }
    @Test fun reconnectDoesNotPairOldSourceWithNewTranslation() {
        source("s1", "旧句子"); assembler.reset(); translated("r1", "New"); source("s1", "新句子")
        assertEquals(2, rows.size); assertEquals("interrupted", rows.values.first().status)
        assertEquals("新句子", rows.values.last().source); assertEquals("New", rows.values.last().translation)
    }
    @Test fun resetKeepsUnpairedTranslationVisible() {
        translated("r1", "Hello"); assembler.reset(); source("s1", "下句话")
        assertEquals(2, rows.size); assertEquals("source_missing", rows.values.first().status)
    }
    @Test fun deltasAndTranscriptFallbackAreSupported() {
        assembler.accept(JSONObject("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"s1\",\"delta\":\"你\"}"))
        assembler.accept(JSONObject("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"s1\",\"delta\":\"好\"}"))
        assertEquals("你好", partialSource)
        assembler.accept(JSONObject("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"s1\"}"))
        assembler.accept(JSONObject("{\"type\":\"response.audio_transcript.delta\",\"response_id\":\"r1\",\"delta\":\"Hel\"}"))
        assembler.accept(JSONObject("{\"type\":\"response.audio_transcript.delta\",\"response_id\":\"r1\",\"delta\":\"lo\"}"))
        assertEquals("Hello", partialTranslation)
        assembler.accept(JSONObject("{\"type\":\"response.audio_transcript.done\",\"response_id\":\"r1\",\"transcript\":\"Hello\"}"))
        assertEquals("Hello", rows.values.first().translation); assertEquals("", partialTranslation)
    }
    @Test fun pendingSourcesAreBoundedAndMarkedInterrupted() {
        repeat(40) { source("s$it", "$it") }
        assertEquals(8, rows.values.count { it.status == "interrupted" })
    }
    @Test fun responseDoneCanSupplyFinalText() {
        source("s1", "你好")
        assembler.accept(JSONObject("{\"type\":\"response.done\",\"response\":{\"id\":\"r1\",\"status\":\"completed\",\"output\":[{\"content\":[{\"transcript\":\"Hello\"}]}]}}"))
        assertEquals("Hello", rows.values.first().translation)
    }
    @Test fun emptyItemIdsDoNotDiscardLaterUtterances() {
        source("", "第一句"); translated("r1", "First")
        source("", "第二句"); translated("r2", "Second")
        assertEquals(listOf("第一句", "第二句"), rows.values.map { it.source })
    }
}
