package com.cyh.translator

import org.junit.Assert.*
import org.junit.Test

class SubtitlePresentationTest {
    @Test fun livePartialIsDisplayedBeforeFinalCaption() {
        val snapshot = SessionSnapshot(active = true, sources = mapOf("listen" to "Hello"), translations = mapOf("listen" to "你好"))
        assertEquals(SubtitleText("Hello", "你好"), SubtitlePresentation.text(snapshot, "listen"))
    }
    @Test fun finalCaptionRemainsVisibleAfterPartialsAreCleared() {
        val snapshot = SessionSnapshot(active = true, captions = listOf(Caption("1", "listen", source = "Hello", translation = "你好", status = "translated")))
        assertEquals(SubtitleText("Hello", "你好"), SubtitlePresentation.text(snapshot, "listen"))
    }
    @Test fun translationWithoutSourceIsStillVisible() {
        val snapshot = SessionSnapshot(active = true, captions = listOf(Caption("1", "listen", translation = "先到的译文", status = "awaiting_source")))
        assertEquals("先到的译文", SubtitlePresentation.text(snapshot, "listen").translation)
    }
    @Test fun channelsDoNotShowEachOthersCaptions() {
        val snapshot = SessionSnapshot(active = true, captions = listOf(Caption("1", "speak", source = "旧原文", translation = "发言译文")))
        assertEquals("正在聆听…", SubtitlePresentation.text(snapshot, "listen").translation)
    }
    @Test fun newSourceDoesNotReuseOldTranslation() {
        val snapshot = SessionSnapshot(active = true, sources = mapOf("listen" to "New"),
            captions = listOf(Caption("1", "listen", translation = "旧译文")))
        assertEquals("正在翻译…", SubtitlePresentation.text(snapshot, "listen").translation)
    }
    @Test fun offscreenPositionIsClampedToVisibleArea() {
        assertEquals(8 to 650, OverlayGeometry.place(OverlayArea(8, 30, 344, 720), 320, 100, OverlayPosition(-10f, 20f)))
    }
    @Test fun normalizedPositionSurvivesRotationAndLargeWindow() {
        val saved = OverlayGeometry.remember(OverlayArea(8, 30, 344, 720), 320, 100, 32, 650)
        assertEquals(OverlayPosition(1f, 1f), saved)
        assertEquals(208 to 200, OverlayGeometry.place(OverlayArea(8, 20, 720, 280), 520, 100, saved))
        assertEquals(8 to 20, OverlayGeometry.place(OverlayArea(8, 20, 100, 80), 500, 200, saved))
    }
    @Test fun corruptNonFinitePositionFallsBackSafely() {
        assertEquals(60 to 164, OverlayGeometry.place(OverlayArea(10, 20, 200, 300), 100, 100, OverlayPosition(Float.NaN, Float.POSITIVE_INFINITY)))
    }
    @Test fun waitingPlaceholdersAreNotReportedAsContent() {
        assertEquals(emptyList<SubtitleLine>(), SubtitlePresentation.rolling(SessionSnapshot(active = true), listOf("listen")))
    }
    @Test fun overlayLinesAreTranslationOnlyAndKeepNewestAtBottom() {
        val snapshot = SessionSnapshot(active = true,
            sources = mapOf("listen" to "こんにちは"), translations = mapOf("listen" to "实时译文"),
            captions = listOf(
                Caption("1", "listen", source = "一句原文", translation = "第一句译文", status = "translated"),
                Caption("2", "listen", source = "二句原文", translation = "第二句译文", status = "translated")))
        assertEquals(
            listOf(SubtitleLine("第一句译文", "listen", false), SubtitleLine("第二句译文", "listen", false), SubtitleLine("实时译文", "listen", true)),
            SubtitlePresentation.rolling(snapshot, listOf("listen")))
    }
    @Test fun overlayKeepsOnlyTheNewestLinesWithinTheBox() {
        val captions = (1..6).map { Caption(it.toString(), "listen", source = "原文$it", translation = "译文$it", status = "translated") }
        val lines = SubtitlePresentation.rolling(SessionSnapshot(active = true, captions = captions), listOf("listen"))
        assertEquals(listOf("译文4", "译文5", "译文6"), lines.map { it.text })
        assertEquals(SubtitlePresentation.ROLLING_LINES, lines.size)
    }
    @Test fun overlayIgnoresOtherChannelsAndSourceOnlyCaptions() {
        val snapshot = SessionSnapshot(active = true, captions = listOf(
            Caption("1", "speak", source = "发言原文", translation = "发言译文", status = "translated"),
            Caption("2", "listen", source = "只有原文")))
        assertEquals(emptyList<SubtitleLine>(), SubtitlePresentation.rolling(snapshot, listOf("listen")))
        assertEquals(listOf("发言译文"), SubtitlePresentation.rolling(snapshot, listOf("listen", "speak")).map { it.text })
    }
    @Test fun overlayNeverFallsBackToPlaceholders() {
        val snapshot = SessionSnapshot(active = true, sources = mapOf("listen" to "还在识别"))
        assertEquals(emptyList<SubtitleLine>(), SubtitlePresentation.rolling(snapshot, listOf("listen")))
    }
    @Test fun reconnectAttemptAfterBackoffStillReadsAsReconnecting() {
        assertEquals("重连中", SubtitlePresentation.overlayState(false, listOf("连接中断 · 4秒后重连")))
        // RealtimeChannel sends "正在重连 · 第 N 次" while the backoff expires and the next socket opens.
        assertEquals("重连中", SubtitlePresentation.overlayState(false, listOf("正在重连 · 第 2 次", "正在翻译")))
        assertEquals("正在连接", SubtitlePresentation.overlayState(false, listOf("正在连接")))
        assertEquals("正在翻译", SubtitlePresentation.overlayState(false, listOf("正在翻译")))
        assertEquals("已静音", SubtitlePresentation.overlayState(true, listOf("正在重连 · 第 1 次")))
    }
    @Test fun overlayLinePrefixAppearsOnlyForDualChannels() {
        val listen = SubtitleLine("你好", "listen", false)
        val speak = SubtitleLine("Hello", "speak", true)
        assertEquals("你好", SubtitlePresentation.overlayLineText(listen, false))
        assertEquals("收听｜你好", SubtitlePresentation.overlayLineText(listen, true))
        assertEquals("发言｜Hello", SubtitlePresentation.overlayLineText(speak, true))
    }
    @Test fun finishedCaptionAndNotYetClearedPartialAreNotDuplicated() {
        val snapshot = SessionSnapshot(active = true,
            translations = mapOf("speak" to "同一句译文"),
            captions = listOf(Caption("1", "speak", source = "原文", translation = "同一句译文", status = "translated")))
        assertEquals(listOf("同一句译文"), SubtitlePresentation.rolling(snapshot, listOf("speak")).map { it.text })
    }
}
