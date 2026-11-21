package com.music.bitchord.desktop

import com.music.bitchord.data.innertube.InnerTubeXResolver
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

class DesktopNativeVideoFrameTest {
    @Test fun retainsNativePixelsAfterDecoderClosesAndDrainsReorderedFrames() = withFixture { path ->
        val first = DesktopCanvasDecoder(loopAtEnd = false).use { decoder ->
            decoder.open(path).getOrThrow()
            val frame = assertNotNull(decoder.nextFrame())
            var frameCount = 1
            try {
                while (true) {
                    val next = decoder.nextFrame() ?: break
                    next.close()
                    frameCount++
                }
                assertEquals(10, frameCount)
                frame
            } catch (failure: Throwable) {
                frame.close()
                throw failure
            }
        }
        first.use { frame ->
            val sample = assertNotNull(frame.sample(16))
            assertEquals(16, sample.width)
            assertEquals(12, sample.height)
            Surface.makeRasterN32Premul(1, 1).use { surface ->
                frame.drawTo(surface.canvas, Rect.makeWH(1f, 1f))
                Bitmap().use { bitmap ->
                    assertTrue(bitmap.allocN32Pixels(1, 1, true) && surface.readPixels(bitmap, 0, 0))
                    assertTrue((bitmap.getColor(0, 0) ushr 16 and 0xff) > 220)
                }
            }
        }
        assertNull(first.sample(16))
    }

    @Test fun musicVideoRenditionStaysDrawableAfterNativeDecoderDisposal() = withFixture { path ->
        val decoder = UnifiedVideoEngine.musicDecoder(null)
        val stream = InnerTubeXResolver.VideoStream("video", path, 16, 12, "video/mp4", emptyMap(), listOf(12))
        val first = try {
            decoder.open(stream)
            val retained = assertNotNull(decoder.nextFrame())
            try {
                repeat(8) { assertNotNull(decoder.nextFrame()).close() }
                retained
            } catch (failure: Throwable) {
                retained.close()
                throw failure
            }
        } finally {
            decoder.close()
        }
        first.use { frame ->
            assertEquals(16, frame.width)
            assertEquals(12, frame.height)
            Surface.makeRasterN32Premul(1, 1).use { surface ->
                assertTrue(frame.drawTo(surface.canvas, Rect.makeWH(1f, 1f)))
                Bitmap().use { bitmap ->
                    assertTrue(bitmap.allocN32Pixels(1, 1, true) && surface.readPixels(bitmap, 0, 0))
                    assertTrue((bitmap.getColor(0, 0) ushr 16 and 0xff) > 220)
                }
            }
        }
    }

    @Test fun seeksAndLoopsWithoutReopeningAndRejectsCancelledIo() = withFixture { path ->
        val owner = Job()
        DesktopCanvasDecoder(owner = owner).use { decoder ->
            decoder.open(path).getOrThrow()
            repeat(12) { assertNotNull(decoder.nextFrame()).close() }
            assertTrue(decoder.seekTo(0))
            assertNotNull(decoder.nextFrame()).close()
            owner.cancel()
            assertNull(decoder.nextFrame { false })
        }
    }

    private fun withFixture(block: (String) -> Unit) {
        val path = Files.createTempFile("bitchord-video-test", ".mp4")
        try {
            checkNotNull(javaClass.getResourceAsStream("/video/red-h264.mp4")).use {
                Files.copy(it, path, StandardCopyOption.REPLACE_EXISTING)
            }
            block(path.toString())
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
