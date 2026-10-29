package com.music.bitchord.desktop

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song

/** Borrowed state only; no resolver, repository or player ownership here. */
internal data class DesktopPipState(
    val song: Song?,
    val playing: Boolean,
    val loading: Boolean,
    val buffering: Boolean,
    val durationMs: Long,
    val previous: Boolean,
    val next: Boolean,
    val controlsLocked: Boolean,
    val lyricsTrack: String?,
    val lyrics: List<LyricLine>?,
    val lyricsLoading: Boolean,
    val lyricsError: String?,
)

internal data class DesktopPipLayout(val padding: Float, val gap: Float, val titleSp: Float,
    val lyricSp: Float, val transport: Float, val sideBySide: Boolean) {
    companion object {
        fun at(width: Float, height: Float): DesktopPipLayout {
            val room = ((minOf(width, height) - 180f) / 420f).coerceIn(0f, 1f)
            return DesktopPipLayout(10 + room * 14, 4 + room * 8, 13 + room * 9,
                14 + room * 15, 34 + room * 14, width >= 640 && height >= 300 && width >= height * 1.2f)
        }
    }
}

internal data class DesktopPipLyricSelection(val index: Int, val instrumental: Boolean)

/** Same line/end semantics as fullscreen; the shared lyric clock supplies the adjusted position. */
internal fun desktopPipLyricSelection(lines: List<LyricLine>, adjustedPositionMs: Long): DesktopPipLyricSelection {
    val index = lines.indexOfLast { it.timeMs <= adjustedPositionMs }
    val line = lines.getOrNull(index)
    return DesktopPipLyricSelection(index, line == null || line.isGap ||
        (line.hasKnownEnd && adjustedPositionMs >= line.endMs))
}

/** Prevent a composition between selection and the lookup effect from displaying the old song. */
internal fun DesktopPipState.currentLyrics(): List<LyricLine>? = lyrics.takeIf { lyricsTrack == song?.videoId }
