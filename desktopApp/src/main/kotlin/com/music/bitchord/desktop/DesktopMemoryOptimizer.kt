package com.music.bitchord.desktop

import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Trims in-memory resources when the window is minimized or hidden in system tray. */
object DesktopMemoryOptimizer {
    private val hidden = AtomicBoolean(false)

    fun onWindowShown() { hidden.set(false) }

    fun onWindowHidden() {
        // Tray close and WindowState can report the same hidden episode. Never
        // run duplicate disk walks/forced collections from those two callbacks.
        if (!hidden.compareAndSet(false, true)) return
        DesktopArtworkCache.clear()
        DesktopApplicationResources.owner.scope.launch {
            runCatching { DesktopMediaCache.trim() }
            // Preserve the existing hidden-window GC policy, but don't request
            // it after the user has already restored the window.
            if (hidden.get()) System.gc()
            DesktopTrackLog.log("memory: trimmed inactive artwork and media cache")
        }
    }
}
