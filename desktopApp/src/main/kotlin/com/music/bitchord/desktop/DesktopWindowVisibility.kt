package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the window is on screen, and what closing it means. */
internal object DesktopWindowVisibility {

    private val _visible = MutableStateFlow(true)

    /** What the window's own `visible` is driven from. */
    val visible: StateFlow<Boolean> = _visible

    private val _raiseRequest = MutableStateFlow(0L)

    /**
     * Monotonic request id for "show and focus the main window".
     *
     * MPRIS invokes Raise from its D-Bus worker thread, while the native AWT window is owned by
     * Compose on the UI thread. Publishing a request here keeps those two concerns separated.
     */
    val raiseRequest: StateFlow<Long> = _raiseRequest

    /** Whether a close should hide rather than quit. */
    @Volatile
    var keepRunningWhenClosed: Boolean = false

    /** Brings the window back — from the tray menu, or the icon itself. */
    fun show() {
        _visible.value = true
    }

    /**
     * Brings the app back and asks the native window to become the active foreground window.
     * Main.kt performs the actual AWT focus operation after Compose has made the window visible.
     */
    fun raise() {
        _visible.value = true
        _raiseRequest.value = _raiseRequest.value + 1L
    }

    /**
     * Handles the window's close request.
     * @return true when the caller should end the application; false when the
     */
    fun onCloseRequest(): Boolean {
        if (!keepRunningWhenClosed) return true
        // A window put away full-screen comes back full-screen with nothing on it, which reads as
        // the app having broken rather than as it having been hidden.
        DesktopWindowMode.exit()
        _visible.value = false
        DesktopMemoryOptimizer.onWindowHidden()
        DesktopTrackLog.log("window closed to the tray; playback continues")
        return false
    }
}
