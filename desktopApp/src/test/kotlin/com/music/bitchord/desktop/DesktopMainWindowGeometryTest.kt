package com.music.bitchord.desktop

import java.awt.Rectangle
import kotlin.test.*

class DesktopMainWindowGeometryTest {
    @Test fun `saved floating bounds survive negative origins and overhanging edges`() {
        val left = Rectangle(-1920, 0, 1920, 1040)
        val primary = Rectangle(0, 24, 1600, 876)
        val onTheLeft = DesktopMainWindowBounds(-1700, 300, 1220, 780)
        assertEquals(onTheLeft, DesktopMainWindowGeometry.restore(onTheLeft, listOf(primary, left)))
        // A window the reader deliberately hangs over an edge keeps the place it was left in.
        val overhanging = DesktopMainWindowBounds(1500, 100, 1220, 780)
        assertEquals(overhanging, DesktopMainWindowGeometry.restore(overhanging, listOf(primary)))
    }

    @Test fun `a window on a disconnected monitor is recovered onto one that exists`() {
        val primary = Rectangle(0, 24, 1600, 900)
        val gone = DesktopMainWindowBounds(3000, 400, 1220, 780)
        val restored = DesktopMainWindowGeometry.restore(gone, listOf(primary))
        assertTrue(primary.contains(restored.x, restored.y))
        assertTrue(primary.contains(restored.x + restored.width - 1, restored.y + restored.height - 1))
        // A sliver left on screen is not enough to grab; it is pulled fully back too.
        val peeking = DesktopMainWindowBounds(1595, 300, 1220, 780)
        val recovered = DesktopMainWindowGeometry.restore(peeking, listOf(primary))
        assertEquals(primary.x + primary.width - peeking.width, recovered.x)
    }

    @Test fun `nothing saved centres the usual size and oversized saved sizes are clamped`() {
        val screen = Rectangle(-1920, 0, 1920, 1080)
        assertEquals(
            DesktopMainWindowBounds(-1920 + (1920 - 1220) / 2, (1080 - 780) / 2, 1220, 780),
            DesktopMainWindowGeometry.restore(null, listOf(screen)),
        )
        val huge = DesktopMainWindowGeometry.restore(DesktopMainWindowBounds(100, 100, 9_000, 9_000), listOf(screen))
        assertEquals(1920, huge.width)
        assertEquals(1080, huge.height)
        assertTrue(screen.contains(huge.x, huge.y))
        assertTrue(screen.contains(huge.x + huge.width - 1, huge.y + huge.height - 1))
    }

    @Test fun `malformed stored bounds are rejected and native bounds round-trip through preferences`() {
        assertNull(DesktopMainWindowSettings.decodeBounds(""))
        assertNull(DesktopMainWindowSettings.decodeBounds("1,2,3"))
        assertNull(DesktopMainWindowSettings.decodeBounds("x,2,3,4"))
        assertNull(DesktopMainWindowSettings.decodeBounds("1,2,0,4"))
        val before = DesktopMainWindowSettings.bounds.value
        try {
            DesktopMainWindowSettings.update(DesktopMainWindowBounds(-500, 20, 1440, 900))
            DesktopMainWindowSettings.flush()
            val prefs = java.util.prefs.Preferences.userRoot().node("com.music.bitchord.desktop/window")
            assertEquals(
                DesktopMainWindowBounds(-500, 20, 1440, 900),
                DesktopMainWindowSettings.decodeBounds(prefs.get("bounds", "")),
            )
        } finally {
            DesktopMainWindowSettings.update(before)
            DesktopMainWindowSettings.flush()
        }
    }
}
