package com.music.bitchord.desktop.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One producer per key, bounded concurrent work, and an explicit application owner. */
class SharedLoadCache<K : Any, V : Any>(
    owner: CoroutineScope,
    budgetBytes: Long,
    sizeOf: (V) -> Long,
    maxConcurrentLoads: Int = 4,
) : AutoCloseable {
    init { require(maxConcurrentLoads > 0) }
    private class Flight<V>(val result: Deferred<V?>, var readers: Int = 0)
    private val lock = Any()
    private val job = SupervisorJob(owner.coroutineContext[Job])
    private val scope = CoroutineScope(owner.coroutineContext + job)
    private val permits = Semaphore(maxConcurrentLoads)
    private val values = ByteBudgetCache<K, V>(budgetBytes, sizeOf)
    private val flights = mutableMapOf<K, Flight<V>>()
    private var generation = 0L
    private var closed = false
    val heldBytes: Long get() = values.heldBytes
    val cachedEntries: Int get() = values.size
    val pendingLoads: Int get() = synchronized(lock) { flights.size }

    suspend fun load(key: K, producer: suspend () -> V?): V? {
        val flight = synchronized(lock) {
            check(!closed) { "Cache is closed" }
            values[key]?.let { return it }
            val entry = flights.getOrPut(key) {
                val epoch = generation
                Flight(scope.async(start = CoroutineStart.LAZY) {
                    permits.withPermit {
                        val loaded = producer()
                        currentCoroutineContext().ensureActive()
                        synchronized(lock) {
                            if (!closed && epoch == generation && loaded != null) values.put(key, loaded)
                        }
                        loaded
                    }
                })
            }
            entry.readers++
            entry
        }
        flight.result.start()
        try {
            return flight.result.await()
        } finally {
            synchronized(lock) {
                flight.readers--
                if (flight.readers == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    if (!flight.result.isCompleted) flight.result.cancel()
                }
            }
        }
    }

    /** In-flight work from the old generation cannot repopulate a cleared cache. */
    fun clear(cancelPending: Boolean = false) {
        val pending = synchronized(lock) {
            generation++
            values.clear()
            flights.values.toList().also { flights.clear() }
        }
        // Visible readers may still need their image. Invalidation prevents
        // retention without stranding a produceState awaiting that result.
        if (cancelPending) pending.forEach { it.result.cancel() }
    }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true }
        clear(cancelPending = true)
        scope.cancel()
    }
}
