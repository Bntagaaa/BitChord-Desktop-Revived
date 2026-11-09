package com.music.bitchord.desktop

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath

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
    const val MAX_MEMORY_CACHE_BYTES = 32L * 1024 * 1024 // 32 MiB
    const val MAX_DISK_CACHE_BYTES = 100L * 1024 * 1024   // 100 MiB

    fun install() {
        SingletonImageLoader.setSafe { context ->
            createImageLoader(context)
        }
    }

    fun createImageLoader(context: PlatformContext): ImageLoader {
        val cacheDir = DesktopMediaCache.directory.resolve("coil_cache")
        return ImageLoader.Builder(context)
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
