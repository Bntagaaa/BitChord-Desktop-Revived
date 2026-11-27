package com.music.bitchord.desktop

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWindowsCaptionMetricsTest {
    @Test
    fun `integrated title bar is Windows only and disabled in fullscreen`() {
        assertTrue(DesktopWindowsCaptionMetrics.isIntegratedTitleBarActive(isWindows = true, isFullscreen = false))
        assertFalse(DesktopWindowsCaptionMetrics.isIntegratedTitleBarActive(isWindows = true, isFullscreen = true))
        assertFalse(DesktopWindowsCaptionMetrics.isIntegratedTitleBarActive(isWindows = false, isFullscreen = false))
        assertFalse(DesktopWindowsCaptionMetrics.isIntegratedTitleBarActive(isWindows = false, isFullscreen = true))
    }

    @Test
    fun `caption inset end reserves 138dp only when integrated title bar is active`() {
        assertEquals(138.dp, DesktopWindowsCaptionMetrics.captionInsetEnd(isWindows = true, isFullscreen = false))
        assertEquals(0.dp, DesktopWindowsCaptionMetrics.captionInsetEnd(isWindows = true, isFullscreen = true))
        assertEquals(0.dp, DesktopWindowsCaptionMetrics.captionInsetEnd(isWindows = false, isFullscreen = false))
    }

    @Test
    fun `top bar width policy accounts for Windows caption inset`() {
        val captionWidth = DesktopWindowsCaptionMetrics.TOTAL_WIDTH.value
        assertFalse(DesktopTopBarWidthPolicy.showFullControls(800f - captionWidth))
        assertTrue(DesktopTopBarWidthPolicy.showFullControls(860f - captionWidth))
        assertTrue(DesktopTopBarWidthPolicy.showCompactSkipControls(600f - captionWidth))
    }
}
