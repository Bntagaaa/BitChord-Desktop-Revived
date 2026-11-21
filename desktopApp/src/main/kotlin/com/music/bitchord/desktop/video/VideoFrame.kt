package com.music.bitchord.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.math.roundToInt
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/** The native image retains its immutable pixels independently of the decoder. */
internal class VideoFrame(val width: Int, val height: Int, image: Image) : Painter(), AutoCloseable {
    private var image: Image? = image
    private val source = Rect.makeWH(width.toFloat(), height.toFloat())
    override val intrinsicSize = Size(width.toFloat(), height.toFloat())

    constructor(width: Int, height: Int, pixels: ByteArray) : this(
        width, height, Image.makeRaster(ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE), pixels, width * 4),
    )

    override fun DrawScope.onDraw() {
        drawFrame(Rect.makeWH(size.width, size.height))
    }

    fun DrawScope.drawFrame(destination: Rect): Boolean {
        var submitted = false
        drawIntoCanvas { submitted = drawTo(it.nativeCanvas, destination) }
        return submitted
    }

    @Synchronized
    fun drawTo(canvas: org.jetbrains.skia.Canvas, destination: Rect): Boolean {
        val current = image ?: return false
        canvas.drawImageRect(current, source, destination, SamplingMode.LINEAR, null, true)
        return true
    }

    /** Retained mesh/palette input. This never reads a full sized frame into the JVM. */
    @Synchronized
    fun sample(maxEdge: Int): ImageBitmap? {
        if (image == null || maxEdge <= 0) return null
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(width, height))
        val sampleWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val sampleHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return Surface.makeRasterN32Premul(sampleWidth, sampleHeight).use { surface ->
            drawTo(surface.canvas, Rect.makeWH(sampleWidth.toFloat(), sampleHeight.toFloat()))
            surface.makeImageSnapshot().toComposeImageBitmap()
        }
    }

    @Synchronized
    override fun close() {
        image?.close()
        image = null
    }
}

/** Reads the current frame inside the owner's lock, even when Compose is a frame behind. */
internal class VideoSurfacePainter(
    override val intrinsicSize: Size,
    private val observe: () -> Unit,
    private val withFrame: ((VideoFrame) -> Unit) -> Unit,
    private val submitted: () -> Unit = {},
) : Painter() {
    override fun DrawScope.onDraw() {
        observe()
        val target = Rect.makeWH(size.width, size.height)
        withFrame { frame -> if (with(frame) { drawFrame(target) }) submitted() }
    }
}
