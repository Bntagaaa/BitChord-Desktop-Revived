package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlin.test.*

/** Real FFmpeg and decode worker with a collecting output (not a claim of audible playback). */
class DesktopInstantPlaybackEngineTest {
    private val files = mutableListOf<File>()
    private val engines = mutableListOf<DesktopPlaybackEngine>()
    @AfterTest fun cleanup() {
        engines.forEach { it.shutdown() }
        files.forEach { it.delete() }
        DesktopSleepTimer.cancel()
    }
    private fun song(id: String) = Song(id, id, "test", null)
    private fun tone(seconds: Int = 6, rate: Int = 44100): DesktopStream {
        val pcm = ByteArray(rate * 4 * seconds)
        for (i in pcm.indices step 2) { pcm[i] = 0x40; pcm[i+1] = 0x20 }
        val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(rate); putInt(rate * 4)
            putShort(4); putShort(16); put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
        val file = File.createTempFile("bitchord-engine-", ".wav").also { files += it; it.writeBytes(wav) }
        return DesktopStream(file.absolutePath)
    }
    private class Output(private val acceptsUpToHz: Int = Int.MAX_VALUE) : DesktopAudioOutput {
        override var format = DesktopPcmFormat(44100, 2, 2)
        override var gain = 1f
        @Volatile override var isOpen = false
        override val deviceName = "test collector"
        override val bufferBytes = 0
        val rates = CopyOnWriteArrayList<Int>()
        @Volatile var writes = 0
        private var frames = 0L
        override fun isUsingSelection(id: String) = isOpen
        override fun open(requested: DesktopPcmFormat): Result<DesktopPcmFormat> {
            format = requested; isOpen = true; rates += requested.sampleRate
            return Result.success(requested)
        }
        override fun write(samples: FloatArray, count: Int): Int {
            if (format.sampleRate > acceptsUpToHz) return 0
            assertTrue(samples.take(count).all { it.isFinite() })
            Thread.sleep((count.toLong() * 1000 / format.channels / format.sampleRate).coerceAtLeast(1))
            frames += count / format.channels; writes++
            return count * format.bytesPerSample
        }
        override fun framesPlayed() = frames
        override fun pause() {}
        override fun resume() {}
        override fun flush() {}
        override fun drain() {}
        override fun close() { isOpen = false }
    }
    private fun waitFor(description: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < end) { if (condition()) return; Thread.sleep(10) }
        error("timed out: $description")
    }
    private fun engine(output: Output, ended: () -> Unit = {}, handoff: (Song) -> Unit = {},
        resolve: suspend (Song) -> DesktopStream): DesktopPlaybackEngine =
        DesktopPlaybackEngine(ended, handoff, output) { song, _, _, _ -> Result.success(resolve(song)).map { DesktopLiveResolution(it) } }
            .also { engines += it }

    @Test fun `five rapid skips cancel an obsolete resolution and only latest reaches output`() {
        val stream = tone()
        val entered = CountDownLatch(1)
        val resolved = CopyOnWriteArrayList<String>()
        val output = Output()
        val engine = engine(output) { song ->
            if (song.videoId == "slow") { entered.countDown(); delay(10_000) }
            resolved += song.videoId
            stream
        }
        engine.load(song("slow"))
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        repeat(5) { engine.load(song("skip$it")) }
        waitFor("latest PCM") { engine.state.value.song?.videoId == "skip4" && output.writes > 0 }
        assertFalse("slow" in resolved)
        assertEquals("skip4", engine.state.value.song?.videoId)
        assertNull(engine.state.value.error)
    }

    @Test fun `ordinary prefetch is reused on manual next without resolving twice`() {
        val stream = tone()
        val requests = CopyOnWriteArrayList<String>()
        val output = Output()
        val engine = engine(output) { song -> requests += song.videoId; stream }
        engine.load(song("first"))
        waitFor("first PCM") { output.writes > 0 }
        engine.prepareNext(song("second"))
        waitFor("next resolved") { "second" in requests }
        Thread.sleep(300) // Allow the real native decoder to transfer ownership.
        engine.load(song("second"))
        waitFor("prepared selection") { engine.state.value.song?.videoId == "second" && !engine.state.value.isLoading }
        assertEquals(1, requests.count { it == "second" })
        assertNull(engine.state.value.error)
    }

    @Test fun `ordinary automatic handoff negotiates a changed sample rate`() {
        val first = tone(seconds = 3); val next = tone(rate = 48000)
        val output = Output(); val handed = CountDownLatch(1)
        val engine = engine(output, handoff = { handed.countDown() }) { if (it.videoId == "first") first else next }
        engine.load(song("first"))
        waitFor("first PCM") { output.writes > 0 }
        engine.prepareNext(song("second"))
        assertTrue(handed.await(10, TimeUnit.SECONDS))
        waitFor("new sample rate") { 48000 in output.rates }
        assertEquals("second", engine.state.value.song?.videoId)
        assertTrue(engine.state.value.positionMs >= 0)
        assertNull(engine.state.value.error)
    }

    @Test fun `queue change cancels a speculative resolve and does not start it later`() {
        val stream = tone(); val output = Output(); val entered = CountDownLatch(1)
        val resolved = CopyOnWriteArrayList<String>()
        val engine = engine(output) {
            if (it.videoId == "obsolete") { entered.countDown(); delay(10_000) }
            resolved += it.videoId; stream
        }
        engine.load(song("first")); waitFor("first PCM") { output.writes > 0 }
        engine.prepareNext(song("obsolete")); assertTrue(entered.await(4, TimeUnit.SECONDS))
        engine.prepareNext(song("replacement"))
        waitFor("replacement prepared") { "replacement" in resolved }
        assertFalse("obsolete" in resolved)
        assertEquals("first", engine.state.value.song?.videoId)
    }
    @Test fun `seek pause resume speed and output reconfigure still submit valid PCM`() {
        val stream = tone(); val output = Output()
        val engine = engine(output) { stream }
        engine.load(song("first")); waitFor("PCM") { output.writes > 0 }
        engine.pause(); Thread.sleep(150)
        val pausedWrites = output.writes
        Thread.sleep(100); assertEquals(pausedWrites, output.writes)
        engine.seekTo(3000); engine.setPlaybackSpeed(1.25f); engine.play()
        waitFor("seeked output") { output.writes > pausedWrites && engine.state.value.positionMs >= 2800 }
        engine.setOutputPrecision("FLOAT_32")
        waitFor("float route") { output.format.isFloat }
        assertNull(engine.state.value.error)
    }

    @Test fun `high resolution source falls back to 48k when output refuses PCM`() {
        val stream = tone(seconds = 6, rate = 192_000)
        val output = Output(acceptsUpToHz = 48_000)
        val engine = engine(output) { stream }
        engine.load(song("high-res"))
        waitFor("48k PCM fallback") {
            192_000 in output.rates && 48_000 in output.rates && output.writes > 0
        }
        assertTrue(engine.state.value.isPlaying, "player should resume after a safe-rate reopen")
        assertNull(engine.state.value.error)
    }

    @Test fun `player stops with an error if no output accepts any samples`() {
        val stream = tone(seconds = 6, rate = 192_000)
        val output = Output(acceptsUpToHz = 0)
        val engine = engine(output) { stream }
        engine.load(song("bad-output"))
        waitFor("failed output becomes visible") {
            !engine.state.value.isPlaying &&
                engine.state.value.error?.contains("accepting samples") == true
        }
        assertTrue(48_000 in output.rates, "the safe-rate attempt must happen before failing")
    }

    @Test fun `crossfade retains the incoming deck and advances queue once`() {
        val stream = tone(seconds = 3); val output = Output(); val handoffs = CopyOnWriteArrayList<String>()
        val engine = engine(output, handoff = { handoffs += it.videoId }) { stream }
        engine.setCrossfadeSeconds(1)
        engine.load(song("first")); waitFor("PCM") { output.writes > 0 }
        engine.prepareNext(song("second"))
        waitFor("crossfade handoff") { "second" in handoffs }
        Thread.sleep(200)
        assertEquals(1, handoffs.count { it == "second" })
        assertNull(engine.state.value.error)
    }

    @Test fun `sleep after track prevents ordinary prefetch bypassing track end`() {
        val stream = tone(seconds = 2); val output = Output(); val ended = CountDownLatch(1)
        val handoffs = CopyOnWriteArrayList<String>(); val requests = CopyOnWriteArrayList<String>()
        val engine = engine(output, ended = { ended.countDown() }, handoff = { handoffs += it.videoId }) {
            requests += it.videoId; stream
        }
        DesktopSleepTimer.startAfterTrack()
        engine.load(song("first")); waitFor("PCM") { output.writes > 0 }
        engine.prepareNext(song("second"))
        assertTrue(ended.await(6, TimeUnit.SECONDS))
        assertFalse("second" in requests)
        assertTrue(handoffs.isEmpty())
    }

    @Test fun `a canceled native network open releases the foreground gate promptly`() {
        val stream = tone(); val output = Output(); val entered = CountDownLatch(1)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/stall") { exchange ->
            entered.countDown()
            Thread.sleep(3000)
            runCatching { exchange.sendResponseHeaders(503, -1); exchange.close() }
        }
        server.start()
        try {
            val engine = engine(output) {
                if (it.videoId == "stalled") DesktopStream("http://127.0.0.1:${server.address.port}/stall", windowedReads = true)
                else stream
            }
            engine.load(song("stalled")); assertTrue(entered.await(4, TimeUnit.SECONDS))
            val start = System.nanoTime()
            engine.load(song("latest"))
            waitFor("native open canceled") { output.writes > 0 && engine.state.value.song?.videoId == "latest" }
            val elapsedMs = (System.nanoTime()-start)/1_000_000
            assertTrue(elapsedMs < 2000, "canceled HTTP open held the decoder gate for ${elapsedMs}ms")
            assertNull(engine.state.value.error)
        } finally { server.stop(0) }
    }

    @Test fun `an oversized server ignoring ranges falls back to native streaming`() {
        val stream = tone(); val bytes = File(stream.url).readBytes()
        assertTrue(bytes.size > DesktopRangeStream.DEFAULT_WINDOW)
        val output = Output()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tone") { exchange ->
            runCatching {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            val engine = engine(output) { DesktopStream("http://127.0.0.1:${server.address.port}/tone", windowedReads = true) }
            engine.load(song("first"))
            waitFor("native fallback PCM") { output.writes > 0 }
            assertNull(engine.state.value.error)
        } finally { server.stop(0) }
    }

    @Test fun `an expired speculative URL is resolved again on selection`() {
        val local = tone(); val bytes = File(local.url).readBytes(); val requests = CopyOnWriteArrayList<String>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tone") { exchange ->
            runCatching {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            val output = Output()
            val engine = engine(output) {
                requests += it.videoId
                if (it.videoId == "first") local else DesktopStream("http://127.0.0.1:${server.address.port}/tone?expire=1")
            }
            engine.load(song("first")); waitFor("first PCM") { output.writes > 0 }
            engine.prepareNext(song("second")); waitFor("expired prepared source") { "second" in requests }
            Thread.sleep(300)
            engine.load(song("second"))
            waitFor("refreshed selection") { requests.count { it == "second" } == 2 && !engine.state.value.isLoading }
            assertNull(engine.state.value.error)
        } finally { server.stop(0) }
    }

}
