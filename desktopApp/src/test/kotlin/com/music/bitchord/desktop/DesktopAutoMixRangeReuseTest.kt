package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopAutoMixRangeReuseTest {
    @Test
    fun playbackAndAutoMixShareTheSameCacheableAudioBytes() {
        val wave = waveFixture()
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val path = "/${UUID.randomUUID()}.wav"
        server.createContext(path) { exchange ->
            try {
                requests.incrementAndGet()
                val range = requireNotNull(exchange.requestHeaders.getFirst("Range"))
                val (start, end) = range.removePrefix("bytes=").split('-')
                val from = start.toInt()
                val until = minOf(end.toInt(), wave.lastIndex)
                val count = until - from + 1
                exchange.responseHeaders.set("Content-Range", "bytes $from-$until/${wave.size}")
                exchange.responseHeaders.set("Cache-Control", "public, max-age=60")
                exchange.sendResponseHeaders(206, count.toLong())
                exchange.responseBody.write(wave, from, count)
            } finally {
                exchange.close()
            }
        }
        server.start()
        val stream = DesktopStream("http://127.0.0.1:${server.address.port}$path",
            sourceId = "youtube", windowedReads = true, analysisVideoId = "fixture")
        val identity = stream.rangeCacheIdentity("fixture")
        val decoder = DesktopAudioDecoder()
        try {
            decoder.open(stream.url, requested = DesktopPcmFormat(44_100, 2, 4, isFloat = true),
                windowed = true, cacheIdentity = identity).getOrThrow()
            assertNotNull(decoder.readSamples())
            assertEquals(1, requests.get())
            var retrievalNanos = 0L
            val region = assertNotNull(
                desktopLoopRegion(stream, 0.25, 0.75, 44_100, identity, { retrievalNanos += it }) { false },
            )
            assertTrue(region.left.isNotEmpty())
            assertEquals(1, requests.get())
            assertEquals(0L, retrievalNanos)

            val different = stream.copy(sourceId = "different-source")
            assertNotNull(desktopLoopRegion(different, 0.1, 0.6, 44_100, different.rangeCacheIdentity("fixture")) { false })
            assertEquals(2, requests.get())
        } finally {
            decoder.close()
            server.stop(0)
        }
    }

    private fun waveFixture(): ByteArray {
        val rate = 44_100
        val pcmBytes = rate * 2 * 2
        return ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + pcmBytes)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(2)
            putInt(rate)
            putInt(rate * 4)
            putShort(4)
            putShort(16)
            put("data".toByteArray())
            putInt(pcmBytes)
            repeat(pcmBytes / 2) { putShort(2048) }
        }.array()
    }
}
