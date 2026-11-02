package com.music.bitchord.desktop

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache

/**
 * Shared screens use Coil AsyncImage, while desktop-only screens use DesktopArtworkCache.
 * Budget BOTH caches; Coil's default cache size is independent of our desktop artwork LRU.
 *
 * Installing the factory before Compose creates the first ImageRequest preserves Coil's
 * existing network fetchers and normal lazy-composition cancellation semantics.
 */
internal object DesktopCoilImageLoader {
    internal const val CACHE_BUDGET_BYTES = 64L * 1024 * 1024

    @Volatile private var memoryCache: MemoryCache? = null

    fun install() {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizeBytes(CACHE_BUDGET_BYTES)
                        .build()
                        .also { memoryCache = it }
                }
                .build()
        }
    }

    /** Trim only decoded Coil images, never network/disk caches or live composable images. */
    fun trimInactive(targetBytes: Long): Long {
        val cache = memoryCache ?: return 0L
        val before = cache.size
        cache.trimToSize(targetBytes.coerceIn(0L, CACHE_BUDGET_BYTES))
        return (before - cache.size).coerceAtLeast(0L)
    }
}
