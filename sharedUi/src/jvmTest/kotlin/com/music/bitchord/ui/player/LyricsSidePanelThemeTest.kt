package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsSidePanelThemeTest {
    @Test
    fun `light sidebar uses contrast ink from the desktop theme`() {
        val ink = Color(0xFF20242A)
        val palette = lyricsSidePanelColors(isLight = true, onSurface = ink)

        assertTrue(palette.isLight)
        assertEquals(ink, palette.primary)
        assertEquals(ink, palette.inactive)
        assertEquals(ink, palette.iconSurface)
        assertEquals(ink, palette.selection)
        assertEquals(ink.copy(alpha = 0.76f), palette.secondary)
    }

    @Test
    fun `dark sidebar retains the fullscreen-compatible default palette`() {
        val palette = lyricsSidePanelColors(isLight = false, onSurface = Color.White)

        assertFalse(palette.isLight)
        assertEquals(LyricsPanelColors(), palette)
        assertEquals(Color.White, palette.primary)
        assertEquals(Color.White, palette.inactive)
    }
}
