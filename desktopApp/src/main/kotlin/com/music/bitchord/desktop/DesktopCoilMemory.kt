package com.music.bitchord.desktop

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import kotlinx.coroutines.Dispatchers

/** The shared player has a second loader; give its idle entries an explicit native byte budget. */
internal object DesktopCoilMemory {
    private const val BUDGET = 32L * 1024 * 1024
    @Volatile private var active: ImageLoader? = null
    fun install() {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .memoryCache { MemoryCache.Builder().maxSizeBytes(BUDGET).build() }
                .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(2))
                .build().also { active = it }
        }
    }
    fun trim() { active?.memoryCache?.clear() }
}
