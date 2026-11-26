package com.music.bitchord.desktop

import com.music.bitchord.data.Http
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.ArtworkHover
import com.music.bitchord.ui.components.ArtworkHoverMode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.music.bitchord.desktop.runtime.SharedLoadCache
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import java.util.concurrent.TimeUnit

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

    private val artworkHttp by lazy {
        Http.imageClient.newBuilder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Four bytes a pixel, which is what every format here decodes to. */
    private val images = lazy {
        val owner = DesktopApplicationResources.owner
        owner.own("artwork") {
            SharedLoadCache<String, ImageBitmap>(owner.scope, budgetBytes,
                sizeOf = { it.width.toLong() * it.height.toLong() * 4L },
                maxConcurrentLoads = 12)
        }
    }

    val heldBytes: Long get() = if (images.isInitialized()) images.value.heldBytes else 0

    /** Everything held, dropped — what the Storage settings' "Clear image cache" does. */
    fun clear() { if (images.isInitialized()) images.value.clear() }

    suspend fun load(url: String?): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        return images.value.load(url) {
            var loaded: ImageBitmap? = null
            for (attempt in 0 until ARTWORK_ATTEMPTS) {
                if (attempt > 0) delay(ARTWORK_RETRY_BASE_MS * attempt)
                loaded = try {
                    val request = Request.Builder().url(url).build()
                    artworkHttp.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) null
                        else response.body?.bytes()?.decodeToImageBitmap()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (loaded != null) break
            }
            loaded
        }
    }

    private const val ARTWORK_ATTEMPTS = 3
    private const val ARTWORK_RETRY_BASE_MS = 400L
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
    var attempt by remember(sized, url) { mutableIntStateOf(0) }
    var bitmap by remember(sized, url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(sized, url, attempt) {
        val target = if (attempt >= ARTWORK_UI_RETRIES && !url.isNullOrBlank() && url != sized) url else sized
        if (target.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            DesktopArtworkCache.load(target)
        }
        if (loaded != null) {
            bitmap = loaded
        } else if (attempt < ARTWORK_UI_RETRIES) {
            delay(ARTWORK_UI_RETRY_DELAYS_MS[attempt])
            attempt++
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

private const val ARTWORK_UI_RETRIES = 3
private val ARTWORK_UI_RETRY_DELAYS_MS = longArrayOf(800L, 2_000L, 4_000L)
