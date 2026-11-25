package com.music.bitchord.desktop

import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.ArtworkHover
import com.music.bitchord.ui.components.ArtworkHoverMode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.desktop.runtime.SharedLoadCache
import com.music.bitchord.ui.graphics.toImageBitmap
import com.music.bitchord.ui.player.currentTrackArtwork
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Decoded artwork with a byte budget, shared producers and application ownership.
 * Native bitmap retention and decode concurrency are separate from the JVM heap limit.
 */
internal object DesktopArtworkCache {

    /**
     * A thirty-second of the heap, within reason.
     *
     * Derived rather than fixed so the budget still makes sense against whatever `-Xmx` the
     * launcher was given, and clamped at both ends so a small heap still caches something and a
     * large one does not decide it may hold half a gigabyte of thumbnails.
     */
    internal val budgetBytes: Long =
        (Runtime.getRuntime().maxMemory() / 32).coerceIn(6L * 1024 * 1024, 8L * 1024 * 1024)

    private data class Key(val url: String, val pixels: Int?)

    /** Four bytes a pixel, which is what every format here decodes to. */
    private val images = lazy {
        val owner = DesktopApplicationResources.owner
        owner.own("artwork") {
            SharedLoadCache<Key, ImageBitmap>(owner.scope, budgetBytes,
                sizeOf = { it.width.toLong() * it.height.toLong() * 4L },
                maxConcurrentLoads = null)
        }
    }

    val heldBytes: Long get() = if (images.isInitialized()) images.value.heldBytes else 0

    fun trimMemory() { if (images.isInitialized()) images.value.trim() }

    suspend fun load(url: String?, pixels: Int? = null, currentTrack: Boolean = false): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        val context = PlatformContext.INSTANCE
        return images.value.load(Key(url, pixels)) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .apply {
                    if (pixels != null) size(pixels)
                    if (currentTrack) currentTrackArtwork()
                }
                .build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
            result?.image?.toImageBitmap()
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
    currentTrackId: String? = null,
) {
    val sized = if (px != null) url.artworkAt(px) else url
    var bitmap by remember(currentTrackId ?: sized) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(currentTrackId, sized, px) {
        val result = DesktopArtworkCache.load(sized, px ?: HEADER_ART_PX, currentTrackId != null)
        currentCoroutineContext().ensureActive()
        if (result != null) bitmap = result
    }
    val displayed = bitmap

    if (hoverMode == null) {
        if (displayed == null) {
            Box(modifier.background(Color(0xFF2B2B35)))
        } else {
            Image(
                painter = BitmapPainter(displayed),
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
            if (displayed == null) {
                Box(Modifier.fillMaxSize().background(Color(0xFF2B2B35)))
            } else {
                Image(
                    painter = BitmapPainter(displayed),
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
