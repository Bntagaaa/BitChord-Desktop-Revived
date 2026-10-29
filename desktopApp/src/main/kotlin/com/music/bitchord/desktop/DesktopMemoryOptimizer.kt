package com.music.bitchord.desktop

import kotlinx.coroutines.*

/** An interruptible debounce around blocking cleanup; a restore must cancel the later GC request. */
internal class DesktopWindowMemoryTrimmer(
    private val scope: CoroutineScope,
    private val trim: () -> Unit,
    private val collect: () -> Unit,
    private val visible: () -> Boolean,
    private val delayMillis: Long = 1_000,
) {
    private var job: Job? = null
    @Synchronized fun hidden() {
        job?.cancel()
        job = scope.launch {
            delay(delayMillis)
            trim()
            currentCoroutineContext().ensureActive()
            if (!visible()) collect()
        }
    }
    @Synchronized fun shown() { job?.cancel(); job = null }
}

/** Eviction stays off window events; the Phase 1 GC policy is retained after measuring its benefit. */
object DesktopMemoryOptimizer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trimmer = DesktopWindowMemoryTrimmer(scope,
        trim = {
            DesktopArtworkCache.trimMemory()
            DesktopCoilMemory.trim()
            runCatching { DesktopMediaCache.trim() }
        },
        collect = {
            System.gc()
            DesktopTrackLog.log("memory: released idle artwork caches on window hide")
        },
        visible = { DesktopRenderVisibility.visible.value },
    )
    fun onWindowHidden() = trimmer.hidden()
    fun onWindowShown() = trimmer.shown()
}
