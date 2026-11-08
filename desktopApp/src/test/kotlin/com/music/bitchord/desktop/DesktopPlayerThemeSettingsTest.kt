package com.music.bitchord.desktop

import com.music.bitchord.data.settings.PlayerTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPlayerThemeSettingsTest {
    @Test
    fun `defaults to cover-edge colours when no setting exists`() {
        assertEquals(PlayerTheme.DEFAULT, restoreDesktopPlayerTheme("", false))
    }

    @Test
    fun `migrates the old legacy mesh toggle`() {
        assertEquals(PlayerTheme.LEGACY, restoreDesktopPlayerTheme("", true))
    }

    @Test
    fun `explicit selection always wins over the old toggle`() {
        PlayerTheme.entries.forEach { option ->
            assertEquals(option, restoreDesktopPlayerTheme(option.name, false))
            assertEquals(option, restoreDesktopPlayerTheme(option.name, true))
        }
    }

    @Test
    fun `corrupt values fall back safely`() {
        assertEquals(PlayerTheme.DEFAULT, restoreDesktopPlayerTheme("MISSING", false))
        assertEquals(PlayerTheme.LEGACY, restoreDesktopPlayerTheme("MISSING", true))
    }

    @Test
    fun `shows the same theme ordering as BitChord mobile`() {
        assertEquals(listOf(PlayerTheme.DEFAULT, PlayerTheme.LEGACY, PlayerTheme.SIMPLE), PlayerTheme.entries)
    }
}
