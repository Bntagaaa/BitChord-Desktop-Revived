package com.music.bitchord.desktop

import com.music.bitchord.ui.player.DesktopMenuGlassStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopContextMenuAppearanceTest {
    @Test
    fun desktopContextMenusUseFinalGlassValues() {
        assertEquals(0.47f, DesktopMenuGlassStyle.TINT_ALPHA)
        assertEquals(35, DesktopMenuGlassStyle.BLUR_RADIUS_DP)
    }
}
