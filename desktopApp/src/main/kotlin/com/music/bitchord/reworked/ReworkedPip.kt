package com.music.bitchord.reworked

import com.music.bitchord.data.lyrics.LyricLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Rectangle

internal data class ReworkedPipLyrics(
    val trackId: String? = null,
    val lines: List<LyricLine>? = null,
    val message: String? = null,
)

/** A small window shares the current player; opening it never loads or pauses a track. */
internal object ReworkedPip {
    private val _isOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _isOpen
    fun open() { _isOpen.value = true }
    fun close() { _isOpen.value = false }
}

internal data class ReworkedPipBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun fit(workAreas: List<Rectangle>): ReworkedPipBounds {
        val areas = workAreas.filter { it.width > 0 && it.height > 0 }
        if (areas.isEmpty()) return ReworkedPipBounds(80, 80, 440, 360)
        val old = Rectangle(x, y, width.coerceAtLeast(1), height.coerceAtLeast(1))
        val screen = areas.maxBy { area ->
            val intersection = area.intersection(old)
            intersection.width.coerceAtLeast(0).toLong() * intersection.height.coerceAtLeast(0)
        }
        val w = width.coerceIn(minOf(320, screen.width), screen.width)
        val h = height.coerceIn(minOf(220, screen.height), screen.height)
        return ReworkedPipBounds(
            x.coerceIn(screen.x, screen.x + screen.width - w),
            y.coerceIn(screen.y, screen.y + screen.height - h), w, h,
        )
    }

    companion object {
        fun parse(value: String): ReworkedPipBounds? {
            val fields = value.split(',').map { it.toIntOrNull() }
            if (fields.size != 4 || fields.any { it == null }) return null
            if (fields[2]!! <= 0 || fields[3]!! <= 0) return null
            return ReworkedPipBounds(fields[0]!!, fields[1]!!, fields[2]!!, fields[3]!!)
        }
    }
}

internal object ReworkedPipPreferences {
    private val store by lazy { DesktopStore.preferences.node("pip") }
    private val _onClose = MutableStateFlow(store.getBoolean("onClose", false))
    private val _pinned = MutableStateFlow(store.getBoolean("pinned", true))
    private val _lyrics = MutableStateFlow(store.getBoolean("lyrics", true))
    val onClose: StateFlow<Boolean> = _onClose
    val pinned: StateFlow<Boolean> = _pinned
    val lyrics: StateFlow<Boolean> = _lyrics
    fun setOnClose(value: Boolean) { store.putBoolean("onClose", value); _onClose.value = value }
    fun setPinned(value: Boolean) { store.putBoolean("pinned", value); _pinned.value = value }
    fun setLyrics(value: Boolean) { store.putBoolean("lyrics", value); _lyrics.value = value }
    fun bounds(): ReworkedPipBounds = ReworkedPipBounds.parse(store.get("bounds", "")) ?: ReworkedPipBounds(80, 80, 440, 360)
    fun saveBounds(bounds: Rectangle) {
        if (bounds.width <= 0 || bounds.height <= 0) return
        store.put("bounds", "${bounds.x},${bounds.y},${bounds.width},${bounds.height}")
        runCatching { store.flush() }
    }
}

internal fun reworkedPipLyric(lyrics: ReworkedPipLyrics, trackId: String?, positionMs: Long): String? {
    if (trackId == null || lyrics.trackId != trackId) return null
    val lines = lyrics.lines ?: return lyrics.message
    if (lines.isEmpty()) return lyrics.message
    if (lines.none { it.timeMs > 0L }) return lines.joinToString("\n") { it.text }
    val active = lines.lastOrNull { it.timeMs <= positionMs } ?: return "Instrumental"
    if (active.isGap || (active.hasKnownEnd && positionMs > active.endMs + 750L)) return "Instrumental"
    return active.text
}
