package com.music.bitchord.desktop

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopArtworkLoadGateTest {

    @Test
    fun cancelledOffscreenRequestNeverStartsAfterSlotBecomesAvailable() = runBlocking {
        val gate = DesktopArtworkLoadGate(maxConcurrentLoads = 1, urlStripes = 8)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            gate.forUrl("visible") {
                started.complete(Unit)
                release.await()
            }
        }

        withTimeout(2_000) { started.await() }
        val staleWasExecuted = AtomicBoolean(false)
        val stale = launch {
            gate.forUrl("offscreen") { staleWasExecuted.set(true) }
        }
        yield()
        stale.cancelAndJoin()
        release.complete(Unit)
        withTimeout(2_000) { first.join() }

        assertFalse(staleWasExecuted.get(), "a disposed row triggered a stale network request")
    }

    @Test
    fun simultaneousRequestsForSameUrlAreSerializedForCacheReuse() = runBlocking {
        val gate = DesktopArtworkLoadGate(maxConcurrentLoads = 4, urlStripes = 8)
        var isLoading = false
        var overlap = false
        var cacheValue: Int? = null
        var downloads = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        suspend fun load(): Int = gate.forUrl("same-cover") {
            cacheValue ?: run {
                if (isLoading) overlap = true
                isLoading = true
                started.complete(Unit)
                release.await()
                downloads++
                cacheValue = 42
                isLoading = false
                42
            }
        }

        val first = async { load() }
        withTimeout(2_000) { started.await() }
        val second = async { load() }
        yield()
        release.complete(Unit)
        assertEquals(42, withTimeout(2_000) { first.await() })
        assertEquals(42, withTimeout(2_000) { second.await() })
        assertEquals(1, downloads)
        assertFalse(overlap)
    }
}
