package com.music.bitchord.desktop

import java.awt.Dimension
import java.awt.Insets
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPipGeometryTest {
    private val decorations = Insets(30, 6, 6, 6)
    private val workArea = Rectangle(0, 0, 1920, 1040)

    @Test
    fun correctedContentKeepsTheCompactClientAreaAtFractionalScales() {
        for ((density, client) in listOf(
            1.25f to Dimension(400, 225),
            1.5f to Dimension(480, 270),
            1.75f to Dimension(560, 315),
            2f to Dimension(640, 360),
        )) {
            assertEquals(
                Dimension(client.width + 12, client.height + 36),
                DesktopPipGeometry.minimumSize(density, 1.0, 1.0, decorations, workArea),
            )
        }
    }

    @Test
    fun nativeScalingIsNotAppliedTwiceAndOneHundredPercentKeepsItsOriginalMinimum() {
        val original = Dimension(340, 216)
        assertEquals(original, DesktopPipGeometry.minimumSize(1f, 1.0, 1.0, decorations, workArea))
        assertEquals(original, DesktopPipGeometry.minimumSize(2f, 2.0, 2.0, decorations, workArea))
        assertEquals(original, DesktopPipGeometry.minimumSize(1.5f, 1.5, 1.5, decorations, workArea))
        assertEquals(
            Dimension(340, 210),
            DesktopPipGeometry.minimumSize(1f, 1.0, 1.0, Insets(0, 0, 0, 0), workArea),
        )
    }

    @Test
    fun minimumNeverExceedsTheAvailableMonitorWorkArea() {
        assertEquals(
            Dimension(500, 280),
            DesktopPipGeometry.minimumSize(2f, 1.0, 1.0, decorations, Rectangle(-500, 0, 500, 280)),
        )
    }
}
