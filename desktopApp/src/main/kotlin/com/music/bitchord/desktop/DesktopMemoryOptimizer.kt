package com.music.bitchord.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Trims in-memory resources when the window is minimized or hidden in system tray. */
object DesktopMemoryOptimizer {

    fun onWindowHidden() {
        DesktopPageMemoryPolicy.trimProcessMemory()
        runCatching { DesktopMediaCache.trim() }
        DesktopTrackLog.log("memory: trimmed image cache, coil cache, and requested heap GC on window hide")
    }
}
