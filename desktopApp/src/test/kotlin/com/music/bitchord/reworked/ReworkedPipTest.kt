package com.music.bitchord.reworked

import com.music.bitchord.data.lyrics.LyricLine
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReworkedPipTest {
    @Test fun disconnectedAndNegativeOriginMonitorsKeepControlsReachable() {
        val left = Rectangle(-1920, 0, 1920, 1040)
        val primary = Rectangle(0, 0, 1920, 1040)
        val saved = ReworkedPipBounds(-1800, 100, 440, 360)
        assertEquals(saved, saved.fit(listOf(primary, left)))
        val recovered = saved.fit(listOf(primary))
        assertEquals(0, recovered.x)
        assertEquals(440, recovered.width)
        val tiny = ReworkedPipBounds(9000, 9000, 8000, 6000).fit(listOf(Rectangle(0, 0, 800, 600)))
        assertEquals(ReworkedPipBounds(0, 0, 800, 600), tiny)
        assertNull(ReworkedPipBounds.parse("0,0,-50,10"))
        assertNull(ReworkedPipBounds.parse("garbage"))
    }

    @Test fun lyricOwnerFenceExcludesPreviousTrackAndHonorsGaps() {
        val lyrics = ReworkedPipLyrics("one", listOf(LyricLine(1000, "First", sungUntilMs = 2000), LyricLine(4000, "Second")))
        assertNull(reworkedPipLyric(lyrics, "two", 1500))
        assertEquals("Instrumental", reworkedPipLyric(lyrics, "one", 500))
        assertEquals("First", reworkedPipLyric(lyrics, "one", 1500))
        assertEquals("Instrumental", reworkedPipLyric(lyrics, "one", 3000))
        assertEquals("Second", reworkedPipLyric(lyrics, "one", 4100))
        val plain = ReworkedPipLyrics("one", listOf(LyricLine(0, "Un"), LyricLine(0, "synced")))
        assertEquals("Un\nsynced", reworkedPipLyric(plain, "one", 9000))
    }

    @Test fun openingAndClosingPipOnlyChangesWindowState() {
        ReworkedPip.close()
        ReworkedPip.open(); ReworkedPip.open()
        assertTrue(ReworkedPip.isOpen.value)
        ReworkedPip.close()
        assertEquals(false, ReworkedPip.isOpen.value)
    }
}
