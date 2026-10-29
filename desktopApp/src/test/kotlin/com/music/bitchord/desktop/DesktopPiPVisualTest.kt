package com.music.bitchord.desktop

import androidx.compose.ui.unit.dp
import com.music.bitchord.data.lyrics.LyricLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPiPVisualTest {

    @Test
    fun lyricsStayAccessibleAcrossContinuouslyResizableWindow() {
        assertEquals(DesktopPiPLyricsMode.INLINE, desktopPiPLyricsMode(270.dp, 160.dp, true))
        assertEquals(DesktopPiPLyricsMode.INLINE, desktopPiPLyricsMode(350.dp, 220.dp, true))
        assertEquals(DesktopPiPLyricsMode.BELOW, desktopPiPLyricsMode(440.dp, 280.dp, true))
        assertEquals(DesktopPiPLyricsMode.BESIDE, desktopPiPLyricsMode(820.dp, 380.dp, true))
        assertEquals(DesktopPiPLyricsMode.HIDDEN, desktopPiPLyricsMode(820.dp, 380.dp, false))
    }

    @Test
    fun lineCursorFollowsPlaybackAndInstrumentalGaps() {
        val lines = listOf(
            LyricLine(0, ""),
            LyricLine(1_000, "One"),
            LyricLine(2_000, ""),
            LyricLine(3_000, "Two"),
        )
        assertEquals("♪ Instrumental", desktopPiPActiveLyrics(lines, 500, 0)?.current)
        assertEquals("One", desktopPiPActiveLyrics(lines, 1_500, 0)?.current)
        assertEquals("♪ Instrumental", desktopPiPActiveLyrics(lines, 2_500, 0)?.current)
        assertEquals("Two", desktopPiPActiveLyrics(lines, 3_500, 0)?.current)
        assertEquals("One", desktopPiPActiveLyrics(lines, 3_500, 2_000)?.current)
        assertEquals("Two", desktopPiPActiveLyrics(lines, 2_500, -1_000)?.current)
        val current = desktopPiPActiveLyrics(lines, 1_500, 0)
        assertEquals("Two", current?.next)
        assertTrue(current?.synced == true)
    }

    @Test
    fun unavailableAndUnsyncedLyricsAreHandledWithoutFakeTiming() {
        assertNull(desktopPiPActiveLyrics(emptyList(), 0, 0))
        val unsynced = desktopPiPActiveLyrics(
            listOf(LyricLine(0, "First verse"), LyricLine(0, "Second verse")),
            30_000,
            0,
        )
        assertFalse(unsynced!!.synced)
        assertEquals("First verse", unsynced.current)
        assertEquals("Second verse", unsynced.next)
    }

    @Test
    fun reduceBlurAlwaysOverridesPreparedArtworkBlur() {
        assertTrue(desktopPiPBlurAllowed(reduceBlur = false, hasBackdrop = true))
        assertFalse(desktopPiPBlurAllowed(reduceBlur = true, hasBackdrop = true))
        assertFalse(desktopPiPBlurAllowed(reduceBlur = false, hasBackdrop = false))
    }
}
