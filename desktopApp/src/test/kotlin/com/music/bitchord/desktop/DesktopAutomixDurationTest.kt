package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAutomixDurationTest {
    @Test
    fun `adaptive is the default for old or invalid preferences`() {
        assertEquals(DesktopAutomixDuration.ADAPTIVE, DesktopAutomixDuration.fromStored(""))
        assertEquals(DesktopAutomixDuration.ADAPTIVE, DesktopAutomixDuration.fromStored("unknown"))
        assertEquals(0, DesktopAutomixDuration.ADAPTIVE.phraseBars)
    }

    @Test
    fun `each selection round trips through persistent storage`() {
        val options = DesktopAutomixDuration.entries
        assertEquals(listOf(0, 8, 16), options.map { it.phraseBars })
        options.forEach { choice ->
            assertEquals(choice, DesktopAutomixDuration.fromStored(choice.name))
        }
    }
}
