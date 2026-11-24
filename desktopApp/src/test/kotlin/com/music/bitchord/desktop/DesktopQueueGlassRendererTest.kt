package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopQueueGlassRendererTest {
    @Test
    fun `only a panel sized half resolution sample is allocated`() {
        assertEquals(IntSize(180, 400), homeQueueGlassSampleSize(360f, 800f))
        assertEquals(IntSize(270, 600), homeQueueGlassSampleSize(540f, 1200f))
        assertEquals(IntSize(1, 1), homeQueueGlassSampleSize(0f, 0f))
    }

    @Test
    fun `desktop Skia compiles the actual lens shader`() {
        assertNotNull(createHomeQueueGlassRenderEffect(180, 400, 0.5f, Color(0xFF202020)))
        assertNotNull(createHomeQueueGlassRenderEffect(270, 600, 0.75f, Color(0xFFEDF0F6)))
    }
}
