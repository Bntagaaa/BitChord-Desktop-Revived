package com.music.bitchord.desktop

import coil3.PlatformContext
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Size
import com.music.bitchord.ui.player.currentTrackArtwork
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class DesktopArtworkRequestsTest {
    @Test
    fun transientFailureRecoversAndConcurrentReadersReuseTheImage() = runBlocking {
        val png = ByteArrayOutputStream().also {
            check(ImageIO.write(BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB), "png", it))
        }.toByteArray()
        val attempts = AtomicInteger()
        val unavailable = AtomicInteger()
        val rateLimited = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/art") { exchange ->
            exchange.use {
                if (attempts.incrementAndGet() == 1) {
                    it.responseHeaders.add("Retry-After", "0")
                    it.sendResponseHeaders(503, -1)
                } else {
                    it.responseHeaders.add("Content-Type", "image/png")
                    it.sendResponseHeaders(200, png.size.toLong())
                    it.responseBody.write(png)
                }
            }
        }
        server.createContext("/missing") {
            it.use {
                unavailable.incrementAndGet()
                it.sendResponseHeaders(404, -1)
            }
        }
        server.createContext("/limited") {
            it.use {
                rateLimited.incrementAndGet()
                it.responseHeaders.add("Retry-After", "120")
                it.sendResponseHeaders(429, -1)
            }
        }
        server.start()
        val directory = Files.createTempDirectory("desktop-artwork-test").toFile()
        val loader = DesktopImageLoader.createImageLoader(PlatformContext.INSTANCE, directory.toPath())
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val cover = request("$base/art")
            val results = List(8) { async { loader.execute(cover) } }.awaitAll()
            val image = assertIs<SuccessResult>(results.first()).image
            assertTrue(image.width in 1..64)
            assertTrue(results.all { it is SuccessResult && it.image === image })
            assertEquals(2, attempts.get(), "Initial 503 plus one successful network request")
            loader.memoryCache?.clear()
            assertIs<SuccessResult>(loader.execute(cover))
            assertEquals(2, attempts.get(), "Warm disk cache avoids another network request")
            assertIs<ErrorResult>(loader.execute(request("$base/missing")))
            assertIs<ErrorResult>(loader.execute(request("$base/missing")))
            assertEquals(2, unavailable.get(), "A missing cover is neither retried nor negatively cached")
            assertIs<ErrorResult>(loader.execute(request("$base/limited")))
            assertEquals(1, rateLimited.get(), "A Retry-After beyond the retry budget ends the request")
        } finally {
            loader.shutdown()
            server.stop(0)
            directory.deleteRecursively()
        }
        Unit
    }

    @Test
    fun currentTrackStartsWithAllBackgroundLanesOccupied() = runBlocking {
        val scheduler = DesktopArtworkRequests(maxConcurrent = 3)
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        fun load(index: Int, player: Boolean = false) = async(start = CoroutineStart.UNDISPATCHED) {
            val request = request("https://example.test/cover/$index", player)
            scheduler.intercept(object : Interceptor.Chain {
                override val request = request
                override val size = Size(64, 64)
                override fun withRequest(request: ImageRequest): Interceptor.Chain = error("Unexpected request rewrite")
                override fun withSize(size: Size): Interceptor.Chain = error("Unexpected size rewrite")
                override suspend fun proceed(): ImageResult {
                    started += index
                    release.await()
                    return ErrorResult(null, request, IllegalArgumentException("Decode failed"))
                }
            })
        }
        val thumbnails = (0..7).map { load(it) }
        val playing = load(8, player = true)
        assertEquals(listOf(0, 1, 8), started)
        release.complete(Unit)
        (thumbnails + playing).awaitAll()
        assertEquals(listOf(0, 1, 8, 2, 3, 4, 5, 6, 7), started)
    }

    private fun request(url: String, player: Boolean = false): ImageRequest =
        ImageRequest.Builder(PlatformContext.INSTANCE)
            .data(url)
            .size(64)
            .apply { if (player) currentTrackArtwork() }
            .build()
}
