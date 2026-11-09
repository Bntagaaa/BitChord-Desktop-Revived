package com.music.bitchord.desktop

import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopImageLoaderTest {

    @Test
    fun constantsMatchMemoryBudgets() {
        assertEquals(32L * 1024 * 1024, DesktopImageLoader.MAX_MEMORY_CACHE_BYTES)
        assertEquals(100L * 1024 * 1024, DesktopImageLoader.MAX_DISK_CACHE_BYTES)
    }

    @Test
    fun imageLoaderConfiguresMemoryAndDiskCaches() {
        val loader = DesktopImageLoader.createImageLoader(PlatformContext.INSTANCE)
        val memoryCache = assertNotNull(loader.memoryCache)
        assertEquals(32L * 1024 * 1024, memoryCache.maxSize)

        val diskCache = assertNotNull(loader.diskCache)
        assertEquals(100L * 1024 * 1024, diskCache.maxSize)
    }

    @Test
    fun installRegistersSingletonAndTrimCompletes() {
        DesktopImageLoader.install()
        val singleton = SingletonImageLoader.get(PlatformContext.INSTANCE)
        assertNotNull(singleton)
        assertNotNull(singleton.memoryCache)
        DesktopImageLoader.trimMemory()
        assertEquals(0L, singleton.memoryCache?.size)
    }
}
