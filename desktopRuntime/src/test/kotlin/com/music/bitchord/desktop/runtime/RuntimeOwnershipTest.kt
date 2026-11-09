package com.music.bitchord.desktop.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RuntimeOwnershipTest {
    @Test fun oversizedValuesRemainUsableWithoutDisplacingTheBudgetedCache() {
        val cache = ByteBudgetCache<String, ByteArray>(12) { it.size.toLong() }
        val small = ByteArray(8)
        cache.put("small", small)
        val huge = ByteArray(32)
        assertFalse(cache.put("huge", huge))
        assertSame(small, cache["small"])
        assertNull(cache["huge"])
        assertEquals(8L, cache.heldBytes)
        assertEquals(32, huge.size)
    }

    @Test fun replacementAndEvictionUseRecordedWeightsAndAccessOrder() {
        val cache = ByteBudgetCache<String, ByteArray>(12) { it.size.toLong() }
        cache.put("a", ByteArray(4)); cache.put("b", ByteArray(4)); cache.put("c", ByteArray(4))
        cache["a"]
        cache.put("d", ByteArray(4))
        assertNull(cache["b"])
        assertNotNull(cache["a"])
        cache.put("c", ByteArray(8))
        assertTrue(cache.heldBytes <= 12)
        cache.clear()
        assertEquals(0L, cache.heldBytes)
    }

    @Test fun weightArithmeticDoesNotOverflowAtLongMaxValue() {
        val cache = ByteBudgetCache<String, Long>(Long.MAX_VALUE) { it }
        cache.put("a", Long.MAX_VALUE - 1)
        cache.put("b", 10)
        assertEquals(10L, cache.heldBytes)
        assertNull(cache["a"])
    }

    @Test fun simultaneousReadersShareOneProducerAndTheSameDecodedObject() = runTest {
        SharedLoadCache<String, ByteArray>(backgroundScope, 64, { it.size.toLong() }).use { cache ->
            val ready = CompletableDeferred<Unit>()
            var decodes = 0
            val readers = List(12) { async { cache.load("cover") { decodes++; ready.await(); ByteArray(16) } } }
            runCurrent()
            assertEquals(1, decodes)
            ready.complete(Unit)
            val results = readers.awaitAll()
            results.forEach { assertSame(results.first(), it) }
            assertEquals(16L, cache.heldBytes)
            assertEquals(0, cache.pendingLoads)
        }
    }

    @Test fun cancellingOneScreenDoesNotCancelAnotherScreensSharedRequest() = runTest {
        SharedLoadCache<String, ByteArray>(backgroundScope, 64, { it.size.toLong() }).use { cache ->
            val ready = CompletableDeferred<Unit>()
            var cancelled = false
            val a = async { cache.load("cover") { try { ready.await(); ByteArray(8) } finally { cancelled = !ready.isCompleted } } }
            val b = async { cache.load("cover") { error("Duplicate producer") } }
            runCurrent()
            a.cancel(); runCurrent()
            assertFalse(cancelled)
            ready.complete(Unit)
            assertEquals(8, b.await()?.size)
        }
    }

    @Test fun removingTheLastReaderCancelsAndReleasesQueuedWork() = runTest {
        SharedLoadCache<String, ByteArray>(backgroundScope, 64, { it.size.toLong() }).use { cache ->
            val ready = CompletableDeferred<Unit>()
            val a = async { cache.load("cover") { ready.await(); ByteArray(8) } }
            runCurrent(); a.cancel(); runCurrent()
            assertEquals(0, cache.pendingLoads)
            assertEquals(0L, cache.heldBytes)
            assertEquals(4, cache.load("cover") { ByteArray(4) }?.size)
        }
    }

    @Test fun clearingDuringAnUninterruptibleDecodeCannotPublishAnObsoleteResult() = runTest {
        SharedLoadCache<String, ByteArray>(backgroundScope, 64, { it.size.toLong() }).use { cache ->
            val ready = CompletableDeferred<Unit>()
            val old = async { cache.load("cover") { withContext(NonCancellable) { ready.await(); ByteArray(32) } } }
            runCurrent(); cache.clear(cancelPending = true)
            val current = cache.load("cover") { ByteArray(8) }
            ready.complete(Unit); runCurrent()
            assertFailsWith<CancellationException> { old.await() }
            assertEquals(8L, cache.heldBytes)
            assertSame(current, cache.load("cover") { error("Already cached") })
        }
    }

    @Test fun clearingDoesNotStrandVisibleReadersOrRepopulateTheirOldGeneration() = runTest {
        SharedLoadCache<String, ByteArray>(backgroundScope, 64, { it.size.toLong() }).use { cache ->
            val ready = CompletableDeferred<Unit>()
            val visible = async { cache.load("cover") { ready.await(); ByteArray(8) } }
            runCurrent(); cache.clear(); ready.complete(Unit)
            assertEquals(8, visible.await()?.size)
            assertEquals(0L, cache.heldBytes)
            assertEquals(4, cache.load("cover") { ByteArray(4) }?.size)
        }
    }

    @Test fun distinctImagesCannotExceedTheDecodeConcurrencyLimit() = runTest {
        SharedLoadCache<String, ByteArray>(backgroundScope, 64, { it.size.toLong() }, 2).use { cache ->
            val ready = CompletableDeferred<Unit>()
            var active = 0
            var peak = 0
            val readers = List(8) { index -> async {
                cache.load("$index") {
                    active++; peak = maxOf(peak, active)
                    try { ready.await(); ByteArray(4) } finally { active-- }
                }
            } }
            runCurrent()
            assertEquals(2, active)
            ready.complete(Unit); readers.awaitAll()
            assertEquals(2, peak)
            assertEquals(0, active)
        }
    }

    @Test fun failedRequestsDoNotPoisonRetriesOrOtherKeys() = runTest {
        SharedLoadCache<String, ByteArray>(backgroundScope, 64, { it.size.toLong() }).use { cache ->
            assertFailsWith<IllegalArgumentException> { cache.load("bad") { throw IllegalArgumentException("failed") } }
            assertEquals(3, cache.load("bad") { ByteArray(3) }?.size)
            assertEquals(5, cache.load("other") { ByteArray(5) }?.size)
        }
    }

    @Test fun applicationCloseCancelsJobsAndReleasesResourcesOnceInReverseOrder() = runTest {
        val order = mutableListOf<String>()
        val owner = DesktopResourceOwner(StandardTestDispatcher(testScheduler)) { _, _ -> error("Logger failed") }
        owner.own("first") { AutoCloseable { order += "first" } }
        owner.own("last") { AutoCloseable { order += "last"; error("Close failed") } }
        val worker = owner.scope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
        owner.close(); owner.close(); runCurrent()
        assertEquals(listOf("last", "first"), order)
        assertTrue(worker.isCancelled)
        assertFailsWith<IllegalStateException> { owner.own("later") { AutoCloseable {} } }
    }
}
