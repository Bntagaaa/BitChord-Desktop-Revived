package com.music.bitchord.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Trims in-memory resources when the window is minimized or hidden in system tray. */
object DesktopMemoryOptimizer {

    fun onWindowHidden() {
        DesktopArtworkCache.clear()
        runCatching { DesktopMediaCache.trim() }
        System.gc()
        DesktopTrackLog.log("memory: trimmed image cache and requested heap GC on window hide")
    }
}
