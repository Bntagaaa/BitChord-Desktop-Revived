package com.music.bitchord.desktop

import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.ArtworkHover
import com.music.bitchord.ui.components.ArtworkHoverMode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.decodeToImageBitmap
import java.net.URI
import com.music.bitchord.desktop.runtime.SharedLoadCache
import kotlinx.coroutines.CancellationException

/**
 * Decoded artwork with a byte budget, shared producers and application ownership.
 * Native bitmap retention and decode concurrency are separate from the JVM heap limit.
 */
internal object DesktopArtworkCache {

    /**
     * A sixth of the heap, within reason.
     *
     * Derived rather than fixed so the budget still makes sense against whatever `-Xmx` the
     * launcher was given, and clamped at both ends so a small heap still caches something and a
     * large one does not decide it may hold half a gigabyte of thumbnails.
     */
    internal val budgetBytes: Long =
        (Runtime.getRuntime().maxMemory() / 6).coerceIn(32L * 1024 * 1024, 128L * 1024 * 1024)

    /** Four bytes a pixel, which is what every format here decodes to. */
    private val images = lazy {
        val owner = DesktopApplicationResources.owner
        owner.own("artwork") {
            SharedLoadCache<String, ImageBitmap>(owner.scope, budgetBytes,
                sizeOf = { it.width.toLong() * it.height.toLong() * 4L },
                maxConcurrentLoads = 4)
        }
    }

    val heldBytes: Long get() = if (images.isInitialized()) images.value.heldBytes else 0

    /** Everything held, dropped — what the Storage settings' "Clear image cache" does. */
    fun clear() { if (images.isInitialized()) images.value.clear() }

    suspend fun load(url: String?): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        return images.value.load(url) {
            try {
                URI(url).toURL().openConnection().apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }.getInputStream().use { stream -> stream.readBytes().decodeToImageBitmap() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Artwork, fetched at the size the surface drawing it actually needs. */
@Composable
fun DesktopArtwork(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    px: Int? = null,
    hoverMode: ArtworkHoverMode? = null,
    hovered: Boolean? = null,
) {
    val sized = if (px != null) url.artworkAt(px) else url
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = sized) {
        value = withContext(Dispatchers.IO) {
            DesktopArtworkCache.load(sized)
        }
    }

    if (hoverMode == null) {
        if (bitmap == null) {
            Box(modifier.background(Color(0xFF2B2B35)))
        } else {
            Image(
                painter = BitmapPainter(bitmap!!),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = modifier,
            )
        }
    } else {
        ArtworkHover(
            modifier = modifier,
            shape = RectangleShape,
            mode = hoverMode,
            hovered = hovered,
        ) {
            if (bitmap == null) {
                Box(Modifier.fillMaxSize().background(Color(0xFF2B2B35)))
            } else {
                Image(
                    painter = BitmapPainter(bitmap!!),
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
