package com.music.bitchord.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Native heap pages can remain resident in glibc malloc arenas long after their blocks are
 * freed. NMT/Java GC cannot reclaim those pages. Only attempt malloc_trim during a long pause
 * or while the window is hidden, never on the audio or Compose rendering thread.
 */
internal object DesktopMemoryOptimizer {
    private const val ENABLE_PROPERTY = "bitchord.memory.nativeTrim"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val maintenanceRunning = AtomicBoolean(false)
    private val nativeTrimThrottle = DesktopNativeTrimThrottle(TimeUnit.MINUTES.toNanos(3))

    // Conservative until the playback UI publishes its first state.
    @Volatile private var playbackBusy = true
    @Volatile private var windowHidden = false

    private interface Glibc : Library {
        fun malloc_trim(pad: Long): Int
    }

    private val glibc: Glibc? by lazy {
        if (!DesktopPlatform.isLinux) null
        else runCatching { Native.load("c", Glibc::class.java) }
            .onFailure { DesktopTrackLog.log("memory: native trim unavailable: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    fun onPlaybackStateChanged(busy: Boolean) {
        playbackBusy = busy
    }

    fun onWindowShown() {
        windowHidden = false
    }

    fun onWindowHidden() {
        if (windowHidden) return
        windowHidden = true
        // The previous implementation also cleared this on hide; keep that behavior.
        DesktopArtworkCache.clear()
        scheduleMaintenance()
    }

    /** Called after 90s of paused/unloaded playback (Compose cancels the timer on resume). */
    fun onPausedIdle() {
        if (!playbackBusy) scheduleMaintenance()
    }

    private fun scheduleMaintenance() {
        if (!maintenanceRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (windowHidden) {
                    DesktopCoilImageLoader.trimInactive(24L * 1024 * 1024)
                    runCatching { DesktopMediaCache.trim() }
                        .onFailure { DesktopTrackLog.log("memory: disk cache trim failed: ${it.message}") }
                }
                if (!DesktopPlatform.isLinux || playbackBusy ||
                    System.getProperty(ENABLE_PROPERTY, "true").equals("false", ignoreCase = true)
                ) return@launch
                // No native memory pressure work while playback or an Automix inference is active.
                if (!nativeTrimThrottle.allow(System.nanoTime())) return@launch
                val libc = glibc ?: return@launch
                if (playbackBusy) return@launch
                val started = System.nanoTime()
                runCatching { libc.malloc_trim(0L) }
                    .onSuccess { released ->
                        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        DesktopTrackLog.log("memory: native malloc_trim result=$released duration=${elapsedMs}ms")
                    }
                    .onFailure { DesktopTrackLog.log("memory: native trim failed: ${it.javaClass.simpleName}") }
            } finally {
                maintenanceRunning.set(false)
            }
        }
    }
}

/** Pure cooldown policy; the native call and playback gating stay outside for testability. */
internal class DesktopNativeTrimThrottle(private val cooldownNanos: Long) {
    private var previousNanos: Long? = null

    init { require(cooldownNanos >= 0) }

    @Synchronized fun allow(nowNanos: Long): Boolean {
        val last = previousNanos
        if (last != null && nowNanos >= last && nowNanos - last < cooldownNanos) return false
        previousNanos = nowNanos
        return true
    }
}
