package com.music.bitchord.desktop

import org.junit.Test
import kotlin.test.assertEquals

class DesktopSidePanelMaterialTest {
    @Test
    fun `only the home queue receives the experimental glass`() {
        for (panel in DesktopSidePanel.entries) {
            val expected = if (panel == DesktopSidePanel.QUEUE) {
                DesktopSidePanelMaterial.QUEUE_GLASS
            } else {
                DesktopSidePanelMaterial.FROSTED
            }
            assertEquals(expected, desktopSidePanelMaterial(panel, homeOverlay = true, blurAvailable = true))
        }
    }

    @Test
    fun `other pages keep solid panels even when blur is available`() {
        for (panel in DesktopSidePanel.entries) {
            assertEquals(
                DesktopSidePanelMaterial.SOLID,
                desktopSidePanelMaterial(panel, homeOverlay = false, blurAvailable = true),
            )
        }
    }

    @Test
    fun `disabled or unavailable blur always uses the original solid surface`() {
        for (panel in DesktopSidePanel.entries) {
            for (homeOverlay in listOf(false, true)) {
                assertEquals(
                    DesktopSidePanelMaterial.SOLID,
                    desktopSidePanelMaterial(panel, homeOverlay = homeOverlay, blurAvailable = false),
                )
            }
        }
    }
}
