package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertNotNull

/** Opt-in, deterministic transport/decode benchmark. No claim of acoustic output latency. */
class DesktopPlaybackBenchmarkTest {
    @Test fun `range transport to first real PCM`() {
        if (System.getenv("BITCHORD_PLAYBACK_BENCHMARK") != "1") return
        val pcm = ByteArray(44100 * 4 * 12) { (it % 127).toByte() }
        val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(44100); putInt(44100 * 4)
            putShort(4); putShort(16); put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tone.wav") { exchange ->
            requests.incrementAndGet()
            Thread.sleep(40) // Identical artificial network latency in both revisions.
            val range = exchange.requestHeaders.getFirst("Range").removePrefix("bytes=").split('-')
            val start = range[0].toInt(); val end = minOf(range[1].toInt(), wav.lastIndex)
            exchange.responseHeaders.add("Cache-Control", "public, max-age=3600")
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${wav.size}")
            exchange.sendResponseHeaders(206, (end - start + 1).toLong())
            exchange.responseBody.use { it.write(wav, start, end - start + 1) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/tone.wav"
            repeat(10) { trial ->
                val decoder = DesktopAudioDecoder()
                val start = System.nanoTime(); val before = requests.get()
                try {
                    decoder.open(url, requested = DesktopPcmFormat(44100, 2, 4, true), windowed = true).getOrThrow()
                    val opened = System.nanoTime()
                    assertNotNull(decoder.readSamples())
                    println("PLAYBACK_BENCH trial=$trial openMs=${(opened-start)/1e6} firstPcmMs=${(System.nanoTime()-start)/1e6} requests=${requests.get()-before}")
                } finally { decoder.close() }
            }
        } finally { server.stop(0) }
    }
}
