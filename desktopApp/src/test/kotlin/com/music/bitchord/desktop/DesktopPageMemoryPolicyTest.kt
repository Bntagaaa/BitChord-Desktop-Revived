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
}
