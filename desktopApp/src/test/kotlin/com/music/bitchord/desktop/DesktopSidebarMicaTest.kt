package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopSidebarMicaTest {
    @Test
    fun `missing wallpaper never changes existing themed solid surface`() {
        assertEquals(
            DARK_PALETTE.sidebarSurface,
            DesktopSidebarMica.surface(null, true, DARK_PALETTE.sidebarSurface),
        )
        assertEquals(
            LIGHT_PALETTE.sidebarSurface,
            DesktopSidebarMica.surface(null, false, LIGHT_PALETTE.sidebarSurface),
        )
    }

    @Test
    fun `sampled wallpaper subtly influences sidebar without making it translucent`() {
        val warm = Color(0xFFFF5018)
        val cool = Color(0xFF1850FF)
        val darkWarm = DesktopSidebarMica.surface(warm, true, DARK_PALETTE.sidebarSurface)
        val darkCool = DesktopSidebarMica.surface(cool, true, DARK_PALETTE.sidebarSurface)
        val lightWarm = DesktopSidebarMica.surface(warm, false, LIGHT_PALETTE.sidebarSurface)

        assertNotEquals(darkWarm, darkCool)
        assertNotEquals(darkWarm, lightWarm)
        assertEquals(1f, darkWarm.alpha)
        assertEquals(1f, darkCool.alpha)
        assertEquals(1f, lightWarm.alpha)
    }

    @Test
    fun `wallpaper extremes stay within readable light and dark surface ranges`() {
        for (wallpaper in listOf(Color.Black, Color.White, Color.Red, Color.Blue)) {
            val dark = DesktopSidebarMica.surface(wallpaper, true, DARK_PALETTE.sidebarSurface)
            val light = DesktopSidebarMica.surface(wallpaper, false, LIGHT_PALETTE.sidebarSurface)
            assertTrue(dark.red < 0.18f && dark.green < 0.18f && dark.blue < 0.18f)
            assertTrue(light.red > 0.80f && light.green > 0.80f && light.blue > 0.80f)
            assertEquals(1f, dark.alpha)
            assertEquals(1f, light.alpha)
        }
    }
}
