package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Window lifecycle only. Never owns playback, a decoder, or the lyrics repository. */
internal class DesktopPipController(
    private val canOpen: () -> Boolean = { DesktopPipGeometry.screens().isNotEmpty() },
    private val hideMain: () -> Unit = { DesktopWindowVisibility.hideForPip() },
    private val restoreMain: () -> Unit = { DesktopWindowVisibility.raise() },
    private val canCloseToTray: () -> Boolean = { DesktopWindowVisibility.keepRunningWhenClosed },
) {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open
    private val _raise = MutableStateFlow(0L)
    val raise: StateFlow<Long> = _raise
    private var disposed = false
    private var ready = false

    @Synchronized fun show(): Boolean {
        if (disposed || !canOpen()) return false
        if (_open.value) { _raise.value += 1; return true }
        ready = false
        _raise.value += 1
        _open.value = true
        return true
    }
    @Synchronized fun windowReady(request: Long = _raise.value) {
        if (disposed || !_open.value || ready || request != _raise.value) return
        ready = true
        hideMain() // Only after a real floating window exists; failed opens keep main/tray usable.
    }
    @Synchronized fun restore() {
        if (disposed || !_open.value) return
        ready = false
        _open.value = false
        restoreMain()
    }
    @Synchronized fun mainShown() { ready = false; _open.value = false }
    @Synchronized fun close(request: Long = _raise.value) {
        if (!_open.value || request != _raise.value) return
        _open.value = false
        ready = false
        // This is an auxiliary window: without a recoverable tray return to main, never
        // unexpectedly terminate audio. Explicit application Quit remains a separate action.
        if (!canCloseToTray()) restoreMain()
    }
    @Synchronized fun failed(request: Long = _raise.value) {
        if (request != _raise.value || !_open.value) return
        _open.value = false
        ready = false
        if (!canCloseToTray()) restoreMain()
    }
    @Synchronized fun dispose() { disposed = true; ready = false; _open.value = false }
}
