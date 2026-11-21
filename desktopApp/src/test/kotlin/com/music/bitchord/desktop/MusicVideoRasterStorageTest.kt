package com.music.bitchord.desktop

import com.sun.jna.Pointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicVideoRasterStorageTest {
    @Test fun recordedFrameSurvivesSlotReuseAndDisposalOnAnotherThread() {
        val dimensions = Rect.makeWH(8f, 8f)
        PictureRecorder().use { recorder ->
            val canvas = recorder.beginRecording(dimensions)
            MusicVideoRasterStorage(ImageInfo.makeN32(8, 8, ColorAlphaType.OPAQUE), 32).use { storage ->
                fun fill(color: Int) {
                    val pixels = Pointer(storage.address)
                    repeat(64) { pixels.setInt(it * 4L, color) }
                }
                fill(0xffff0000.toInt())
                VideoFrame(8, 8, storage.snapshot()).use { assertTrue(it.drawTo(canvas, dimensions)) }
                // Wrap the ring while Skia's picture is still holding the original red pixels.
                val producer = java.util.concurrent.CompletableFuture.runAsync {
                    repeat(8) {
                        fill(0xff0000ff.toInt())
                        storage.snapshot().close()
                    }
                }
                producer.get(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            recorder.finishRecordingAsPicture().use { picture ->
                Surface.makeRasterN32Premul(8, 8).use { target ->
                    target.canvas.drawPicture(picture)
                    Bitmap().use { pixel ->
                        assertTrue(pixel.allocN32Pixels(1, 1, true) && target.readPixels(pixel, 0, 0))
                        assertEquals(0xffff0000.toInt(), pixel.getColor(0, 0))
                    }
                }
            }
        }
    }
}
