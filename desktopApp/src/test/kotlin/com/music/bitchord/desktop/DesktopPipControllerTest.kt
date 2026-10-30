package com.music.bitchord.desktop

import kotlin.test.*

class DesktopPipControllerTest {
    @Test fun `rapid show ready and restore never duplicate or recursively close a window`() {
        var hides = 0; var restores = 0
        val controller = DesktopPipController({ true }, { hides++ }, { restores++ }, { true })
        repeat(40) { assertTrue(controller.show()) }
        assertTrue(controller.open.value)
        assertEquals(40, controller.raise.value)
        repeat(40) { controller.windowReady() }
        assertEquals(1, hides)
        repeat(40) { controller.restore() }
        assertFalse(controller.open.value)
        assertEquals(1, restores)
        controller.windowReady()
        assertEquals(1, hides)
    }
    @Test fun `closing a floating window keeps tray playback recoverable and restores main without a tray`() {
        var tray = true; var restores = 0
        val controller = DesktopPipController({ true }, {}, { restores++ }, { tray })
        controller.show(); controller.windowReady(); controller.close()
        assertEquals(0, restores)
        tray = false
        controller.show(); controller.windowReady(); controller.close(); controller.close()
        assertEquals(1, restores)
        assertFalse(controller.open.value)
    }
    @Test fun `unavailable and failed openings preserve normal main or tray behavior`() {
        var hidden = false
        val unavailable = DesktopPipController({ false }, { hidden = true })
        assertFalse(unavailable.show()); unavailable.windowReady(); assertFalse(hidden)
        val controller = DesktopPipController({ true }, { hidden = true }, {}, { true })
        controller.show(); controller.failed(); controller.windowReady()
        assertFalse(controller.open.value); assertFalse(hidden)
    }
    @Test fun `native close main raise and app disposal leave no retained open request`() {
        val controller = DesktopPipController({ true }, {}, {}, { true })
        repeat(100) { controller.show(); controller.windowReady(); controller.mainShown(); assertFalse(controller.open.value) }
        controller.show(); controller.dispose()
        assertFalse(controller.open.value); assertFalse(controller.show())
    }
    @Test fun `late events from an older open request cannot affect the new floating window`() {
        var hides = 0
        val controller = DesktopPipController({ true }, { hides++ }, {}, { true })
        controller.show(); val old = controller.raise.value
        controller.close(); controller.show(); val latest = controller.raise.value
        controller.windowReady(old); controller.close(old); controller.failed(old)
        assertTrue(controller.open.value); assertEquals(0,hides)
        controller.windowReady(latest); assertEquals(1,hides)
    }

}
