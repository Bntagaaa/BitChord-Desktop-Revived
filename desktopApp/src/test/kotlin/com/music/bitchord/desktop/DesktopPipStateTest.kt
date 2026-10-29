package com.music.bitchord.desktop

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import java.awt.Rectangle
import kotlin.test.*

class DesktopPipStateTest {
    @Test fun `monitor restoration supports negative origins and disconnected monitors`() {
        val left = Rectangle(-1920, 0, 1920, 1080); val right = Rectangle(0, 24, 1600, 900)
        val saved = DesktopPipBounds(-1700, 300, 500, 340)
        assertEquals(saved, DesktopPipGeometry.restore(saved, listOf(right, left)))
        val restored = DesktopPipGeometry.restore(saved, listOf(right))
        assertTrue(right.contains(restored.x, restored.y))
        assertTrue(right.contains(restored.x + restored.width - 1, restored.y + restored.height - 1))
        val invalid = DesktopPipGeometry.restore(DesktopPipBounds(Int.MAX_VALUE, Int.MIN_VALUE, -4, 900000), listOf(right))
        assertEquals(500, invalid.width); assertEquals(900, invalid.height)
        assertTrue(right.contains(invalid.x, invalid.y))
    }
    @Test fun `arbitrary intermediate layout dimensions stay bounded and vary continuously`() {
        for (width in 320..1400 step 13) for (height in 180..1000 step 17) {
            val layout = DesktopPipLayout.at(width.toFloat(), height.toFloat())
            assertTrue(layout.padding in 10f..24f)
            assertTrue(layout.transport in 34f..48f)
            assertTrue(layout.titleSp in 13f..22f)
            assertTrue(layout.lyricSp in 14f..29f)
            assertTrue(height - layout.padding*2 - 28 - 20 - layout.transport - layout.gap*3 > 0)
        }
        assertNotEquals(DesktopPipLayout.at(437f, 283f), DesktopPipLayout.at(438f, 284f))
        assertFalse(DesktopPipLayout.at(700f, 950f).sideBySide)
        assertTrue(DesktopPipLayout.at(820f, 410f).sideBySide)
    }
    @Test fun `lyrics switch at timestamps seek backward and distinguish real instrumental ends`() {
        val lines = listOf(LyricLine(1000,"First", sungUntilMs=2000), LyricLine(4000,""), LyricLine(6000,"Next"))
        assertEquals(DesktopPipLyricSelection(-1,true), desktopPipLyricSelection(lines,500))
        assertEquals(DesktopPipLyricSelection(0,false), desktopPipLyricSelection(lines,1500))
        assertEquals(DesktopPipLyricSelection(0,true), desktopPipLyricSelection(lines,3000))
        assertEquals(DesktopPipLyricSelection(1,true), desktopPipLyricSelection(lines,5000))
        assertEquals(DesktopPipLyricSelection(2,false), desktopPipLyricSelection(lines,10000))
        assertEquals(DesktopPipLyricSelection(0,false), desktopPipLyricSelection(lines,1500))
    }
    @Test fun `old song lyrics are never adapted into a new track`() {
        val state = DesktopPipState(Song("new","Title","Artist",null), true,false,false,10000,true,true,false,
            "old",listOf(LyricLine(1000,"Old words")),false,null)
        assertNull(state.currentLyrics())
        assertNotNull(state.copy(lyricsTrack="new").currentLyrics())
    }
    @Test fun `malformed saved bounds are rejected and native-size preferences survive flush`() {
        assertNull(DesktopPipSettings.decodeBounds("NaN,5,300,240"))
        assertNull(DesktopPipSettings.decodeBounds("1,2,3"))
        val before = DesktopPipSettings.state.value
        try {
            DesktopPipSettings.update { it.copy(lyrics=false, bounds=DesktopPipBounds(-500,20,437,283)) }
            DesktopPipSettings.flush()
            val prefs = java.util.prefs.Preferences.userRoot().node("com.music.bitchord.desktop/pip-v2")
            assertFalse(prefs.getBoolean("lyrics",true))
            assertEquals(DesktopPipBounds(-500,20,437,283), DesktopPipSettings.decodeBounds(prefs.get("bounds","")))
        } finally { DesktopPipSettings.update { before }; DesktopPipSettings.flush() }
    }
}
