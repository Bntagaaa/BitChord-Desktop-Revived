package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopThemeTest {
    @Test
    fun `loads known modes and rejects corrupted stored preferences`() {
        assertEquals(DesktopThemeMode.DARK, DesktopThemeMode.fromStored("DARK"))
        assertEquals(DesktopThemeMode.LIGHT, DesktopThemeMode.fromStored("LIGHT"))
        assertEquals(DesktopThemeMode.SYSTEM, DesktopThemeMode.fromStored("SYSTEM"))
        assertEquals(DesktopThemeMode.SYSTEM, DesktopThemeMode.fromStored("not-a-theme"))
        assertEquals(DesktopThemeMode.SYSTEM, DesktopThemeMode.fromStored(""))
    }

    @Test
    fun `system follows operating-system appearance while overrides win`() {
        assertEquals(true, DesktopTheme.resolveDark(DesktopThemeMode.SYSTEM, true))
        assertEquals(false, DesktopTheme.resolveDark(DesktopThemeMode.SYSTEM, false))
        assertEquals(true, DesktopTheme.resolveDark(DesktopThemeMode.DARK, false))
        assertEquals(false, DesktopTheme.resolveDark(DesktopThemeMode.LIGHT, true))
    }

    @Test
    fun `dark mode defaults to the original desktop colors`() {
        assertEquals(Color(0xFF202020), DARK_PALETTE.background)
        assertEquals(Color(0xFF0D0D0F), DARK_PALETTE.sidebarSurface)
        assertEquals(Color(0xFF0D0D0F), DARK_PALETTE.surface)
        assertEquals(Color(0xFF0D0D0F), DARK_PALETTE.chromeSurface)
        assertEquals(Color(0xFFFA586A), DARK_PALETTE.navAccent)
        assertEquals(Color.White, DARK_PALETTE.foreground)
        assertEquals(Color.White, DARK_PALETTE.accent)
        assertEquals(Color.Black, DARK_PALETTE.onAccent)
    }

    @Test
    fun `selected sidebar background is gray while icons and indicators stay rose`() {
        assertEquals(Color(0xFFFA586A), DARK_PALETTE.navAccent)
        assertEquals(Color(0xFFCD3659), LIGHT_PALETTE.navAccent)
        assertEquals(Color(0xFF2D2D2D), DARK_PALETTE.navSelectedSurface)
        assertEquals(Color(0xFFDDDDDD), LIGHT_PALETTE.navSelectedSurface)
        assertEquals(Color(0xFF0D0D0F), DARK_PALETTE.sidebarSurface)
        assertEquals(Color(0xFFE9ECF2), LIGHT_PALETTE.sidebarSurface)
        assertNotEquals(DARK_PALETTE.navAccent, DARK_PALETTE.navSelectedSurface)
        assertNotEquals(LIGHT_PALETTE.navAccent, LIGHT_PALETTE.navSelectedSurface)
    }

    @Test
    fun `pitch black is available only when Dark is explicitly selected`() {
        assertTrue(DesktopTheme.supportsPitchBlack(DesktopThemeMode.DARK))
        assertEquals(false, DesktopTheme.supportsPitchBlack(DesktopThemeMode.LIGHT))
        assertEquals(false, DesktopTheme.supportsPitchBlack(DesktopThemeMode.SYSTEM))
    }

    @Test
    fun `pitch black makes both dark backgrounds fully black without swapping`() {
        assertEquals(DARK_PALETTE, DesktopTheme.resolvePalette(DesktopThemeMode.DARK, false, false))
        assertEquals(PITCH_BLACK_PALETTE, DesktopTheme.resolvePalette(DesktopThemeMode.DARK, false, true))
        assertEquals(LIGHT_PALETTE, DesktopTheme.resolvePalette(DesktopThemeMode.LIGHT, true, true))
        assertEquals(DARK_PALETTE, DesktopTheme.resolvePalette(DesktopThemeMode.SYSTEM, true, true))
        assertEquals(LIGHT_PALETTE, DesktopTheme.resolvePalette(DesktopThemeMode.SYSTEM, false, true))
        assertEquals(Color.Black, PITCH_BLACK_PALETTE.background)
        assertEquals(Color.Black, PITCH_BLACK_PALETTE.sidebarSurface)
        assertEquals(Color.Black, PITCH_BLACK_PALETTE.surface)
        assertEquals(Color.Black, PITCH_BLACK_PALETTE.chromeSurface)
        assertEquals(DARK_PALETTE.navAccent, PITCH_BLACK_PALETTE.navAccent)
        assertEquals(DARK_PALETTE.floatingPlayerSurface, PITCH_BLACK_PALETTE.floatingPlayerSurface)
        assertTrue(contrastRatio(PITCH_BLACK_PALETTE.secondary, PITCH_BLACK_PALETTE.sidebarSurface) >= 4.5)
    }

    @Test
    fun `light palette has distinct surfaces and accessible body text`() {
        assertNotEquals(DARK_PALETTE.background, LIGHT_PALETTE.background)
        assertNotEquals(LIGHT_PALETTE.background, LIGHT_PALETTE.surface)
        assertTrue(contrastRatio(LIGHT_PALETTE.foreground, LIGHT_PALETTE.background) >= 7.0)
        assertTrue(contrastRatio(LIGHT_PALETTE.secondary, LIGHT_PALETTE.background) >= 4.5)
        assertTrue(contrastRatio(LIGHT_PALETTE.onAccent, LIGHT_PALETTE.accent) >= 7.0)
        // The floating player and chrome must not turn their controls into white-on-white.
        assertTrue(contrastRatio(LIGHT_PALETTE.foreground, LIGHT_PALETTE.floatingPlayerSurface) >= 7.0)
        assertTrue(contrastRatio(LIGHT_PALETTE.secondary, LIGHT_PALETTE.floatingPlayerSurface) >= 4.5)
        assertNotEquals(LIGHT_PALETTE.background, LIGHT_PALETTE.floatingPlayerSurface)
        assertNotEquals(LIGHT_PALETTE.searchSurface, LIGHT_PALETTE.surfaceRaised)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        fun linear(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        fun luminance(color: Color): Double =
            0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
        val first = luminance(foreground)
        val second = luminance(background)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
