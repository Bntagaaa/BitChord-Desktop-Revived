package com.music.bitchord.desktop.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KeyedResourceRegistryTest {
    private class Handle : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun concurrentReadersShareOneCreationAndNeverEnterTheHandleConcurrently() = runTest {
        val registry = KeyedResourceRegistry<String, Handle>()
        var creations = 0
        var active = 0
        var peak = 0
        val results = List(12) {
            async {
                registry.withResource("js", { creations++; delay(1); Handle() }) { handle ->
                    active++; peak = maxOf(peak, active)
                    delay(1)
                    active--
                    handle
                }
            }
        }.awaitAll()
        assertEquals(1, creations)
        assertEquals(1, peak)
        results.forEach { assertSame(results.first(), it) }
        registry.close(); registry.close()
        assertEquals(1, results.first().closes)
    }

    @Test fun reloadRetiresActiveHandleWithoutClosingUnderItsCaller() = runTest {
        val registry = KeyedResourceRegistry<String, Handle>()
        val old = Handle()
        val release = CompletableDeferred<Unit>()
        val call = async { registry.withResource("js", { old }) { release.await() } }
        runCurrent()
        registry.clear()
        assertEquals(0, old.closes)
        val replacement = registry.withResource("js", { Handle() }) { it }
        assertNotSame(old, replacement)
        release.complete(Unit); call.await()
        assertEquals(1, old.closes)
        assertEquals(0, replacement.closes)
        registry.close()
        assertEquals(1, replacement.closes)
    }

    @Test fun staleNativeCreationIsClosedRatherThanPublishedAfterReload() = runTest {
        val registry = KeyedResourceRegistry<String, Handle>()
        val created = Handle()
        val ready = CompletableDeferred<Unit>()
        val call = async {
            assertFailsWith<CancellationException> {
                registry.withResource("js", {
                    withContext(NonCancellable) { ready.await(); created }
                }) { fail("retired resource must never be used") }
            }
        }
        runCurrent(); registry.clear(); ready.complete(Unit); call.await()
        assertEquals(1, created.closes)
        registry.close()
    }

    @Test fun failedCreationCanRetryAndCloseRejectsNewCalls() = runTest {
        val registry = KeyedResourceRegistry<String, Handle>()
        assertFailsWith<IllegalArgumentException> {
            registry.withResource("js", { throw IllegalArgumentException("invalid script") }) { }
        }
        val handle = registry.withResource("js", { Handle() }) { it }
        registry.close()
        assertEquals(1, handle.closes)
        assertFailsWith<IllegalStateException> {
            registry.withResource("js", { Handle() }) { }
        }
    }
}
