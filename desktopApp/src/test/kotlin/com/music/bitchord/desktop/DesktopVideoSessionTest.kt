package com.music.bitchord.desktop

import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.Song
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class DesktopVideoSessionTest {
    private class Decoder : DesktopVideoDecoder {
        override val width = 16
        override val height = 9
        @Volatile override var frameTimeMs = 0L
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val seeks = AtomicInteger()
        val eofReads = AtomicInteger()
        private var remaining = 0
        override fun open(stream: InnerTubeXResolver.VideoStream) { opens.incrementAndGet() }
        override fun seekTo(millis: Long): Boolean { frameTimeMs = millis; remaining = 1; seeks.incrementAndGet(); return true }
        override fun nextFrame(pixels: ByteArray): Boolean {
            if (remaining-- <= 0) { eofReads.incrementAndGet(); return false }
            return true
        }
        override fun close() { closes.incrementAndGet() }
    }
    private fun song(id: String, video: Boolean = true) = Song(id, id, "Artist", null, isVideo = video)
    private fun state(id: String) = DesktopPlaybackState(song = song(id), positionMs = 1000, seeks = 1)
    private suspend fun await(test: () -> Boolean) = withTimeout(3000) { while (!test()) delay(5) }
    private fun stream(id: String, height: Int) = InnerTubeXResolver.VideoStream(id, "memory:$id", 16, 9, "video/mp4", emptyMap(), listOf(height))

    @Test fun surfacesAndMinimizeRetainTheSameSessionAndFrame() = runBlocking {
        val clock = MutableStateFlow(state("video"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resolves = AtomicInteger()
        val decoder = Decoder()
        val session = DesktopVideoSession(scope, clock, { 1f }, { id, height -> resolves.incrementAndGet(); stream(id, height) }, { decoder })
        try {
            await { session.frame.value != null } // Warmed even before opening the player.
            val warm = session.frame.value
            session.attach("video")
            repeat(4) { session.detach("video"); delay(40); session.attach("video") }
            assertSame(warm, session.frame.value)
            assertEquals(1, resolves.get()); assertEquals(1, decoder.opens.get()); assertEquals(0, decoder.closes.get())
            session.detach("video")
            clock.value = clock.value.copy(positionMs = 90_000, seeks = 2)
            delay(80)
            assertSame(warm, session.frame.value)
            session.attach("video")
            await { decoder.frameTimeMs == 90_000L && session.frame.value !== warm }
            assertEquals(1, resolves.get()); assertEquals(1, decoder.opens.get())
        } finally {
            session.close(); await { decoder.closes.get() == 1 }; scope.cancel()
        }
    }

    @Test fun onlyTrackQualityAndStopReplaceOrCloseTheDecoder() = runBlocking {
        val clock = MutableStateFlow(state("a"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val decoders = CopyOnWriteArrayList<Decoder>()
        val session = DesktopVideoSession(scope, clock, { 1f }, { id, height -> stream(id, height) }, { Decoder().also(decoders::add) })
        try {
            await { session.frame.value?.first == "a" }
            session.held.value = 720
            await { decoders.size == 2 && decoders[1].opens.get() == 1 && session.frame.value != null }
            assertEquals(1, decoders[0].closes.get())
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
            await { session.failed.value }
            session.attach("a"); session.detach("a"); session.attach("a"); delay(80)
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
            object : DesktopVideoDecoder by decoder { override fun nextFrame(pixels: ByteArray) = false }
        })
        try {
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
            clock.value = clock.value.copy(isPlaying = true)
            await { decoder.eofReads.get() > 0 }
            clock.value = clock.value.copy(positionMs = 3000, seeks = 2)
            await { session.frame.value !== first && decoder.frameTimeMs == 3000L }
            assertEquals(1, attempts.get()); assertEquals(1, decoder.opens.get())
        } finally { session.close(); scope.cancel() }
    }
}
