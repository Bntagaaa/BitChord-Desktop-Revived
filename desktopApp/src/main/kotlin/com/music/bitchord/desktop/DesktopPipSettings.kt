package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

internal data class DesktopPipPreferences(
    val onClose: Boolean = false,
    val lyrics: Boolean = true,
    val pinned: Boolean = true,
    val bounds: DesktopPipBounds? = null,
)

/** One conflated snapshot on one lazy IO writer, also drained on normal process exit. */
internal object DesktopPipSettings {
    private val preferences by lazy { Preferences.userRoot().node("com.music.bitchord.desktop/pip-v2") }
    private val _state = MutableStateFlow(read())
    val state: StateFlow<DesktopPipPreferences> = _state
    private val lock = Any()
    private var pending = false
    private val writer by lazy {
        Executors.newSingleThreadScheduledExecutor { work -> Thread(work, "BitChord-PipStore").apply { isDaemon = true } }.also { service ->
            Runtime.getRuntime().addShutdownHook(Thread({ service.submit(::persist).get() }, "BitChord-PipStoreFlush"))
        }
    }
    fun update(change: (DesktopPipPreferences) -> DesktopPipPreferences) = synchronized(lock) {
        val next = change(_state.value)
        if (next == _state.value) return@synchronized
        _state.value = next
        if (!pending) { pending = true; writer.schedule(::persist, 250, TimeUnit.MILLISECONDS) }
    }
    private fun persist() {
        val snapshot = synchronized(lock) { pending = false; _state.value }
        runCatching {
            preferences.putBoolean("on-close", snapshot.onClose)
            preferences.putBoolean("lyrics", snapshot.lyrics)
            preferences.putBoolean("pinned", snapshot.pinned)
            snapshot.bounds?.let { preferences.put("bounds", "${it.x},${it.y},${it.width},${it.height}") }
                ?: preferences.remove("bounds")
            preferences.flush()
        }.onFailure { DesktopTrackLog.log("PiP preferences could not be saved: ${it.javaClass.simpleName}") }
    }
    internal fun flush() { writer.submit(::persist).get() }
    private fun read() = runCatching {
        DesktopPipPreferences(preferences.getBoolean("on-close", false), preferences.getBoolean("lyrics", true),
            preferences.getBoolean("pinned", true), decodeBounds(preferences.get("bounds", "")))
    }.getOrDefault(DesktopPipPreferences())
    internal fun decodeBounds(value: String): DesktopPipBounds? {
        val parts = value.split(',').map { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it == null }) return null
        return DesktopPipBounds(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!)
    }
}
