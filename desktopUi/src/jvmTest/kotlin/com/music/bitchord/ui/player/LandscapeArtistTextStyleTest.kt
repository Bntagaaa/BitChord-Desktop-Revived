package com.music.bitchord.ui.player

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class LandscapeArtistTextStyleTest {
    private val portrait = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.W700,
        fontFamily = FontFamily.SansSerif,
        letterSpacing = (-0.2).sp,
    )
    private val landscape = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.W600,
        fontFamily = FontFamily.SansSerif,
        letterSpacing = 0.05.sp,
    )

    @Test
    fun `desktop artist uses mobile portrait size and regular mapped cut`() {
        val style = landscapeArtistTextStyle(portrait, landscape, desktopPlayer = true)
        assertEquals(20.sp, style.fontSize)
        assertEquals(FontWeight.W500, style.fontWeight)
        assertEquals(portrait.fontFamily, style.fontFamily)
        assertEquals(portrait.letterSpacing, style.letterSpacing)
    }

    @Test
    fun `android landscape keeps the compact font size`() {
        val style = landscapeArtistTextStyle(portrait, landscape, desktopPlayer = false)
        assertEquals(16.sp, style.fontSize)
        assertEquals(FontWeight.W500, style.fontWeight)
        assertEquals(landscape.letterSpacing, style.letterSpacing)
    }
}
