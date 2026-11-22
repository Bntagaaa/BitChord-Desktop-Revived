package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

/** One conflated snapshot on one lazy IO writer, also drained on normal process exit. */
internal object DesktopMainWindowSettings {
    private val preferences by lazy { Preferences.userRoot().node("com.music.bitchord.desktop/window") }
    private val _bounds = MutableStateFlow(read())
    val bounds: StateFlow<DesktopMainWindowBounds?> = _bounds
    private val lock = Any()
    private var pending = false
    private val writer by lazy {
        Executors.newSingleThreadScheduledExecutor { work ->
            Thread(work, "BitChord-WindowStore").apply { isDaemon = true }
        }.also { service ->
            Runtime.getRuntime().addShutdownHook(Thread({ service.submit(::persist).get() }, "BitChord-WindowStoreFlush"))
        }
    }

    /** Records where the window was left; null forgets it. Writes are coalesced, so dragging one out costs one. */
    fun update(bounds: DesktopMainWindowBounds?) = synchronized(lock) {
        if (bounds == _bounds.value) return@synchronized
        _bounds.value = bounds
        if (!pending) { pending = true; writer.schedule(::persist, 250, TimeUnit.MILLISECONDS) }
    }

    private fun persist() {
        val snapshot = synchronized(lock) { pending = false; _bounds.value }
        runCatching {
            if (snapshot == null) preferences.remove("bounds")
            else preferences.put("bounds", "${snapshot.x},${snapshot.y},${snapshot.width},${snapshot.height}")
            preferences.flush()
        }.onFailure { DesktopTrackLog.log("Window preferences could not be saved: ${it.javaClass.simpleName}") }
    }

    internal fun flush() { writer.submit(::persist).get() }

    private fun read() = runCatching { decodeBounds(preferences.get("bounds", "")) }.getOrNull()

    internal fun decodeBounds(value: String): DesktopMainWindowBounds? {
        val parts = value.split(',').map { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it == null }) return null
        val bounds = DesktopMainWindowBounds(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!)
        if (bounds.width <= 0 || bounds.height <= 0) return null
        return bounds
    }
}
