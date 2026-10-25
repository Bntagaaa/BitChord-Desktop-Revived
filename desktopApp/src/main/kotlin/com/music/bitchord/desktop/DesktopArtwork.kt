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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

/** Decoded-artwork LRU with an encoded disk cache to avoid refetching evicted covers. */
internal object DesktopArtworkCache {
    internal val budgetBytes: Long =
        (Runtime.getRuntime().maxMemory() / 12).coerceIn(12L * 1024 * 1024, 24L * 1024 * 1024)
    private val images = DesktopByteBudgetLru<ImageBitmap>(budgetBytes) {
        it.width.toLong() * it.height.toLong() * 4L
    }
    private const val DISK_BUDGET_BYTES = 128L * 1024 * 1024
    private val slots = Semaphore(12)
    private val diskLock = Any()
    private val directory: Path by lazy {
        val cacheRoot = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
            ?: Path.of(System.getProperty("user.home"), ".cache").toString()
        Path.of(cacheRoot, "bitchord", "artwork").also { Files.createDirectories(it) }
    }

    private fun pathFor(url: String): Path {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return directory.resolve("$hash.img")
    }

    private fun fromDisk(url: String): ByteArray? = runCatching {
        synchronized(diskLock) {
            val path = pathFor(url)
            if (!Files.isRegularFile(path)) return@synchronized null
            Files.readAllBytes(path).takeIf(ByteArray::isNotEmpty)?.also {
                Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()))
            }
        }
    }.getOrNull()

    private fun toDisk(url: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        runCatching {
            synchronized(diskLock) {
                val path = pathFor(url)
                val temporary = Files.createTempFile(directory, "artwork-", ".tmp")
                try {
                    Files.write(temporary, bytes)
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temporary)
                }
                trimDisk()
            }
        }
    }

    private fun trimDisk() {
        val paths = Files.list(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList()
        }
        var total = paths.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        if (total <= DISK_BUDGET_BYTES) return
        for (path in paths.sortedBy {
            runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L)
        }) {
            if (total <= DISK_BUDGET_BYTES) break
            val size = runCatching { Files.size(path) }.getOrDefault(0L)
            if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) total -= size
        }
    }

    /** Clear both decoded and encoded artwork when the Storage cache action is used. */
    fun clear() {
        images.clear()
        runCatching {
            synchronized(diskLock) {
                Files.list(directory).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    suspend fun load(url: String?): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        images.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            val cached = fromDisk(url)
            val bytes = cached ?: slots.withPermit {
                coroutineContext.ensureActive()
                val fetched = runCatching {
                    val address = URI(url)
                    require(address.scheme == "http" || address.scheme == "https")
                    address.toURL().openConnection().apply {
                        connectTimeout = 10_000
                        readTimeout = 10_000
                    }.getInputStream().use { it.readBytes() }
                }.getOrNull()
                coroutineContext.ensureActive()
                if (fetched != null) toDisk(url, fetched)
                fetched
            }
            coroutineContext.ensureActive()
            val bitmap = runCatching { bytes?.decodeToImageBitmap() }.getOrNull()
            if (bitmap == null && cached != null) {
                runCatching { synchronized(diskLock) { Files.deleteIfExists(pathFor(url)) } }
            }
            coroutineContext.ensureActive()
            bitmap?.also { images.put(url, it) }
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
