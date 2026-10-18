package com.cyh.translator

import kotlin.math.roundToInt

data class SubtitleText(val source: String, val translation: String)

/** One line inside the floating overlay: translation only, never the source transcript. */
data class SubtitleLine(val text: String, val channel: String, val partial: Boolean)

/** The main screen and the overlay consume the same live/final caption state. */
object SubtitlePresentation {
    const val ROLLING_LINES = 3

    fun text(snapshot: SessionSnapshot, channel: String): SubtitleText {
        val latest = snapshot.captions.lastOrNull { it.channel == channel }
        val source = snapshot.sources[channel].orEmpty()
        val translation = snapshot.translations[channel].orEmpty()
        return SubtitleText(
            source.ifBlank { latest?.source?.ifBlank { "等待原文" } ?: "原文会显示在这里" },
            translation.ifBlank {
                if (source.isNotBlank()) "正在翻译…"
                else latest?.translation?.ifBlank { if (latest.status == "interrupted") "此片段已中断" else "等待译文…" }
                    ?: if (snapshot.active) "正在聆听…" else "等待开始同传"
            })
    }

    /** Overlay captions stay translation-only: last finalized lines first, then live partial lines.
     *  The overlay never renders source text; originals remain available in session history. */
    fun rolling(snapshot: SessionSnapshot, channels: List<String>, maxLines: Int = ROLLING_LINES): List<SubtitleLine> {
        if (maxLines <= 0) return emptyList()
        val lines = ArrayList<SubtitleLine>()
        snapshot.captions.asSequence()
            .filter { it.channel in channels && it.translation.isNotBlank() }
            .forEach { lines.add(SubtitleLine(it.translation, it.channel, false)) }
        channels.forEach { id ->
            val partial = snapshot.translations[id]?.takeIf { it.isNotBlank() } ?: return@forEach
            // A finished caption and its not-yet-cleared partial can briefly share the same text.
            val previous = lines.lastOrNull()
            if (previous != null && previous.channel == id && previous.text == partial) return@forEach
            lines.add(SubtitleLine(partial, id, true))
        }
        return lines.takeLast(maxLines)
    }

    /** Compact state shown in the overlay title. The reconnect attempts after a backoff must read as reconnecting. */
    fun overlayState(muted: Boolean, states: List<String>): String = when {
        muted -> "已静音"
        states.any { it.startsWith("连接中断") || it.startsWith("正在重连") } -> "重连中"
        states.any { it == "正在连接" } -> "正在连接"
        states.any { it == "正在翻译" } -> "正在翻译"
        else -> "正在连接"
    }

    /** Prefix the channel only while both directions are shown. */
    fun overlayLineText(line: SubtitleLine, showChannel: Boolean): String =
        if (!showChannel) line.text else (if (line.channel == "listen") "收听｜" else "发言｜") + line.text
}

data class OverlayPosition(val x: Float = 0.5f, val y: Float = 0.72f)
data class OverlayArea(val left: Int, val top: Int, val width: Int, val height: Int)

/** Save relative travel, so an old portrait position remains reachable in landscape. */
object OverlayGeometry {
    fun place(area: OverlayArea, width: Int, height: Int, position: OverlayPosition): Pair<Int, Int> {
        fun fraction(value: Float, fallback: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
        return (area.left + (maxOf(0, area.width - width) * fraction(position.x, 0.5f)).roundToInt()) to
            (area.top + (maxOf(0, area.height - height) * fraction(position.y, 0.72f)).roundToInt())
    }
    fun remember(area: OverlayArea, width: Int, height: Int, x: Int, y: Int): OverlayPosition {
        val travelX = maxOf(0, area.width - width)
        val travelY = maxOf(0, area.height - height)
        return OverlayPosition(if (travelX == 0) 0.5f else ((x - area.left).toFloat() / travelX).coerceIn(0f, 1f),
            if (travelY == 0) 0.72f else ((y - area.top).toFloat() / travelY).coerceIn(0f, 1f))
    }
}
