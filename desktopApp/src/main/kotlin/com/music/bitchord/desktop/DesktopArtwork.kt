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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import androidx.compose.ui.graphics.decodeToImageBitmap
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * Decoded artwork, kept to a budget.
 *
 * It used to be an unbounded map. Every distinct URL a session ever loaded stayed decoded for the
 * life of the process, so browsing Explore and Search for a while was hundreds of megabytes of
 * bitmaps nothing would ever ask for again — which is where the gigabyte people reported was going.
 */
internal object DesktopArtworkCache {

    /**
     * Keep a byte-budgeted cache of *decoded* images. Reusing visible covers prevents
     * extra downloads; stale requests must not outlive the composable that asked for them.
     */
    internal val budgetBytes: Long =
        (Runtime.getRuntime().maxMemory() / 6).coerceIn(32L * 1024 * 1024, 128L * 1024 * 1024)

    private const val MAX_ENCODED_ARTWORK_BYTES = 12 * 1024 * 1024

    private val images = DesktopByteBudgetLru<ImageBitmap>(budgetBytes) {
        it.width.toLong() * it.height.toLong() * 4L
    }
    private val cacheLifecycleLock = Any()
    private val cacheEpoch = AtomicLong(0)

    // A bounded number of active fetch/decode calls and a fixed-size set of URL locks.
    // Unlike the old SupervisorJob worker, no pending artwork work survives cancellation
    // when a LazyColumn/LazyGrid item scrolls off screen.
    private val loads = DesktopArtworkLoadGate(maxConcurrentLoads = 4, urlStripes = 64)

    val heldBytes: Long get() = images.heldBytes

    internal fun shouldCache(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height.toLong() <= budgetBytes / 4L

    /** Old requests are never allowed to repopulate a cache cleared on minimize. */
    fun clear() = synchronized(cacheLifecycleLock) {
        cacheEpoch.incrementAndGet()
        images.clear()
    }

    /** Runs within the calling composition's coroutine, so disposed items cancel
     * waiting requests instead of leaving independent jobs downloading off-screen covers. */
    suspend fun load(url: String?): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        images.get(url)?.let { return it }

        return loads.forUrl(url) {
            // First client populates the cache; other visible clients with this URL
            // reuse it after acquiring the same striped URL lock.
            images.get(url)?.let { return@forUrl it }
            currentCoroutineContext().ensureActive()
            val epoch = cacheEpoch.get()
            val bitmap = try {
                // Java URL reads are blocking; runInterruptible interrupts the IO work
                // when the Lazy item disappears or the artwork URL changes.
                runInterruptible(Dispatchers.IO) { decodeArtwork(url) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            currentCoroutineContext().ensureActive()
            if (bitmap != null && shouldCache(bitmap.width, bitmap.height)) {
                synchronized(cacheLifecycleLock) {
                    if (cacheEpoch.get() == epoch) images.put(url, bitmap)
                }
            }
            bitmap
        }
    }

    /** Avoid an unbounded readBytes() of a huge, non-thumbnail remote image. */
    private fun decodeArtwork(url: String): ImageBitmap? {
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        if (connection.contentLengthLong > MAX_ENCODED_ARTWORK_BYTES) return null
        val encoded = connection.getInputStream().use { input ->
            input.readNBytes(MAX_ENCODED_ARTWORK_BYTES + 1)
        }
        if (encoded.size > MAX_ENCODED_ARTWORK_BYTES ||
            Thread.currentThread().isInterrupted
        ) return null
        return encoded.decodeToImageBitmap()
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
        value = null
        value = DesktopArtworkCache.load(sized)
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
