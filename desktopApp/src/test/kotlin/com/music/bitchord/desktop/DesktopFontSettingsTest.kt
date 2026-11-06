package com.music.bitchord.desktop

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
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
    fun `modern tracking is more open and legacy reproduces old heading spacing`() {
        val modern = DesktopFontMode.DEFAULT.tracking()
        val legacy = DesktopFontMode.LEGACY.tracking()
        assertEquals((-0.5).sp, modern.displayLarge)
        assertEquals((-0.8).sp, legacy.displayLarge)
        assertEquals(0.05.sp, modern.titleMedium)
        assertEquals((-0.2).sp, legacy.titleMedium)
        assertEquals(0.2.sp, modern.bodyLarge)
        assertEquals(TextUnit.Unspecified, legacy.bodyLarge)
        assertEquals(0.3.sp, modern.labelSmall)
        assertEquals(TextUnit.Unspecified, legacy.labelSmall)
    }
}
