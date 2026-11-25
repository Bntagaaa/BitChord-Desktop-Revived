package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopPageMemoryPolicyTest {
    @Test fun oldestInactiveIsRemovedFirst() {
        val allowed = setOf("Home", "Search", "New")
        assertEquals("Home", DesktopPageMemoryPolicy.oldestInactive(listOf("Home", "Search", "New"), "New", allowed))
        assertEquals("Search", DesktopPageMemoryPolicy.oldestInactive(listOf("Search", "New"), "New", allowed))
    }
    @Test fun activePageIsProtected() {
        assertNull(DesktopPageMemoryPolicy.oldestInactive(listOf("New"), "New", setOf("New")))
    }
    @Test fun unrelatedRoutesAreIgnored() {
        assertEquals("Home", DesktopPageMemoryPolicy.oldestInactive(
            listOf("Library", "Home", "New"), "New", setOf("Home", "New")))
    }

    @Test fun thresholdIsLoweredToPreventMemorySwelling() {
        assertEquals(300L * 1024L * 1024L, DesktopPageMemoryPolicy.TRIM_THRESHOLD_BYTES)
    }

    @Test fun noInactivePageTrimIsThrottled() {
        val limit = DesktopPageMemoryPolicy.TRIM_THRESHOLD_BYTES
        val cooldown = DesktopPageMemoryPolicy.ACTIVE_PAGE_TRIM_COOLDOWN_NS
        kotlin.test.assertFalse(DesktopPageMemoryPolicy.shouldTrimActivePage(limit - 1L, cooldown))
        kotlin.test.assertFalse(DesktopPageMemoryPolicy.shouldTrimActivePage(limit, cooldown - 1L))
        kotlin.test.assertTrue(DesktopPageMemoryPolicy.shouldTrimActivePage(limit, cooldown))
    }

    @Test fun trimProcessMemoryCompletesSafely() {
        DesktopPageMemoryPolicy.trimProcessMemory()
    }
}
