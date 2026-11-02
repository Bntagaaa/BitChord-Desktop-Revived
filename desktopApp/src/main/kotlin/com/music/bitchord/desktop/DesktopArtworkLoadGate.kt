package com.music.bitchord.desktop

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * A small, fixed-memory gate for artwork IO.
 *
 * The caller, not a detached application worker, owns each request. Suspended
 * loads disappear when LazyColumn/LazyGrid cancels their composables. A striped
 * mutex serializes duplicate URLs so the second caller can use the freshly
 * cached image without decoding it twice; the global semaphore bounds active
 * network and image decoder work.
 */
internal class DesktopArtworkLoadGate(
    maxConcurrentLoads: Int,
    urlStripes: Int,
) {
    init {
        require(maxConcurrentLoads > 0)
        require(urlStripes > 0)
    }

    private val slots = Semaphore(maxConcurrentLoads)
    private val locks = Array(urlStripes) { Mutex() }

    suspend fun <T> forUrl(url: String, action: suspend () -> T): T {
        val index = (url.hashCode() and Int.MAX_VALUE) % locks.size
        return locks[index].withLock {
            slots.withPermit { action() }
        }
    }
}
