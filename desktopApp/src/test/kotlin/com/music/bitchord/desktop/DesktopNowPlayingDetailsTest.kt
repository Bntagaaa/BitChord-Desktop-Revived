package com.music.bitchord.desktop

import com.music.bitchord.data.lyrics.LyricLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopNowPlayingDetailsTest {
    private val lyrics = listOf(
        LyricLine(timeMs = 0L, text = "First"),
        LyricLine(timeMs = 1000L, text = ""),
        LyricLine(timeMs = 2000L, text = "Second"),
        LyricLine(timeMs = 4000L, text = "Third"),
        LyricLine(timeMs = 6000L, text = "Fourth"),
    )

    @Test fun usesTheCurrentLineAndTheNextTwo() {
        assertEquals(listOf("Second", "Third", "Fourth"),
            detailsLyricWindow(lyrics, 3000L).map { it.text })
    }

    @Test fun ignoresGapLinesAndLimitsThePreview() {
        assertEquals(listOf("First", "Second", "Third"),
            detailsLyricWindow(lyrics, 0L).map { it.text })
    }

    @Test fun showsTheLastLineAtTheEnd() {
        assertEquals(listOf("Fourth"), detailsLyricWindow(lyrics, 7000L).map { it.text })
    }

    @Test fun handlesUnavailableLyrics() {
        assertTrue(detailsLyricWindow(null, 1000L).isEmpty())
        assertTrue(detailsLyricWindow(emptyList(), 1000L).isEmpty())
    }
}
