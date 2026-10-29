package com.music.bitchord.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/**
 * Retain frame bytes on the JVM, with explicit ownership of the temporary native draw image.
 * A native-only bitmap per frame can grow RSS for seconds without enough JVM pressure for GC.
 * Skia retains its own pixel reference when recording the draw, so closing our Image after that
 * call is safe even if the recorded picture is submitted later. Captured palette frames are
 * independent immutable copies; they can outlive this frame and must not be manually closed here.
 */
internal class DesktopCanvasFrame(info: ImageInfo, pixels: ByteArray) : Painter() {
    private val info = info.withColorAlphaType(ColorAlphaType.PREMUL)
    private val pixels = pixels.copyOf()
    val width = info.width
    val height = info.height
    override val intrinsicSize = Size(width.toFloat(), height.toFloat())
    private var capture: ImageBitmap? = null

    @Synchronized
    fun capture(): ImageBitmap = capture ?: desktopCanvasBitmap(info, pixels).also { capture = it }

    fun draw(canvas: org.jetbrains.skia.Canvas, destination: Rect) {
        Image.makeRaster(info, pixels, width * 4).use { image ->
            canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()), destination,
                SamplingMode.LINEAR, null, true)
        }
    }

    override fun DrawScope.onDraw() {
        drawIntoCanvas { draw(it.nativeCanvas, Rect.makeWH(size.width, size.height)) }
    }
}

/** Only used for the occasional palette capture, never for every displayed video frame. */
internal fun desktopCanvasBitmap(info: ImageInfo, pixels: ByteArray): ImageBitmap {
    val bitmap = Bitmap()
    try {
        check(bitmap.installPixels(info.withColorAlphaType(ColorAlphaType.PREMUL), pixels, info.width * 4)) {
            "Could not allocate Canvas capture"
        }
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    } catch (error: Throwable) {
        bitmap.close()
        throw error
    }
}
