package com.music.bitchord.desktop

import androidx.compose.ui.geometry.Size
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopMotionHlsTest {
    @Test
    fun cachedByteRangePlaylistDecodesThreeCompletePasses() = withOrigin { origin ->
        val playlist = assertNotNull(DesktopCanvasCache.hlsFileFor(origin.primary, EDGE))
        assertEquals(playlist, DesktopCanvasCache.hlsFileIfCached(origin.primary, EDGE))
        val body = Files.readString(playlist)
        assertTrue("#EXT-X-MAP:" in body && "#EXT-X-BYTERANGE:" in body)
        origin.online.set(false)
        repeat(3) { pass ->
            val input = if (pass == 1) playlist.toUri().toString() else playlist.toString()
            DesktopCanvasDecoder(maxEdge = EDGE, loopAtEnd = false).use { decoder ->
                decoder.open(input).getOrThrow()
                var count = 0
                while (true) {
                    val frame = decoder.nextFrame() ?: break
                    frame.use {
                        assertEquals(EDGE, it.width)
                        assertEquals(EDGE, it.height)
                    }
                    count++
                }
                assertEquals(10, count, "pass ${pass + 1}")
            }
        }
    }

    @Test
    fun sessionSwitchesFromStreamingToCacheAndKeepsLooping() = withOrigin { origin ->
        withSession(origin) { lease ->
            lease.awaitSquareFrame(1)
            assertTrue(origin.remoteRequests.get() > 0, "the first pass must stream")
            awaitCache(origin)
            origin.online.set(false)
            lease.awaitSquareFrame(36)
            assertEquals(0, origin.fallbackRequests.get())
        }
    }

    @Test
    fun corruptCacheAfterFirstFrameRetriesTheProvenPrimary() = withOrigin { origin ->
        withSession(origin) { lease ->
            lease.awaitSquareFrame(1)
            val playlist = awaitCache(origin)
            val corrupt = "#EXTM3U\n#EXT-X-ENDLIST\n"
            Files.writeString(playlist, corrupt)
            val requests = origin.remoteRequests.get()
            lease.awaitSquareFrame(36)
            assertTrue(origin.remoteRequests.get() > requests, "recovery must stream the primary")
            assertEquals(0, origin.fallbackRequests.get())
            assertTrue(!Files.exists(playlist) || Files.readString(playlist) != corrupt)
        }
    }

    private fun withSession(origin: Origin, block: suspend (MotionVideoSessions.Lease) -> Unit) = runBlocking {
        val source = MotionVideoSource(origin.primary, origin.fallback, EDGE, MotionVideoSource.Clock.NowPlaying)
        try {
            UnifiedVideoEngine.motion(source, running = true).use { block(it) }
        } finally {
            // The worker can still own a native file handle when the last lease closes.
            withTimeout(5_000) {
                while (UnifiedVideoEngine.hasMotionSession(source)) delay(10)
            }
        }
    }

    private suspend fun MotionVideoSessions.Lease.awaitSquareFrame(sequence: Long) {
        withTimeout(12_000) {
            presentation.first { info ->
                info ?: return@first false
                withFrame { frame ->
                    assertEquals(EDGE, frame.width)
                    assertEquals(EDGE, frame.height)
                }
                assertEquals(Size(EDGE.toFloat(), EDGE.toFloat()), info.size)
                info.sequence >= sequence
            }
        }
    }

    private suspend fun awaitCache(origin: Origin) = withTimeout(5_000) {
        val target = DesktopMediaCache.pathFor("$EDGE|${origin.primary}", "m3u8")
        while (!Files.isRegularFile(target)) delay(10)
        target
    }

    private class Origin(base: String) {
        val primary = "$base/square.m3u8"
        val fallback = "$base/tall.m3u8"
        val online = AtomicBoolean(true)
        val remoteRequests = AtomicInteger()
        val fallbackRequests = AtomicInteger()
    }

    private fun withOrigin(block: (Origin) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val prefix = "/${UUID.randomUUID()}"
        val base = "http://127.0.0.1:${server.address.port}$prefix"
        val origin = Origin(base)
        for ((name, segmentLength) in listOf("square" to 757, "tall" to 747)) {
            val segment = checkNotNull(javaClass.getResourceAsStream("/video/$name.m4s")).use { it.readBytes() }
            val master = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=${if (name == "square") "64x64" else "48x64"},CODECS="avc1.4d401f"
                $name-media.m3u8
            """.trimIndent()
            val media = """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-TARGETDURATION:1
                #EXT-X-MAP:URI="$name.m4s",BYTERANGE="826@0"
                #EXTINF:1.0,
                #EXT-X-BYTERANGE:$segmentLength@826
                $name.m4s
                #EXT-X-ENDLIST
            """.trimIndent()
            for ((file, bytes) in mapOf(
                "$name.m3u8" to master.toByteArray(),
                "$name-media.m3u8" to media.toByteArray(),
                "$name.m4s" to segment,
            )) {
                server.createContext("$prefix/$file") { exchange ->
                    val range = exchange.requestHeaders.getFirst("Range")
                    if (name == "tall") origin.fallbackRequests.incrementAndGet()
                    if (name == "square" && file.endsWith(".m4s") && range != null) {
                        origin.remoteRequests.incrementAndGet()
                    }
                    if (name == "square" && !origin.online.get()) {
                        exchange.sendResponseHeaders(503, -1)
                        exchange.close()
                    } else {
                        val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        val end = range?.substringAfter('-')?.toIntOrNull() ?: (bytes.size - 1)
                        if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $start-$end/${bytes.size}")
                        exchange.sendResponseHeaders(if (range == null) 200 else 206, (end - start + 1).toLong())
                        exchange.responseBody.use { it.write(bytes, start, end - start + 1) }
                    }
                }
            }
        }
        server.start()
        try {
            block(origin)
        } finally {
            server.stop(0)
            for (name in listOf("square", "tall")) {
                Files.deleteIfExists(DesktopMediaCache.pathFor("$EDGE|$base/$name.m3u8", "m3u8"))
                Files.deleteIfExists(DesktopMediaCache.pathFor("$base/$name.m4s", "m4s"))
            }
        }
    }

    private companion object {
        // Downscaling exercises the decoder's two-frame-thread path and fixed output geometry.
        const val EDGE = 48
    }
}
