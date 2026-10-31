package com.music.bitchord.desktop

import androidx.compose.ui.window.WindowPlacement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the desired window placement.
 *
 * Compose's WindowState reports the *previous* OS placement asynchronously while it applies
 * an app-requested transition. Those stale values must not be adopted as new user intents:
 * otherwise Linux/KWin can restore Fullscreen immediately after a request to exit it.
 */
internal class DesktopWindowPlacementController(private val isWindows: Boolean) {
    private val _placement = MutableStateFlow(WindowPlacement.Floating)
    private val _fullScreen = MutableStateFlow(false)
    private val _maximized = MutableStateFlow(false)

    val placement: StateFlow<WindowPlacement> = _placement
    val fullScreen: StateFlow<Boolean> = _fullScreen
    val maximized: StateFlow<Boolean> = _maximized

    private var beforeFullScreen = WindowPlacement.Floating
    /** A requested change that has not yet been reported back by Compose's WindowState. */
    private var awaitingPlacement: WindowPlacement? = null

    fun toggle() = setFullScreen(!_fullScreen.value)

    fun toggleMaximized() {
        if (_fullScreen.value) {
            setFullScreen(false)
            return
        }
        request(if (_placement.value == WindowPlacement.Maximized) WindowPlacement.Floating
                else WindowPlacement.Maximized)
    }

    fun exit() {
        if (_fullScreen.value) setFullScreen(false)
    }

    /** Escape exits OS fullscreen before closing the expanded player. */
    fun exitOnEscape(): Boolean {
        if (!_fullScreen.value) return false
        exit()
        return true
    }

    /**
     * An OS/user placement event. Ignore old snapshots while an app transition is pending;
     * accept an acknowledgement only once WindowState reaches the requested placement.
     * Changes made through window chrome remain discoverable when no request is outstanding.
     */
    fun adopt(observed: WindowPlacement) {
        // Windows uses a separately managed native HWND for fullscreen.
        // OS extended-state changes while that HWND is borderless must not rewrite our mode.
        if (isWindows && _fullScreen.value) return

        val expected = awaitingPlacement
        if (expected != null) {
            if (observed == expected) awaitingPlacement = null
            return
        }
        if (observed == _placement.value) return

        if (observed == WindowPlacement.Fullscreen) {
            // The compositor entered fullscreen without a BitChord toggle (e.g. a window shortcut).
            beforeFullScreen = _placement.value.takeUnless { it == WindowPlacement.Fullscreen }
                ?: WindowPlacement.Floating
            _fullScreen.value = true
        } else {
            _fullScreen.value = false
        }
        update(observed)
    }

    private fun setFullScreen(on: Boolean) {
        if (_fullScreen.value == on) return
        if (on) {
            beforeFullScreen = _placement.value.takeUnless { it == WindowPlacement.Fullscreen }
                ?: WindowPlacement.Floating
        }
        _fullScreen.value = on

        // Windows native fullscreen never changes WindowPlacement; Main.kt toggles its HWND.
        if (!isWindows) request(if (on) WindowPlacement.Fullscreen else beforeFullScreen)
    }

    private fun request(value: WindowPlacement) {
        if (_placement.value == value) return
        awaitingPlacement = value
        update(value)
    }

    private fun update(value: WindowPlacement) {
        _placement.value = value
        _maximized.value = value == WindowPlacement.Maximized
    }
}

/** Shared host for the desktop's player, window chrome, and escape handler. */
internal object DesktopWindowMode {
    private val controller = DesktopWindowPlacementController(DesktopPlatform.isWindows)

    val placement: StateFlow<WindowPlacement> = controller.placement
    val fullScreen: StateFlow<Boolean> = controller.fullScreen
    val maximized: StateFlow<Boolean> = controller.maximized

    fun toggle() = controller.toggle()
    fun toggleMaximized() = controller.toggleMaximized()
    fun exit() = controller.exit()
    fun exitOnEscape(): Boolean = controller.exitOnEscape()
    fun adopt(placement: WindowPlacement) = controller.adopt(placement)
}
