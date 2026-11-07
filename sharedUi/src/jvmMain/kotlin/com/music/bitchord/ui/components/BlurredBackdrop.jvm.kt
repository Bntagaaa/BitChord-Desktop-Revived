package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import coil3.Bitmap
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop equivalent of Android's blurred release-caption backdrop.
 *
 * Coil 3.0.4 does not support non-Android request transformations. Instead,
 * load a small copy through the existing Coil loader, blur its bottom strip on
 * a worker thread, and feed the resulting PNG bytes back to AsyncImage.
 * Neither network loading nor bitmap processing happens during drawing.
 */
@Composable
actual fun rememberBlurredBackdrop(url: String?): Any? {
    val context = LocalPlatformContext.current
    val loader = remember(context) { SingletonImageLoader.get(context) }
    val backdrop = produceState<Any?>(
        initialValue = url?.let(DesktopBackdropCache::get),
        key1 = url,
        key2 = loader,
    ) {
        // produceState retains its previous value when a key changes. Reset it
        // to the new artwork's cached strip before starting another request.
        value = url?.let(DesktopBackdropCache::get)
        if (url == null || value != null) return@produceState
        try {
            val result = loader.execute(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(160, 160)
                    .build(),
            )
            if (result is SuccessResult) {
                val blurred = withContext(Dispatchers.Default) {
                    blurReleaseBackdrop(result.image.toBitmap())
                }
                if (blurred != null) {
                    DesktopBackdropCache.put(url, blurred)
                    value = blurred
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed cover should not take down the Home shelf.
        }
    }
    return backdrop.value
}

/** Reuse finished strips when cards leave and re-enter the lazy row. */
private object DesktopBackdropCache {
    private const val MAX_ENTRIES = 96
    private val entries = object : LinkedHashMap<String, ByteArray>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized fun get(url: String): ByteArray? = entries[url]

    @Synchronized fun put(url: String, png: ByteArray) {
        entries[url] = png
    }
}

private const val BACKDROP_STRIP = 0.4f
private const val BACKDROP_WIDTH = 48
private const val BLUR_RADIUS = 3
private const val BLUR_PASSES = 3

/** Match Android's 40% crop, 48px downsample and three-pass box blur. */
private fun blurReleaseBackdrop(input: Bitmap): ByteArray? {
    if (input.width <= 0 || input.height <= 0) return null

    val top = (input.height * (1f - BACKDROP_STRIP)).toInt().coerceIn(0, input.height - 1)
    val croppedHeight = input.height - top
    val height = (croppedHeight * BACKDROP_WIDTH / input.width.toFloat())
        .toInt().coerceAtLeast(4)
    val pixels = IntArray(BACKDROP_WIDTH * height)

    // This sample is tiny; the subsequent blur removes any residual sampling
    // detail. Keep source reads on the worker thread, never the draw pass.
    for (y in 0 until height) {
        val sourceY = (top + (y + 0.5f) * croppedHeight / height)
            .toInt().coerceIn(top, input.height - 1)
        for (x in 0 until BACKDROP_WIDTH) {
            val sourceX = ((x + 0.5f) * input.width / BACKDROP_WIDTH)
                .toInt().coerceIn(0, input.width - 1)
            pixels[y * BACKDROP_WIDTH + x] =
                input.getColor(sourceX, sourceY) or (0xFF shl 24)
        }
    }

    blurBackdropPixels(pixels, BACKDROP_WIDTH, height)

    val output = BufferedImage(BACKDROP_WIDTH, height, BufferedImage.TYPE_INT_ARGB)
    output.setRGB(0, 0, BACKDROP_WIDTH, height, pixels, 0, BACKDROP_WIDTH)
    return ByteArrayOutputStream().use { stream ->
        if (ImageIO.write(output, "png", stream)) stream.toByteArray() else null
    }
}

/** Separable running-sum blur, identical to the Android backdrop algorithm. */
internal fun blurBackdropPixels(pixels: IntArray, width: Int, height: Int) {
    require(width > 0 && height > 0 && pixels.size == width * height)
    repeat(BLUR_PASSES) {
        boxBlur(pixels, width, height, BLUR_RADIUS, horizontal = true)
        boxBlur(pixels, width, height, BLUR_RADIUS, horizontal = false)
    }
}

private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) height else width
    val length = if (horizontal) width else height
    val line = IntArray(length)
    val window = radius * 2 + 1
    for (l in 0 until lines) {
        fun at(i: Int) = if (horizontal) l * width + i else i * width + l
        for (i in 0 until length) line[i] = pixels[at(i)]
        var r = 0
        var g = 0
        var b = 0
        for (k in -radius..radius) {
            val p = line[k.coerceIn(0, length - 1)]
            r += p shr 16 and 0xFF
            g += p shr 8 and 0xFF
            b += p and 0xFF
        }
        for (i in 0 until length) {
            pixels[at(i)] = (0xFF shl 24) or
                ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val out = line[(i - radius).coerceIn(0, length - 1)]
            val inn = line[(i + radius + 1).coerceIn(0, length - 1)]
            r += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
            g += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
            b += (inn and 0xFF) - (out and 0xFF)
        }
    }
}
