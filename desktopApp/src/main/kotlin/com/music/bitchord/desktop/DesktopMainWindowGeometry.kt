package com.music.bitchord.desktop

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

internal data class DesktopMainWindowBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** AWT coordinates are logical desktop pixels, including negative monitor origins. */
internal object DesktopMainWindowGeometry {
    const val DEFAULT_WIDTH = 1_220
    const val DEFAULT_HEIGHT = 780
    const val MIN_WIDTH = 900
    const val MIN_HEIGHT = 600

    /** How much of the window must still be on a monitor for its saved place to be kept. */
    private const val VISIBLE_WIDTH = 96
    private const val VISIBLE_HEIGHT = 48

    fun screens(): List<Rectangle> = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
            val config = device.defaultConfiguration
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
            Rectangle(config.bounds).apply {
                x += insets.left; y += insets.top
                width -= insets.left + insets.right; height -= insets.top + insets.bottom
            }
        }.filter { it.width > 0 && it.height > 0 }
    }.getOrDefault(emptyList())

    /**
     * The bounds the main window opens at. A saved window that is still on a monitor keeps its
     * exact place and size — including a negative origin on a monitor left of the primary, and
     * including a window the reader deliberately left hanging over an edge. Only a window no
     * monitor can show any more (the monitor it was on is gone, or its size belongs to another
     * desktop) is moved and shrunk onto the first monitor. With nothing saved, the window opens
     * centred at its usual size.
     */
    fun restore(saved: DesktopMainWindowBounds?, monitors: List<Rectangle>): DesktopMainWindowBounds {
        val screens = monitors.filter { it.width > 0 && it.height > 0 }
            .ifEmpty { listOf(Rectangle(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT)) }
        if (saved != null && saved.width > 0 && saved.height > 0) {
            val rect = Rectangle(saved.x, saved.y, saved.width, saved.height)
            val visible = screens.any { screen ->
                val shared = rect.intersection(screen)
                shared.width >= minOf(VISIBLE_WIDTH, saved.width) &&
                    shared.height >= minOf(VISIBLE_HEIGHT, saved.height)
            }
            if (visible) return saved
        }
        val screen = screens.first()
        val width = (saved?.width?.takeIf { it > 0 } ?: DEFAULT_WIDTH)
            .coerceIn(minOf(MIN_WIDTH, screen.width), screen.width)
        val height = (saved?.height?.takeIf { it > 0 } ?: DEFAULT_HEIGHT)
            .coerceIn(minOf(MIN_HEIGHT, screen.height), screen.height)
        return DesktopMainWindowBounds(
            x = (saved?.x ?: (screen.x + (screen.width - width) / 2))
                .coerceIn(screen.x, screen.x + screen.width - width),
            y = (saved?.y ?: (screen.y + (screen.height - height) / 2))
                .coerceIn(screen.y, screen.y + screen.height - height),
            width = width,
            height = height,
        )
    }
}
