package com.music.bitchord.desktop

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopImageLoaderTest {

    @Test
    fun constantsMatchMemoryBudgets() {
        assertEquals(12L * 1024 * 1024, DesktopImageLoader.MAX_MEMORY_CACHE_BYTES)
        assertEquals(64L * 1024 * 1024, DesktopImageLoader.MAX_DISK_CACHE_BYTES)
    }

    @Test
    fun imageLoaderConfiguresMemoryAndDiskCaches() {
        val loader = DesktopImageLoader.createImageLoader(PlatformContext.INSTANCE)
        val memoryCache = assertNotNull(loader.memoryCache)
        assertEquals(12L * 1024 * 1024, memoryCache.maxSize)

        val diskCache = assertNotNull(loader.diskCache)
        assertEquals(64L * 1024 * 1024, diskCache.maxSize)
    }

    @OptIn(DelicateCoilApi::class)
    @Test
    fun installRegistersSingletonAndTrimCompletes() {
        val context = PlatformContext.INSTANCE
        val previous = SingletonImageLoader.get(context)
        SingletonImageLoader.reset()
        try {
            DesktopImageLoader.install()
            val singleton = SingletonImageLoader.get(context)
            try {
                assertEquals(DesktopImageLoader.MAX_MEMORY_CACHE_BYTES, assertNotNull(singleton.memoryCache).maxSize)
                DesktopImageLoader.trimMemory()
                assertEquals(0L, singleton.memoryCache?.size)
            } finally {
                singleton.shutdown()
            }
        } finally {
            SingletonImageLoader.setUnsafe(previous)
        }
    }
}
