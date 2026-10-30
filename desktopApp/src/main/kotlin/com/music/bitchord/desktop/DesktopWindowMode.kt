package com.music.bitchord.desktop

import androidx.compose.ui.window.WindowPlacement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the window is sitting on the screen: floating, maximized, or filling it. */
internal object DesktopWindowMode {
    private val _placement = MutableStateFlow(WindowPlacement.Floating)
    private val _fullScreen = MutableStateFlow(false)
    private val _maximized = MutableStateFlow(false)
    val placement: StateFlow<WindowPlacement> = _placement
    val fullScreen: StateFlow<Boolean> = _fullScreen
    val maximized: StateFlow<Boolean> = _maximized

    // Windows uses a native borderless surface, not Compose's maximized placement.
    // Keep the previous placement so exiting fullscreen restores a maximized window.
    private var beforeFullScreen = WindowPlacement.Floating

    fun toggle() = setFullScreen(!_fullScreen.value)

    fun toggleMaximized() {
        if (_fullScreen.value) {
            setFullScreen(false)
            return
        }
        set(if (_placement.value == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized)
    }

    fun exit() {
        if (_fullScreen.value) setFullScreen(false)
    }

    fun adopt(placement: WindowPlacement) {
        // Native Windows fullscreen changes the AWT extended state without changing our mode.
        // Do not mistake those OS events for a request to exit fullscreen.
        if (_fullScreen.value && DesktopPlatform.isWindows) return
        if (placement == _placement.value) return
        if (placement == WindowPlacement.Floating) _fullScreen.value = false
        set(placement)
    }

    private fun setFullScreen(on: Boolean) {
        if (on == _fullScreen.value) return
        if (on) beforeFullScreen = _placement.value.takeUnless { it == WindowPlacement.Fullscreen }
            ?: WindowPlacement.Floating
        _fullScreen.value = on
        if (!DesktopPlatform.isWindows || !on) {
            set(if (on) WindowPlacement.Fullscreen else beforeFullScreen)
        }
    }

    private fun set(placement: WindowPlacement) {
        _placement.value = placement
        _maximized.value = placement == WindowPlacement.Maximized
    }
}
