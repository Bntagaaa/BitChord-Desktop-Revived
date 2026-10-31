package com.music.bitchord.desktop

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWindowModeTest {
    @Test fun linuxExitIgnoresStaleFullscreenAndCanReenter() {
        val mode = DesktopWindowPlacementController(isWindows = false)
        mode.toggle()
        assertEquals(WindowPlacement.Fullscreen, mode.placement.value)
        mode.adopt(WindowPlacement.Floating) // delayed state from before entry
        assertTrue(mode.fullScreen.value)
        mode.adopt(WindowPlacement.Fullscreen) // entry acknowledged
        assertTrue(mode.exitOnEscape())
        assertEquals(WindowPlacement.Floating, mode.placement.value)
        mode.adopt(WindowPlacement.Fullscreen) // old snapshot while exit is pending
        assertFalse(mode.fullScreen.value)
        assertEquals(WindowPlacement.Floating, mode.placement.value)
        mode.adopt(WindowPlacement.Floating)
        assertFalse(mode.exitOnEscape())
        mode.toggle()
        assertTrue(mode.fullScreen.value)
    }

    @Test fun restoresPreviousMaximizedPlacement() {
        val mode = DesktopWindowPlacementController(isWindows = false)
        mode.adopt(WindowPlacement.Maximized)
        mode.toggle()
        mode.adopt(WindowPlacement.Fullscreen)
        mode.exit()
        mode.adopt(WindowPlacement.Fullscreen)
        assertEquals(WindowPlacement.Maximized, mode.placement.value)
        mode.adopt(WindowPlacement.Maximized)
        assertTrue(mode.maximized.value)
    }

    @Test fun externalFullscreenStillSynchronizes() {
        val mode = DesktopWindowPlacementController(isWindows = false)
        mode.adopt(WindowPlacement.Fullscreen)
        assertTrue(mode.fullScreen.value)
        mode.adopt(WindowPlacement.Floating)
        assertFalse(mode.fullScreen.value)
    }

    @Test fun windowsFullscreenDoesNotChangeComposePlacement() {
        val mode = DesktopWindowPlacementController(isWindows = true)
        mode.toggle()
        assertTrue(mode.fullScreen.value)
        assertEquals(WindowPlacement.Floating, mode.placement.value)
        mode.adopt(WindowPlacement.Maximized)
        assertTrue(mode.fullScreen.value)
        mode.exit()
        assertFalse(mode.fullScreen.value)
        assertEquals(WindowPlacement.Floating, mode.placement.value)
    }
}
