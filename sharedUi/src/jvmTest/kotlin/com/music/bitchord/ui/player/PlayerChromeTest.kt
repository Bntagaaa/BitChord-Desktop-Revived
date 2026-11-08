package com.music.bitchord.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    @Test
    fun `desktop direct ink compensates for the skipped mobile additive layer`() {
        assertEquals(0.8525f, desktopDirectChromeAlpha(0.55f), 0.00001f)
        assertEquals(0.6975f, desktopDirectChromeAlpha(0.45f), 0.00001f)
        assertEquals(1f, desktopDirectChromeAlpha(CHROME_FULL_ALPHA))
        assertEquals(0f, desktopDirectChromeAlpha(0f))
        assertEquals(1f, desktopDirectChromeAlpha(2f))
    }

    @Test
    fun `desktop text never goes through a recorded additive layer`() {
        assertFalse(shouldUseOffscreenChromeLayer(false, true, true))
        assertFalse(shouldUseOffscreenChromeLayer(true, true, true))
        assertFalse(shouldUseOffscreenChromeLayer(true, true, false))
    }

    @Test
    fun `non-desktop chrome keeps capability gated additive rendering`() {
        assertTrue(shouldUseOffscreenChromeLayer(true, false, true))
        assertFalse(shouldUseOffscreenChromeLayer(true, false, false))
        assertFalse(shouldUseOffscreenChromeLayer(false, false, true))
    }

}