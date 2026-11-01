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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.decodeToImageBitmap
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
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
     * Bound the decoded-image cache, not just compressed HTTP payloads. A decoded 1000px
     * bitmap can be several MiB even when its JPEG is tiny.
     */
    internal val budgetBytes: Long =
        (Runtime.getRuntime().maxMemory() / 6).coerceIn(32L * 1024 * 1024, 128L * 1024 * 1024)

    private val images = DesktopByteBudgetLru<ImageBitmap>(budgetBytes) {
        it.width.toLong() * it.height.toLong() * 4L
    }
    private val cacheLifecycleLock = Any()
    private val cacheEpoch = AtomicLong(0)
    private val requests = ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Prevent a wall of parallel network/decode jobs when Home, Search and playlists mount.
    private val loadSlots = Semaphore(4)

    val heldBytes: Long get() = images.heldBytes

    internal fun shouldCache(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height.toLong() <= budgetBytes / 4L

    /** Drop cached artwork without allowing an older request to repopulate it on completion. */
    fun clear() = synchronized(cacheLifecycleLock) {
        cacheEpoch.incrementAndGet()
        images.clear()
    }

    /** Single-flight by URL: all collectors share one fetch/decode and the worker outlives
     * a single composable's cancellation. The caller still owns the returned bitmap reference. */
    suspend fun load(url: String?): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        images.get(url)?.let { return it }

        val request = CompletableDeferred<ImageBitmap?>()
        val ongoing = requests.putIfAbsent(url, request)
        if (ongoing != null) return ongoing.await()
        val epoch = cacheEpoch.get()
        worker.launch {
            try {
                val bitmap = loadSlots.withPermit {
                    images.get(url) ?: runCatching {
                        URI(url).toURL().openConnection().apply {
                            connectTimeout = 10_000
                            readTimeout = 10_000
                        }.getInputStream().use { stream -> stream.readBytes().decodeToImageBitmap() }
                    }.getOrNull()
                }
                if (bitmap != null && shouldCache(bitmap.width, bitmap.height)) {
                    synchronized(cacheLifecycleLock) {
                        if (cacheEpoch.get() == epoch) images.put(url, bitmap)
                    }
                }
                request.complete(bitmap)
            } catch (cancelled: CancellationException) {
                request.cancel(cancelled)
            } catch (_: Exception) {
                request.complete(null)
            } finally {
                requests.remove(url, request)
            }
        }
        return request.await()
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
