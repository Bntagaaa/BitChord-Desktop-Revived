package com.music.bitchord.desktop

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bounds the whole encoded-payload lifetime as well as concurrent native decodes. */
internal class DesktopImageLoader<V : Any>(
    budgetBytes: Long,
    sizeOf: (V) -> Long,
    parallelLoads: Int = 4,
    parallelDecodes: Int = 2,
    private val fetch: suspend (String) -> ByteArray,
    private val decode: suspend (ByteArray) -> V,
) {
    private val cache = DesktopByteBudgetLru(budgetBytes, sizeOf)
    private val flights = DesktopSingleFlight<Pair<Long, String>, V>()
    private val loads = Semaphore(parallelLoads)
    private val decodes = Semaphore(parallelDecodes)
    private val lock = Any()
    private var generation = 0L
    val heldBytes get() = cache.heldBytes

    /** Drop idle cache references without cancelling images still requested by mounted views. */
    fun trimMemory() = synchronized(lock) {
        generation++
        cache.clear()
    }

    fun clear() = synchronized(lock) {
        generation++
        cache.clear()
        flights.cancelAll()
    }

    suspend fun load(url: String): V {
        val revision = synchronized(lock) {
            cache.get(url)?.let { return it }
            generation
        }
        return flights.get(revision to url) {
            loads.withPermit {
                synchronized(lock) { if (revision == generation) cache.get(url) else null }
                    ?: run {
                        val bytes = fetch(url)
                        val image = decodes.withPermit {
                            currentCoroutineContext().ensureActive()
                            decode(bytes)
                        }
                        currentCoroutineContext().ensureActive()
                        synchronized(lock) { if (revision == generation) cache.put(url, image) }
                        image
                    }
            }
        }
    }
}
