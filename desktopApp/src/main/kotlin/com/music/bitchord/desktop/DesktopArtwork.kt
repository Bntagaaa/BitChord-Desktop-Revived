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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Decoded artwork, kept to a budget.
 *
 * It used to be an unbounded map. Every distinct URL a session ever loaded stayed decoded for the
 * life of the process, so browsing Explore and Search for a while was hundreds of megabytes of
 * bitmaps nothing would ever ask for again — which is where the gigabyte people reported was going.
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

    // Keep the established decoded budget: shrinking it without measuring churn can cost more.
    private val loader = DesktopImageLoader<ImageBitmap>(
        budgetBytes = budgetBytes,
        sizeOf = { it.width.toLong() * it.height * 4L },
        fetch = ::fetch,
        decode = { it.decodeToImageBitmap() },
    )
    private val disk = lazy {
        val base = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
            ?: "${System.getProperty("user.home")}/.cache"
        Cache(Path.of(base, "bitchord", "artwork-http").toFile(), 128L * 1024 * 1024)
    }
    // HTTP freshness, validators, atomic journal writes and incremental eviction are handled by
    // OkHttp. No directory walk on every write, and no permanent caching of expired artwork.
    private val client by lazy {
        OkHttpClient.Builder().cache(disk.value)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()
    }

    fun trimMemory() = loader.trimMemory()

    fun clear() {
        loader.trimMemory()
        runCatching { disk.value.evictAll() }
    }

    internal val heldBytes get() = loader.heldBytes

    suspend fun load(url: String?): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        return try { loader.load(url) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private suspend fun fetch(url: String): ByteArray {
        if (URI(url).scheme !in setOf("https", "http")) {
            return URI(url).toURL().openStream().use { it.readBytes() }
        }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val bytes = try {
                        response.use {
                            check(it.isSuccessful) { "Artwork HTTP ${it.code}" }
                            checkNotNull(it.body).bytes()
                        }
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(bytes)
                }
            })
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
        value = null
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
