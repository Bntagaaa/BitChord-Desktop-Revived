package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopTabCachePolicyTest {
    @Test fun recentMetadataRemainsWarmAndStaleMetadataRevalidates() {
        val p = DesktopTabCachePolicy()
        assertTrue(p.isStale("new", 1_000, 10_000))
        p.markFresh("new", 10_000)
        assertFalse(p.isStale("new", 1_000, 10_999))
        assertTrue(p.isStale("new", 1_000, 11_000))
    }

    @Test fun accountIdentityAndInvalidationAreIndependent() {
        val p = DesktopTabCachePolicy()
        p.markFresh("library:a", 10_000)
        assertFalse(p.isStale("library:a", 5_000, 11_000))
        assertTrue(p.isStale("library:b", 5_000, 11_000))
        p.invalidate("library:a")
        assertTrue(p.isStale("library:a", 5_000, 11_000))
    }

    @Test fun backwardsMonotonicTimeInvalidates() {
        val p = DesktopTabCachePolicy()
        p.markFresh("home", 10_000)
        assertTrue(p.isStale("home", 20_000, 500))
    }
}
