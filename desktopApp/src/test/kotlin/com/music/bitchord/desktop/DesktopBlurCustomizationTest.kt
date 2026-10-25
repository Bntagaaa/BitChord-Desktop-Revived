package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure policy tests: no OS preferences are modified and no native blur backend is needed. */
class DesktopBlurCustomizationTest {
    @Test
    fun `master Reduce Blur wins over every custom preference`() {
        DesktopBlurArea.entries.forEach { area ->
            assertFalse(effectiveDesktopBlur(reduceDynamicBlur = true, selected = true), area.name)
            assertFalse(effectiveDesktopBlur(reduceDynamicBlur = true, selected = false), area.name)
        }
    }

    @Test
    fun `each area can be enabled or disabled independently`() {
        DesktopBlurArea.entries.forEach { area ->
            assertTrue(effectiveDesktopBlur(reduceDynamicBlur = false, selected = true), area.name)
            assertFalse(effectiveDesktopBlur(reduceDynamicBlur = false, selected = false), area.name)
        }
    }

    @Test
    fun `areas have unique durable preference keys`() {
        val keys = DesktopBlurArea.entries.map { it.preferenceKey }
        assertEquals(DesktopBlurArea.entries.size, keys.toSet().size)
        assertTrue(keys.all { it.startsWith("blur_") })
        assertEquals(6, keys.size)
    }
}
