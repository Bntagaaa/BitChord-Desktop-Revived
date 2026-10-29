package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DesktopArtworkPalette(
    val primary: Color,
    val secondary: Color,
) {
    companion object {
        val Default = DesktopArtworkPalette(Color(0xFFFF375F), Color(0xFF4E6DFF))
    }
}

@Composable
fun rememberDesktopArtworkPalette(
    url: String?,
    bitmapOverride: ImageBitmap? = null,
): DesktopArtworkPalette {
    val palette by produceState(DesktopArtworkPalette.Default, key1 = url, key2 = bitmapOverride) {
        value = DesktopArtworkPalette.Default
        value = withContext(Dispatchers.IO) {
            DesktopArtworkPalette.fromBitmap(bitmapOverride ?: DesktopArtworkCache.load(url))
        }
    }
    return palette
}

internal fun DesktopArtworkPalette.Companion.fromBitmap(bitmap: ImageBitmap?): DesktopArtworkPalette {
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) return DesktopArtworkPalette.Default
    // Preserve the exact original every-16th-pixel average, with a small scratch stripe.
    val stripeRows = minOf(32, bitmap.height)
    val pixels = IntArray(bitmap.width * stripeRows)
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0L
    for (y in 0 until bitmap.height step stripeRows) {
        val rows = minOf(stripeRows, bitmap.height - y)
        bitmap.readPixels(pixels, startY = y, width = bitmap.width, height = rows)
        val first = (16 - (y.toLong() * bitmap.width % 16)).toInt() % 16
        for (index in first until bitmap.width * rows step 16) {
            val pixel = pixels[index]
            red += pixel shr 16 and 0xFF
            green += pixel shr 8 and 0xFF
            blue += pixel and 0xFF
            count++
        }
    }
    if (count == 0L) return DesktopArtworkPalette.Default
    val average = Color(
        red = (red / count).toFloat() / 255f,
        green = (green / count).toFloat() / 255f,
        blue = (blue / count).toFloat() / 255f,
    )
    return DesktopArtworkPalette(
        primary = average.copy(alpha = 1f),
        secondary = Color(
            red = (average.red * 0.55f + 0.22f).coerceAtMost(1f),
            green = (average.green * 0.45f + 0.18f).coerceAtMost(1f),
            blue = (average.blue * 0.70f + 0.24f).coerceAtMost(1f),
        ),
    )
}
