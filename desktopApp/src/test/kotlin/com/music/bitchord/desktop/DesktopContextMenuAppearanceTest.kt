package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.SoftSurfaceRole
import com.music.bitchord.ui.components.softSurfacePaint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopContextMenuAppearanceTest {
    @Test
    fun popupMaterialIsOpaqueAndSubtlyElevatedInEveryTheme() {
        for ((fill, light) in listOf(
            DARK_PALETTE.cardFill to false,
            PITCH_BLACK_PALETTE.cardFill to false,
            LIGHT_PALETTE.cardFill to true,
        )) {
            val paint = softSurfacePaint(fill, light, SoftSurfaceRole.POPUP)
            assertEquals(1f, paint.top.alpha)
            assertEquals(1f, paint.bottom.alpha)
            assertTrue(paint.top.red > paint.bottom.red)
            assertTrue(paint.top.red - paint.bottom.red < 0.10f)
            assertTrue(paint.edge.alpha in 0f..0.12f)
            assertEquals(14.dp, paint.elevation)
        }
    }

    @Test
    fun buttonElevationIsSmallAndDisabledButtonsDoNotCastAShadow() {
        val active = softSurfacePaint(Color.White, true, SoftSurfaceRole.BUTTON)
        val disabled = softSurfacePaint(Color.White, true, SoftSurfaceRole.BUTTON, enabled = false)
        assertEquals(2.dp, active.elevation)
        assertEquals(0.dp, disabled.elevation)
        assertTrue(active.top.red > active.bottom.red)
        assertTrue(disabled.edge.alpha < active.edge.alpha)
    }

    @Test
    fun quietControlsHaveARestrainedGradientRatherThanAHeavyShadow() {
        val paint = softSurfacePaint(Color.Transparent, false, SoftSurfaceRole.QUIET_BUTTON)
        assertEquals(0.dp, paint.elevation)
        assertTrue(paint.top.alpha > paint.bottom.alpha)
        assertTrue(paint.top.alpha < 0.10f)
        assertTrue(paint.edge.alpha < 0.10f)
    }
}
