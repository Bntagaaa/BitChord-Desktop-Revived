package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBoundedMapTest {
    @Test fun recentAnalysisEntriesStayBoundedAndPreserveCurrentSong() {
        val cache = synchronizedBoundedMap<String, Int>(3)
        cache["current"] = 1
        cache["old"] = 2
        cache["next"] = 3
        assertEquals(1, cache["current"]) // the audible track is recently used
        cache["another"] = 4
        assertEquals(3, cache.size)
        assertTrue(cache.containsKey("current"))
        assertTrue(cache.containsKey("next"))
        assertFalse(cache.containsKey("old"))
    }

    @Test fun cacheNeverGrowsBeyondItsConfiguredMaximum() {
        val cache = synchronizedBoundedMap<String, Int>(64)
        repeat(10_000) { cache["track-$it"] = it }
        assertEquals(64, cache.size)
        assertEquals(9_999, cache["track-9999"])
        assertFalse(cache.containsKey("track-0"))
    }
}
