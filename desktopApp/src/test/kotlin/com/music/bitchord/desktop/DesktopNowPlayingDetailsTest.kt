package com.music.bitchord.desktop

import com.music.bitchord.data.lyrics.LyricLine
import androidx.compose.ui.graphics.Color
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

    @Test fun grayscaleCanvasKeepsLyricsSurfaceNeutral() {
        val base = Color(0xFF393939)
        assertEquals(base, tintSurfaceFromArtwork(base, Color(0xFF808080), 0.28f))
    }

    @Test fun colorfulCanvasTintsLyricsSurfaceWithoutReplacingItsBase() {
        val base = Color(0xFF393939)
        val tinted = tintSurfaceFromArtwork(base, Color(0xFF00A080), 0.28f)
        assertTrue(tinted.green > tinted.red)
        assertTrue(tinted.blue > tinted.red)
        assertTrue(tinted.red > 0f && tinted.green < 0.5f)
    }

    @Test fun grayscaleArtworkUsesTheSameNeutralSurfaceForCanvasFadeAndLyricsCard() {
        val grayscale = DesktopArtworkPalette(Color(0xFF808080), Color(0xFF808080))
        assertEquals(Color(0xFF393939), lyricsPreviewSurfaceColor(grayscale, dark = true))
    }

    @Test fun colourfulArtworkGivesLyricsSurfaceItsOwnTint() {
        val greenArtwork = DesktopArtworkPalette(Color(0xFF00A080), Color(0xFF004C40))
        val surface = lyricsPreviewSurfaceColor(greenArtwork, dark = true)
        assertTrue(surface.green > surface.red)
        assertTrue(surface != Color(0xFF393939))
    }
}
