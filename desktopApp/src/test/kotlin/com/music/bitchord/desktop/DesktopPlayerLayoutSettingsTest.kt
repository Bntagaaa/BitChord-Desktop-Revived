package com.music.bitchord.desktop

import com.music.bitchord.data.settings.PlayerLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPlayerLayoutSettingsTest {
    @Test
    fun `defaults to classic layout when no setting exists`() {
        assertEquals(PlayerLayout.CLASSIC, restoreDesktopPlayerLayout(""))
    }

    @Test
    fun `restores every valid layout selection`() {
        PlayerLayout.entries.forEach { option ->
            assertEquals(option, restoreDesktopPlayerLayout(option.name))
        }
    }

    @Test
    fun `corrupt values fall back to classic`() {
        assertEquals(PlayerLayout.CLASSIC, restoreDesktopPlayerLayout("UNKNOWN"))
    }

    @Test
    fun `keeps classic first and centered second`() {
        assertEquals(listOf(PlayerLayout.CLASSIC, PlayerLayout.CENTERED), PlayerLayout.entries)
    }
}
