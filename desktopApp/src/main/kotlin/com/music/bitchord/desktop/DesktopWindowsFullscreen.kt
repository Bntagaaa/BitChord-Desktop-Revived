package com.music.bitchord.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Window

/** Switch existing HWND to borderless fullscreen without recreating its Skiko surface. */
internal object DesktopWindowsFullscreen {
    private interface User32Fullscreen : StdCallLibrary {
        fun GetWindowLongW(hwnd: Pointer, index: Int): Int
        fun SetWindowLongW(hwnd: Pointer, index: Int, value: Int): Int
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, w: Int, h: Int, flags: Int): Boolean
    }
    private val user32 by lazy {
        if (DesktopPlatform.isWindows) runCatching { Native.load("user32", User32Fullscreen::class.java) }.getOrNull() else null
    }
    private data class Saved(val style: Int, val bounds: Rectangle, val extendedState: Int)
    private var current: Window? = null
    private var saved: Saved? = null
    private const val STYLE = -16
    private const val OVERLAPPED = 0x00CF0000
    private const val FRAMECHANGED = 0x0020
    private const val NOZORDER = 0x0004
    private const val NOOWNERZORDER = 0x0200

    fun apply(window: Window, enabled: Boolean): Boolean {
        if (!DesktopPlatform.isWindows || window !is Frame || !window.isDisplayable) return false
        val api = user32 ?: return false
        val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return false
        return runCatching {
            if (enabled) {
                if (current === window && saved != null) return true
                if (saved != null) return false
                val style = api.GetWindowLongW(hwnd, STYLE)
                val old = Saved(style, window.bounds, window.extendedState)
                val monitor = window.graphicsConfiguration.bounds
                window.extendedState = Frame.NORMAL
                api.SetWindowLongW(hwnd, STYLE, style and OVERLAPPED.inv())
                if (!api.SetWindowPos(hwnd, null, monitor.x, monitor.y, monitor.width, monitor.height,
                        FRAMECHANGED or NOZORDER or NOOWNERZORDER)) {
                    api.SetWindowLongW(hwnd, STYLE, style)
                    window.bounds = old.bounds
                    window.extendedState = old.extendedState
                    return false
                }
                saved = old
                current = window
            } else if (current === window) {
                val old = saved ?: return false
                api.SetWindowLongW(hwnd, STYLE, old.style)
                api.SetWindowPos(hwnd, null, old.bounds.x, old.bounds.y, old.bounds.width, old.bounds.height,
                    FRAMECHANGED or NOZORDER or NOOWNERZORDER)
                window.extendedState = old.extendedState
                saved = null
                current = null
            }
            true
        }.onFailure { DesktopTrackLog.log("native fullscreen: ${it.message}") }.getOrDefault(false)
    }
}
