package com.music.bitchord.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerChromeTest {
    @Test
    fun `desktop chrome matches the mobile opacity scale`() {
        assertEquals(0.40f, mobileChromeAlpha(0.55f, blended = true), 0.00001f)
        assertEquals(0.58f, mobileChromeAlpha(0.73f, blended = true), 0.00001f)
        assertEquals(0.70f, mobileChromeAlpha(0.85f, blended = true), 0.00001f)
    }

    @Test
    fun `ordinary alpha is preserved when blend is disabled`() {
        assertEquals(0.55f, mobileChromeAlpha(0.55f, blended = false))
        assertEquals(1.0f, mobileChromeAlpha(1.0f, blended = false))
    }

    @Test
    fun `chrome alpha cannot escape the valid range`() {
        assertEquals(0f, mobileChromeAlpha(0.05f, blended = true))
        assertEquals(1f, mobileChromeAlpha(2f, blended = false))
    }
}
