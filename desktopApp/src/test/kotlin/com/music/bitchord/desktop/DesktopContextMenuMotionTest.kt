package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopContextMenuMotionTest {
    @Test fun popupScaleIsBounded() {
        assertEquals(0.30f, DesktopContextMenuMotion.scale(0f, false), 0.00001f)
        assertEquals(0.65f, DesktopContextMenuMotion.scale(0.5f, false), 0.00001f)
        assertEquals(1f, DesktopContextMenuMotion.scale(1f, false), 0.00001f)
        assertEquals(1f, DesktopContextMenuMotion.scale(1.5f, false), 0.00001f)
    }

    @Test fun reduceMotionKeepsMenuFullSize() {
        assertEquals(1f, DesktopContextMenuMotion.scale(0f, true))
        assertEquals(1f, DesktopContextMenuMotion.scale(0.5f, true))
    }

    @Test fun scrimAnimatesWithoutBlurringFullscreen() {
        assertEquals(0f, DesktopContextMenuMotion.scrimAlpha(0f, true))
        assertEquals(0.08f, DesktopContextMenuMotion.scrimAlpha(0.5f, true), 0.00001f)
        assertEquals(0.16f, DesktopContextMenuMotion.scrimAlpha(1f, true), 0.00001f)
        assertEquals(0.10f, DesktopContextMenuMotion.scrimAlpha(1f, false), 0.00001f)
    }

    @Test fun animationDurationsAreCompact() {
        assertEquals(190, DesktopContextMenuMotion.OPEN_MS)
        assertEquals(170, DesktopContextMenuMotion.CLOSE_MS)
    }
}
