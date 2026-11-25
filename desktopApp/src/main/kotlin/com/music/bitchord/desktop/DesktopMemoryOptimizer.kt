package com.music.bitchord.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Trims in-memory resources when the window is minimized or hidden in system tray. */
object DesktopMemoryOptimizer {
    private val hidden = AtomicBoolean(false)
    private const val TRAY_POTOKEN_IDLE_MS = 30L * 1_000L

    fun onWindowShown() { hidden.set(false) }

    fun onWindowHidden() {
        // Tray close and WindowState can report the same hidden episode. Never
        // run duplicate disk walks/trims from those two callbacks.
        if (!hidden.compareAndSet(false, true)) return
        // Run all cache eviction and native trimming off the UI/audio path without
        // forcing a Stop-The-World System.gc() that can starve the 200ms audio sink.
        DesktopApplicationResources.owner.scope.launch(Dispatchers.IO) {
            DesktopPageMemoryPolicy.trimProcessMemory()
            runCatching { DesktopMediaCache.trim() }
            DesktopPoTokenWebView.trimIfIdle(TRAY_POTOKEN_IDLE_MS)
            DesktopTrackLog.log("memory: trimmed inactive artwork, coil cache, and media cache")
        }
    }
}
