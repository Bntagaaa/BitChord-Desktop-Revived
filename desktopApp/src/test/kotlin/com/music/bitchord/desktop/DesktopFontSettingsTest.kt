package com.music.bitchord.desktop

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.music.bitchord.ui.theme.bitChordTypography
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopFontSettingsTest {
    @Test
    fun `unknown or missing stored font falls back to upstream default`() {
        assertEquals(DesktopFontMode.DEFAULT, DesktopFontMode.fromStored(""))
        assertEquals(DesktopFontMode.DEFAULT, DesktopFontMode.fromStored("UNKNOWN"))
        assertEquals(DesktopFontMode.DEFAULT, DesktopFontMode.fromStored("default"))
        assertEquals(DesktopFontMode.DEFAULT, DesktopFontMode.fromStored("DEFAULT"))
        assertEquals(DesktopFontMode.LEGACY, DesktopFontMode.fromStored("LEGACY"))
    }

    @Test
    fun `modern SF Pro requests lighter real cuts and legacy keeps the original weights`() {
        val requested = listOf(FontWeight.W400, FontWeight.W500, FontWeight.W600, FontWeight.W700, FontWeight.W800)
        assertEquals(
            listOf(FontWeight.W400, FontWeight.W400, FontWeight.W500, FontWeight.W600, FontWeight.W700),
            requested.map(DesktopFontMode.DEFAULT::physicalCut),
        )
        assertEquals(requested, requested.map(DesktopFontMode.LEGACY::physicalCut))
    }

    @Test
    fun `tracking and metrics come from the actual shared production scale`() {
        val modern = bitChordTypography(FontFamily.SansSerif, legacy = false)
        val legacy = bitChordTypography(FontFamily.SansSerif, legacy = true)
        assertEquals((-0.5).sp, modern.displayLarge.letterSpacing)
        assertEquals((-0.8).sp, legacy.displayLarge.letterSpacing)
        assertEquals(0.05.sp, modern.titleMedium.letterSpacing)
        assertEquals((-0.2).sp, legacy.titleMedium.letterSpacing)
        assertEquals(0.2.sp, modern.bodyLarge.letterSpacing)
        assertEquals(TextUnit.Unspecified, legacy.bodyLarge.letterSpacing)
        assertEquals(0.3.sp, modern.labelSmall.letterSpacing)
        assertEquals(TextUnit.Unspecified, legacy.labelSmall.letterSpacing)
        assertEquals(TextUnit.Unspecified, modern.displayLarge.lineHeight)
        assertEquals(TextUnit.Unspecified, legacy.displayLarge.lineHeight)
    }
}
