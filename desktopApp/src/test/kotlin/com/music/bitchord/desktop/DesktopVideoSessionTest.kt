package com.music.bitchord.desktop

import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.Song
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class DesktopVideoSessionTest {
    private class Decoder : DesktopVideoDecoder {
        @Volatile override var frameTimeMs = 0L
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val seeks = AtomicInteger()
        val eofReads = AtomicInteger()
        private var remaining = 0
        override fun open(stream: InnerTubeXResolver.VideoStream) { opens.incrementAndGet() }
        override fun seekTo(millis: Long): Boolean { frameTimeMs = millis; remaining = 1; seeks.incrementAndGet(); return true }
        override fun nextFrame(accept: (Long) -> Boolean): VideoFrame? {
            if (remaining-- <= 0) { eofReads.incrementAndGet(); return null }
            return if (accept(frameTimeMs)) VideoFrame(16, 9, ByteArray(16 * 9 * 4)) else null
        }
        override fun close() { closes.incrementAndGet() }
    }
    private fun song(id: String, video: Boolean = true) = Song(id, id, "Artist", null, isVideo = video)
    private fun state(id: String) = DesktopPlaybackState(song = song(id), positionMs = 1000, seeks = 1)
    private suspend fun await(test: () -> Boolean) = withTimeout(3000) { while (!test()) delay(5) }
    private fun stream(id: String, height: Int) = InnerTubeXResolver.VideoStream(id, "memory:$id", 16, 9, "video/mp4", emptyMap(), listOf(height))

    @Test fun disabledBetaDoesNotOpenVideoAndTogglingOffReleasesOnlyPicture() = runBlocking {
        val clock = MutableStateFlow(state("video"))
        val beta = MutableStateFlow(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val decoder = Decoder()
        val attempts = AtomicInteger()
        val session = DesktopVideoSession(scope, clock, { 1f }, { id, height -> attempts.incrementAndGet(); stream(id, height) },
            { decoder }, enabled = beta)
        try {
            session.attach("video")
            delay(100)
            assertEquals(0, attempts.get()); assertEquals(0, decoder.opens.get())
            beta.value = true
            await { session.frame.value != null }
            assertEquals(1, attempts.get()); assertEquals(1, decoder.opens.get())
            beta.value = false
            await { session.frame.value == null && decoder.closes.get() == 1 }
            assertEquals("video", clock.value.song?.videoId)
        } finally { session.close(); scope.cancel() }
    }

    @Test fun surfacesReuseSessionDuringHandoffAndReleaseItWhenHidden() = runBlocking {
        val clock = MutableStateFlow(state("video"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resolves = AtomicInteger()
        val decoder = Decoder()
        val session = DesktopVideoSession(scope, clock, { 1f }, { id, height -> resolves.incrementAndGet(); stream(id, height) }, { decoder })
        try {
            delay(100)
            assertEquals(0, resolves.get())
            session.attach("video")
            await { session.frame.value != null }
            val warm = session.frame.value
            // PiP mounts before the main surface is hidden. The remaining lease keeps the picture.
            session.attach("video")
            assertEquals(2, session.resources().surfaces)
            session.detach("video")
            assertEquals(1, session.resources().surfaces)
            assertEquals(0, session.resources().handoffs)
            repeat(30) { session.detach("video"); delay(40); session.attach("video") }
            assertSame(warm, session.frame.value, "opens=${decoder.opens.get()} closes=${decoder.closes.get()} seeks=${decoder.seeks.get()} failed=${session.failed.value}")
            assertEquals(1, resolves.get()); assertEquals(1, decoder.opens.get()); assertEquals(0, decoder.closes.get())
            session.detach("video")
            clock.value = clock.value.copy(positionMs = 90_000, seeks = 2)
            delay(80)
            assertSame(warm, session.frame.value, "opens=${decoder.opens.get()} closes=${decoder.closes.get()} seeks=${decoder.seeks.get()} failed=${session.failed.value}")
            session.attach("video")
            await { decoder.frameTimeMs == 90_000L && session.frame.value !== warm }
            assertEquals(1, resolves.get()); assertEquals(1, decoder.opens.get())
            session.detach("video")
            await { session.frame.value == null && decoder.closes.get() == 1 }
        } finally {
            session.close(); scope.cancel()
        }
    }

    @Test fun onlyTrackQualityAndStopReplaceOrCloseTheDecoder() = runBlocking {
        val clock = MutableStateFlow(state("a"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val decoders = CopyOnWriteArrayList<Decoder>()
        val session = DesktopVideoSession(scope, clock, { 1f }, { id, height -> stream(id, height) }, { Decoder().also(decoders::add) })
        try {
            session.attach("a")
            await { session.frame.value?.first == "a" }
            session.held.value = 720
            await { decoders.size == 2 && decoders[1].opens.get() == 1 && session.frame.value != null }
            assertEquals(1, decoders[0].closes.get())
            session.attach("b")
            clock.value = state("b")
            await { session.frame.value?.first == "b" }
            assertEquals(3, decoders.size); assertEquals(1, decoders[1].closes.get())
            clock.value = DesktopPlaybackState(song = song("audio", video = false))
            await { session.frame.value == null && decoders.last().closes.get() == 1 }
            assertTrue(decoders.all { it.closes.get() == 1 })
        } finally { session.close(); scope.cancel() }
    }

    @Test fun failedResolutionRequiresExplicitRetryNotAnotherView() = runBlocking {
        val clock = MutableStateFlow(state("a"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val attempts = AtomicInteger()
        val session = DesktopVideoSession(scope, clock, { 1f }, { id, height ->
            if (attempts.incrementAndGet() == 1) error("Offline") else stream(id, height)
        }, { Decoder() })
        try {
            session.attach("a")
            await { session.failed.value }
            session.detach("a"); session.attach("a"); delay(80)
            assertEquals(1, attempts.get())
            session.retry()
            await { session.frame.value != null }
            assertFalse(session.failed.value); assertEquals(2, attempts.get())
        } finally { session.close(); scope.cancel() }
    }

    @Test fun anEmptyVideoFailsVisiblyInsteadOfLoadingForever() = runBlocking {
        val clock = MutableStateFlow(state("empty"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val decoder = Decoder()
        val session = DesktopVideoSession(scope, clock, { 1f }, { id, height -> stream(id, height) }, {
            object : DesktopVideoDecoder by decoder { override fun nextFrame(accept: (Long) -> Boolean): VideoFrame? = null }
        })
        try {
            session.attach("empty")
            await { session.failed.value && decoder.closes.get() == 1 }
            assertNull(session.frame.value)
            assertEquals(1, decoder.opens.get())
        } finally { session.close(); scope.cancel() }
    }

    @Test fun seekingAfterEndDoesNotOpenAnotherStream() = runBlocking {
        val clock = MutableStateFlow(state("a"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val decoder = Decoder()
        val attempts = AtomicInteger()
        val session = DesktopVideoSession(scope, clock, { 1f }, { id, height -> attempts.incrementAndGet(); stream(id, height) }, { decoder })
        try {
            session.attach("a")
            await { session.frame.value != null }
            val first = session.frame.value
            session.withFrame("a") {}
            clock.value = clock.value.copy(isPlaying = true)
            await { decoder.eofReads.get() > 0 }
            clock.value = clock.value.copy(positionMs = 3000, seeks = 2)
            await { session.frame.value !== first && decoder.frameTimeMs == 3000L }
            assertEquals(1, attempts.get()); assertEquals(1, decoder.opens.get())
        } finally { session.close(); scope.cancel() }
    }

    @Test fun presentationBackpressureAndRepeatedPausedSeeksKeepOneDecoder() = runBlocking {
        val clock = MutableStateFlow(state("video").copy(isPlaying = true))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val decoder = Decoder()
        val session = DesktopVideoSession(scope, clock, { 1f }, ::stream, { decoder })
        try {
            session.attach("video")
            await { session.presentation.value != null }
            // No consumer has recorded this frame yet, so nothing else is decoded.
            delay(80)
            assertEquals(0, decoder.eofReads.get())
            session.withFrame("video") {}
            await { decoder.eofReads.get() > 0 }

            repeat(4) { index ->
                val previous = session.presentation.value
                val position = (index + 2) * 1000L
                clock.value = clock.value.copy(isPlaying = false, positionMs = position,
                    seeks = clock.value.seeks + 1)
                await { session.presentation.value != previous && decoder.frameTimeMs == position }
                val reads = decoder.eofReads.get()
                session.withFrame("video") {}
                delay(30)
                assertEquals(reads, decoder.eofReads.get())
                clock.value = clock.value.copy(isPlaying = true)
                await { decoder.eofReads.get() > reads }
            }
            assertEquals(1, decoder.opens.get())
        } finally { session.close(); scope.cancel() }
    }

    @Test fun repeatedLastSurfaceCloseClearsWorkersAndPresentation() = runBlocking {
        val clock = MutableStateFlow(state("video"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val decoders = CopyOnWriteArrayList<Decoder>()
        val session = DesktopVideoSession(scope, clock, { 1f }, { id, height ->
            assertEquals(1080, height)
            stream(id, height)
        }, { Decoder().also(decoders::add) })
        try {
            repeat(30) {
                session.attach("video")
                await { session.presentation.value != null }
                assertEquals(1, session.resources().publishedFrames)
                session.detach("video")
                await { session.resources() == MusicVideoResources(0, 0, 0, 0, 0, 0) }
                assertNull(session.frame.value)
                assertNull(session.presentation.value)
            }
            assertEquals(30, decoders.size)
            assertTrue(decoders.all { it.opens.get() == 1 && it.closes.get() == 1 })
        } finally { session.close(); scope.cancel() }
    }

    @Test fun cancelledDecodeCannotPublishAfterTheLastSurface() = runBlocking {
        val clock = MutableStateFlow(state("video"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val delivered = CompletableDeferred<VideoFrame>()
        val session = DesktopVideoSession(scope, clock, { 1f }, { id, height -> stream(id, height) }, {
            object : DesktopVideoDecoder by Decoder() {
                override fun nextFrame(accept: (Long) -> Boolean): VideoFrame {
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                    return VideoFrame(2, 2, ByteArray(16)).also { delivered.complete(it) }
                }
            }
        })
        try {
            session.attach("video")
            withTimeout(3000) { entered.await() }
            session.detach("video")
            await { session.resources().handoffs == 0 }
            release.countDown()
            val late = withTimeout(3000) { delivered.await() }
            await { session.resources() == MusicVideoResources(0, 0, 0, 0, 0, 0) }
            assertNull(session.presentation.value)
            assertNull(late.sample(2))
        } finally {
            release.countDown()
            session.close()
            scope.cancel()
        }
    }

    @Test fun actualAudioEndReleasesThePictureWhileSurfaceRemainsMounted() = runBlocking {
        val clock = MutableStateFlow(state("video").copy(durationMs = 6_000))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val decoder = Decoder()
        val session = DesktopVideoSession(scope, clock, { 1f }, { id, height -> stream(id, height) }, { decoder })
        try {
            session.attach("video")
            await { session.presentation.value != null }
            clock.value = clock.value.copy(positionMs = 6_000, isPlaying = false)
            await { session.resources() == MusicVideoResources(0, 0, 1, 0, 0, 0) }
            assertEquals(1, decoder.closes.get())
        } finally { session.close(); scope.cancel() }
    }

    @Test fun closingDuringDrawWaitsForTheCurrentImage() = runBlocking {
        val clock = MutableStateFlow(state("video"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val decoder = Decoder()
        val session = DesktopVideoSession(scope, clock, { 1f }, ::stream, { decoder })
        val entered = CompletableDeferred<Unit>()
        val closing = CompletableDeferred<Unit>()
        val allowDraw = CountDownLatch(1)
        try {
            session.attach("video")
            await { session.frame.value != null }
            val frame = checkNotNull(session.frame.value).second
            val draw = async(Dispatchers.IO) {
                session.withFrame("video") {
                    entered.complete(Unit)
                    check(allowDraw.await(5, TimeUnit.SECONDS))
                    Surface.makeRasterN32Premul(16, 9).use { target ->
                        assertTrue(it.drawTo(target.canvas, Rect.makeWH(16f, 9f)))
                    }
                }
            }
            try {
                withTimeout(3000) { entered.await() }
                val close = async(Dispatchers.IO) {
                    closing.complete(Unit)
                    session.close()
                }
                withTimeout(3000) { closing.await() }
                assertFalse(close.isCompleted)
                allowDraw.countDown()
                close.await()
                draw.await()
                await { session.resources() == MusicVideoResources(0, 0, 0, 0, 0, 0) }
                assertNull(frame.sample(2))
            } finally { allowDraw.countDown() }
        } finally {
            session.close()
            scope.cancel()
        }
    }
}
