package com.music.bitchord.desktop

import kotlinx.coroutines.*

/** Eviction on a worker: walking the disk cache and requesting GC must not block window events. */
object DesktopMemoryOptimizer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trim: Job? = null
    @Synchronized fun onWindowHidden() {
        trim?.cancel()
        trim = scope.launch {
            delay(1_000)
            DesktopArtworkCache.trimMemory()
            DesktopCoilMemory.trim()
            runCatching { DesktopMediaCache.trim() }
            System.gc()
            DesktopTrackLog.log("memory: released idle artwork caches on window hide")
        }
    }
    @Synchronized fun onWindowShown() { trim?.cancel(); trim = null }
}
