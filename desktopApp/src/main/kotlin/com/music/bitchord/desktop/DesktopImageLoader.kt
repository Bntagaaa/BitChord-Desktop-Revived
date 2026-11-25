package com.music.bitchord.desktop

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.music.bitchord.data.Http
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

/**
 * Global Coil [ImageLoader] configuration for Desktop.
 *
 * Prevents native off-heap Skia memory ballooning by strictly capping the
 * in-memory bitmap cache and disabling weak references (which prevent GC from
 * reclaiming Skia image buffers under native memory pressure).
 *
 * Evicted bitmaps fall back to a 100 MiB local disk cache on SSD, ensuring
 * that re-visiting screens does not require network re-fetches.
 */
internal object DesktopImageLoader {
    const val MAX_MEMORY_CACHE_BYTES = 20L * 1024 * 1024 // 20 MiB
    const val MAX_DISK_CACHE_BYTES = 100L * 1024 * 1024   // 100 MiB

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

    fun createImageLoader(context: PlatformContext): ImageLoader {
        val cacheDir = DesktopMediaCache.directory.resolve("coil_cache")
        return ImageLoader.Builder(context)
            .components {
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
            SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.clear()
        }
    }
}
