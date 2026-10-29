package com.music.bitchord.desktop

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.collect
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopMemoryRegressionTest {
    @Test
    fun `duplicate artwork requests share fetch and decode`() = runBlocking {
        val fetches = AtomicInteger()
        val decodes = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val loader = DesktopImageLoader<String>(1024, { it.length.toLong() },
            fetch = { fetches.incrementAndGet(); gate.await(); it.toByteArray() },
            decode = { decodes.incrementAndGet(); it.decodeToString() })
        val results = List(30) { async(start = CoroutineStart.UNDISPATCHED) { loader.load("same") } }
        gate.complete(Unit)
        assertEquals(List(30) { "same" }, results.awaitAll())
        assertEquals(1, fetches.get())
        assertEquals(1, decodes.get())
    }

    @Test
    fun `distinct artwork bounds both live payloads and decodes`() = runBlocking {
        val live = AtomicInteger()
        val decoding = AtomicInteger()
        val peakLive = AtomicInteger()
        val peakDecode = AtomicInteger()
        val loader = DesktopImageLoader<String>(1024, { 1L }, parallelLoads = 4, parallelDecodes = 2,
            fetch = {
                peakLive.accumulateAndGet(live.incrementAndGet(), ::maxOf)
                delay(5)
                it.toByteArray()
            }, decode = {
                peakDecode.accumulateAndGet(decoding.incrementAndGet(), ::maxOf)
                delay(10)
                decoding.decrementAndGet(); live.decrementAndGet()
                it.decodeToString()
            })
        (1..40).map { async { loader.load("$it") } }.awaitAll()
        assertTrue(peakLive.get() in 2..4)
        assertTrue(peakDecode.get() in 1..2)
        assertEquals(0, live.get())
    }

    @Test
    fun `one departing consumer leaves shared work alive and last one cancels it`() = runBlocking {
        val flights = DesktopSingleFlight<String, String>()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val load: suspend () -> String = {
            try { entered.complete(Unit); awaitCancellation() }
            finally { cancelled.complete(Unit) }
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { flights.get("x", load) }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { flights.get("x", load) }
        first.cancelAndJoin()
        assertFalse(cancelled.isCompleted)
        second.cancelAndJoin()
        withTimeout(2000) { cancelled.await() }
        assertEquals("retry", flights.get("x") { "retry" })
    }

    @Test
    fun `clearing cancels old loads and cannot repopulate decoded cache`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val loader = DesktopImageLoader<String>(1024, { 1L },
            fetch = { if (it == "old") { entered.complete(Unit); awaitCancellation() }; it.toByteArray() },
            decode = { it.decodeToString() })
        val old = async { loader.load("old") }
        entered.await()
        loader.clear()
        assertFailsWith<CancellationException> { old.await() }
        assertEquals(0, loader.heldBytes)
        assertEquals("new", loader.load("new"))
        assertEquals(1, loader.heldBytes)
    }

    @Test
    fun `failed loads are retryable`() = runBlocking {
        val attempts = AtomicInteger()
        val loader = DesktopImageLoader<String>(10, { 1L },
            fetch = { check(attempts.incrementAndGet() > 1); it.toByteArray() }, decode = { it.decodeToString() })
        assertFailsWith<IllegalStateException> { loader.load("url") }
        assertEquals("url", loader.load("url"))
        assertEquals(2, attempts.get())
    }

    @Test
    fun `Canvas immutable frame owns pixels independently of decoder buffer`() {
        val info = ImageInfo.makeN32(4, 2, ColorAlphaType.OPAQUE)
        val bytes = ByteArray(4 * 2 * 4) { if (it % 4 == 2 || it % 4 == 3) 0xff.toByte() else 0 }
        val first = DesktopCanvasFrame(info, bytes).capture()
        val expected = IntArray(8).also(first::readPixels)
        bytes.fill(0)
        val second = DesktopCanvasFrame(info, bytes).capture()
        try {
            val actual = IntArray(8).also(first::readPixels)
            assertContentEquals(expected, actual)
            assertTrue(first.asSkiaBitmap().isImmutable)
            assertFalse(actual.contentEquals(IntArray(8).also(second::readPixels)))
        } finally {
            first.asSkiaBitmap().close(); second.asSkiaBitmap().close()
        }
    }

    @Test
    fun `direct Canvas pixels and palette match the previous conversion`() {
        val width = 133; val height = 145
        val info = ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE)
        val bytes = ByteArray(width * height * 4) { (it * 31).toByte() }
        val previous = Image.makeRaster(info, bytes.copyOf(), width * 4).use { it.toComposeImageBitmap() }
        val current = DesktopCanvasFrame(info, bytes).capture()
        try {
            val before = IntArray(width * height).also(previous::readPixels)
            val after = IntArray(width * height).also(current::readPixels)
            assertTrue(before.contentEquals(after), "Converted pixels differ")
            val all = IntArray(width * height).also(current::readPixels)
            val selected = all.filterIndexed { i, _ -> i % 16 == 0 }
            val red = selected.sumOf { (it shr 16 and 255).toLong() } / selected.size
            val palette = DesktopArtworkPalette.fromBitmap(current)
            assertEquals(red.toFloat() / 255f, palette.primary.red, 0.005f)
        } finally { previous.asSkiaBitmap().close(); current.asSkiaBitmap().close() }
    }

    @Test
    fun `Canvas decoder closes after failure and supports repeated reopen`() {
        val file = Files.createTempFile("canvas-lifecycle", ".png")
        ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", file.toFile())
        val decoder = DesktopCanvasDecoder()
        try {
            repeat(20) {
                decoder.open(file.toString()).getOrThrow()
                assertTrue(decoder.nextFrame(ByteArray(decoder.width * decoder.height * 4)))
                decoder.close(); decoder.close()
                assertEquals(0, decoder.width)
                assertFalse(decoder.nextFrame(ByteArray(0)))
                assertTrue(decoder.open(file.resolveSibling("absent-$it").toString()).isFailure)
            }
        } finally { decoder.close(); Files.delete(file) }
    }


    @Test fun `scoped frame drawing matches the former Skia draw with the same pixels`() {
        val info = ImageInfo.makeN32(18, 12, ColorAlphaType.OPAQUE)
        val pixels = ByteArray(18 * 12 * 4) { index -> if (index % 4 == 3) 0xff.toByte() else (index * 7).toByte() }
        val frame = DesktopCanvasFrame(info, pixels)
        org.jetbrains.skia.Surface.makeRasterN32Premul(40, 30).use { previous ->
            org.jetbrains.skia.Surface.makeRasterN32Premul(40, 30).use { current ->
                val rect = org.jetbrains.skia.Rect.makeXYWH(3f, 4f, 32f, 20f)
                previous.canvas.clear(org.jetbrains.skia.Color.BLACK)
                current.canvas.clear(org.jetbrains.skia.Color.BLACK)
                Image.makeRaster(info, pixels, 18 * 4).use { old ->
                    previous.canvas.drawImageRect(old, org.jetbrains.skia.Rect.makeWH(18f,12f), rect, org.jetbrains.skia.SamplingMode.LINEAR, null, true)
                }
                frame.draw(current.canvas, rect)
                previous.makeImageSnapshot().use { a -> current.makeImageSnapshot().use { b ->
                    a.encodeToData()!!.use { aa -> b.encodeToData()!!.use { bb -> assertContentEquals(aa.bytes,bb.bytes) } }
                } }
            }
        }
    }

    @Test fun `hide trim preserves active artwork consumers and avoids repopulating the old cache`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val loader = DesktopImageLoader<String>(1024, { 1L },
            fetch = { entered.complete(Unit); finish.await(); it.toByteArray() }, decode = { it.decodeToString() })
        val active = async { loader.load("visible") }
        entered.await()
        loader.trimMemory()
        finish.complete(Unit)
        assertEquals("visible", active.await())
        assertEquals(0, loader.heldBytes)
    }
}
