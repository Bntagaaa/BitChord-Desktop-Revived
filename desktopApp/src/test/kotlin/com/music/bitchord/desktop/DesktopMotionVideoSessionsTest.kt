package com.music.bitchord.desktop

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class DesktopMotionVideoSessionsTest {
    private val source = MotionVideoSource("fixture", null, 540, MotionVideoSource.Clock.NowPlaying, "track")

    @Test fun sessionSharingHonorsClockAndReleasesAtLastOwner() = runBlocking {
        val starts = AtomicInteger()
        val closes = AtomicInteger()
        val sessions = MotionVideoSessions(this) { _, publish, running ->
            running.first { it }
            starts.incrementAndGet()
            val frame = VideoFrame(2, 2, ByteArray(16))
            try {
                publish(frame)
                awaitCancellation()
            } finally {
                closes.incrementAndGet()
            }
        }
        sessions.attach(source, true).use { first ->
            sessions.attach(source, false).use { second ->
                val frame = withTimeout(3000) { first.frame.first { it != null } }
                assertSame(frame, second.frame.value)
                assertEquals(1, starts.get())
                sessions.attach(source.copy(track = "other"), true).use { other ->
                    assertNotNull(withTimeout(3000) { other.frame.first { it != null } })
                    assertEquals(2, starts.get())
                }
                first.close()
                assertSame(frame, second.frame.value)
            }
            assertNull(first.frame.value)
        }
        withTimeout(3000) { while (closes.get() < 2) delay(5) }
        assertEquals(2, closes.get())
    }

    @Test fun fastReentryWaitsForOldNativeCleanupEvenIfIntermediateLeaseCloses() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val closing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reopened = CompletableDeferred<Unit>()
        val sessions = MotionVideoSessions(this) { _, _, _ ->
            if (started.complete(Unit)) {
                try {
                    awaitCancellation()
                } finally {
                    closing.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            } else {
                reopened.complete(Unit)
                awaitCancellation()
            }
        }
        sessions.attach(source, true).use { first ->
            withTimeout(3000) { started.await() }
            first.close()
            withTimeout(3000) { closing.await() }
            sessions.attach(source, true).close()
            try {
                sessions.attach(source, true).use {
                    delay(50)
                    assertEquals(false, reopened.isCompleted)
                    release.complete(Unit)
                    withTimeout(3000) { reopened.await() }
                }
            } finally {
                release.complete(Unit)
            }
        }
    }
}
