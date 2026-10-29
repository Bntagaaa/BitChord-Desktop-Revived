package com.music.bitchord.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** One operation per key. The last departing consumer cancels abandoned work. */
internal class DesktopSingleFlight<K, V> {
    private class Flight<V>(val result: Deferred<V>, var users: Int = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flights = HashMap<K, Flight<V>>()

    fun cancelAll() = synchronized(flights) {
        val old = flights.values.toList()
        flights.clear()
        old.forEach { it.result.cancel() }
    }

    suspend fun get(key: K, load: suspend () -> V): V {
        val flight = synchronized(flights) {
            flights.getOrPut(key) { Flight(scope.async(start = CoroutineStart.LAZY) { load() }) }
                .also { it.users++ }
        }
        try {
            return flight.result.await()
        } finally {
            synchronized(flights) {
                if (--flight.users == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    flight.result.cancel()
                }
            }
        }
    }
}
