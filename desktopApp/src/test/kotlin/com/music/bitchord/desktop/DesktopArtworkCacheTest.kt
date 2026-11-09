package com.music.bitchord.desktop

import com.music.bitchord.desktop.runtime.ByteBudgetCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Budget invariants without starting networking or native decoders. */
class DesktopArtworkCacheTest {

    /** Sizes stand in for images: the value *is* its own byte count. */
    private fun lru(budget: Long) = ByteBudgetCache<String, Long>(budget) { it }

    @Test
    fun theBudgetIsAFractionOfTheHeapRatherThanEverything() {
        val budget = DesktopArtworkCache.budgetBytes
        assertTrue(budget >= 16L * 1024 * 1024, "a small heap should still cache something: $budget")
        assertTrue(budget <= 32L * 1024 * 1024, "the cache should not grow without reason: $budget")
    }

    @Test
    fun holdingMoreThanTheBudgetDropsTheColdestEntries() {
        val cache = lru(10)
        repeat(20) { cache.put("art-$it", 3L) }

        assertTrue(cache.heldBytes <= 10, "held ${cache.heldBytes} against a budget of 10")
        // The newest is what a caller is about to draw, so it is the one eviction may not take.
        assertTrue(cache.contains("art-19"), "the newest entry was evicted")
        assertFalse(cache.contains("art-0"), "the coldest entry was kept")
    }

    @Test
    fun readingAnEntryKeepsItWarm() {
        val cache = lru(10)
        cache.put("a", 4L)
        cache.put("b", 4L)
        cache.get("a")
        cache.put("c", 4L)

        assertTrue(cache.contains("a"), "the entry that was just read should have survived")
        assertFalse(cache.contains("b"), "the colder entry should have gone instead")
    }

    @Test
    fun replacingAnEntryDoesNotCountItTwice() {
        val cache = lru(100)
        cache.put("a", 8L)
        cache.put("a", 2L)
        assertEquals(2L, cache.heldBytes)
    }

    @Test
    fun anOversizedEntryDoesNotRemainRetained() {
        val cache = lru(10)
        cache.put("huge", 40L)
        assertFalse(cache.contains("huge"), "the caller owns its image; the cache must respect its budget")
        assertEquals(0L, cache.heldBytes)
    }

    @Test
    fun clearingGivesEverythingBack() {
        val cache = lru(100)
        repeat(5) { cache.put("x-$it", 10L) }
        cache.clear()
        assertEquals(0L, cache.heldBytes)
    }
}
