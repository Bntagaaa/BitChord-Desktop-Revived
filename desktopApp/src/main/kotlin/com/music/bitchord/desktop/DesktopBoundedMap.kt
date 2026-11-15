package com.music.bitchord.desktop

import java.util.Collections
import java.util.LinkedHashMap

/**
 * Synchronised access-order LRU for values shared between the audio and analysis threads.
 * The application must not grow its heap proportionally to every song visited in a session.
 */
internal fun <K : Any, V : Any> synchronizedBoundedMap(maxEntries: Int): MutableMap<K, V> {
    require(maxEntries > 0)
    return Collections.synchronizedMap(object : LinkedHashMap<K, V>(
        maxEntries.coerceAtMost(64), 0.75f, true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    })
}
