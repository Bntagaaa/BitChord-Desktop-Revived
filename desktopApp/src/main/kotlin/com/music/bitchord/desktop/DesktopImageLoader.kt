package com.music.bitchord.desktop

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toOkioPath
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Global Coil [ImageLoader] configuration for Desktop.
 *
 * Prevents native off-heap Skia memory ballooning by strictly capping the
 * in-memory bitmap cache and disabling weak references (which prevent GC from
 * reclaiming Skia image buffers under native memory pressure).
 *
 * Evicted bitmaps fall back to a 64 MiB local disk cache on SSD, ensuring
 * that re-visiting screens does not require network re-fetches.
 */
internal object DesktopImageLoader {
    const val MAX_MEMORY_CACHE_BYTES = 12L * 1024 * 1024 // 12 MiB
    const val MAX_DISK_CACHE_BYTES = 64L * 1024 * 1024    // 64 MiB

    fun install() {
        SingletonImageLoader.setSafe { context ->
            createImageLoader(context)
        }
    }

    private val imageHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val trace = if (java.lang.Boolean.getBoolean("bitchord.artwork.trace")) DesktopTrackLog::log else null

    fun createImageLoader(
        context: PlatformContext,
        cacheDir: Path = DesktopMediaCache.directory.resolve("coil_cache"),
    ): ImageLoader {
        return ImageLoader.Builder(context)
            .interceptorCoroutineContext(Dispatchers.IO)
            .decoderCoroutineContext(Dispatchers.Default.limitedParallelism(3))
            .components {
                add(DesktopArtworkRequests(trace = trace))
                add(OkHttpNetworkFetcherFactory(callFactory = { imageHttpClient }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(MAX_MEMORY_CACHE_BYTES)
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(false)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.toOkioPath())
                    .maxSizeBytes(MAX_DISK_CACHE_BYTES)
                    .build()
            }
            .build()
    }

    fun trimMemory() {
        runCatching {
            val cache = SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache
            val evicted = cache?.size ?: 0
            cache?.clear()
            trace?.invoke("artwork event=memory_trim bytes=$evicted")
        }
    }
    fun clearCache() {
        trimMemory()
        SingletonImageLoader.get(PlatformContext.INSTANCE).diskCache?.clear()
    }
}
