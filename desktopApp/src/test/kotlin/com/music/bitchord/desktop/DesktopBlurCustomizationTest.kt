package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one visible blur setting is authoritative across all desktop surfaces. */
class DesktopBlurCustomizationTest {
    @Test
    fun `Reduce Blur disables every surface regardless of legacy preferences`() {
        DesktopBlurArea.entries.forEach { area ->
            assertFalse(effectiveDesktopBlur(reduceDynamicBlur = true, selected = true), area.name)
            assertFalse(effectiveDesktopBlur(reduceDynamicBlur = true, selected = false), area.name)
        }
    }

    @Test
    fun `legacy per-area preferences and graphics profiles no longer affect blur`() {
        DesktopBlurArea.entries.forEach { area ->
            assertTrue(effectiveDesktopBlur(reduceDynamicBlur = false, selected = true), area.name)
            assertTrue(effectiveDesktopBlur(reduceDynamicBlur = false, selected = false, profile = DesktopPerformanceProfile.PERFORMANCE), area.name)
        }
    }
}
