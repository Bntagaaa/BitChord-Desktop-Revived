package com.music.bitchord.reworked

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RangedMediaInputTest {
    @Test fun shortRangeResponsesContinueAndSeekingUsesTheNewPosition() {
        val content = ByteArray(90_000) { (it % 251).toByte() }
        val requests = CopyOnWriteArrayList<Long>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audio") { exchange ->
            val start = exchange.requestHeaders.getFirst("Range").substringAfter("bytes=").substringBefore('-').toLong()
            requests += start
            val end = minOf(start + 12_000, content.size.toLong())
            exchange.responseHeaders.add("Content-Range", "bytes $start-${end - 1}/${content.size}")
            exchange.sendResponseHeaders(206, end - start)
            exchange.responseBody.use { it.write(content, start.toInt(), (end - start).toInt()) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/audio?clen=${content.size}"
            RangedMediaInput(url, emptyMap(), { false }).use { input ->
                val received = ByteArray(content.size)
                val block = ByteArray(8_192)
                var count = 0
                while (true) {
                    val read = input.read(block, block.size)
                    if (read < 0) break
                    block.copyInto(received, count, 0, read)
                    count += read
                }
                assertEquals(content.size, count)
                assertContentEquals(content, received)
                assertNull(input.failure)
                assertTrue(requests.size > 1)
                input.position = 17_321
                val read = input.read(block, block.size)
                assertTrue(read > 0)
                assertContentEquals(content.copyOfRange(17_321, 17_321 + read), block.copyOf(read))
            }
        } finally { server.stop(0) }
    }

    @Test fun incorrectRangeIsReportedAsAnErrorAndNeverAsValidAudio() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/wrong") { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes 0-15/100")
            exchange.sendResponseHeaders(206, 16)
            exchange.responseBody.use { it.write(ByteArray(16)) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/wrong?clen=100"
            RangedMediaInput(url, emptyMap(), { false }).use { input ->
                input.position = 30
                assertEquals(-1, input.read(ByteArray(16), 16))
                assertNotNull(input.failure)
                assertTrue(input.failure!!.message!!.contains("wrong byte range"))
            }
        } finally { server.stop(0) }
    }
}
