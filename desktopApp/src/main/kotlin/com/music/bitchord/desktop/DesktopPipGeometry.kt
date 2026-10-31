package com.music.bitchord.desktop

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

internal data class DesktopPipBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** AWT coordinates are logical desktop pixels, including negative monitor origins. */
internal object DesktopPipGeometry {
    const val MIN_WIDTH = 340
    const val MIN_HEIGHT = 210

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

    fun restore(saved: DesktopPipBounds?, monitors: List<Rectangle>): DesktopPipBounds {
        val screens = monitors.filter { it.width > 0 && it.height > 0 }.ifEmpty { listOf(Rectangle(0, 0, 1280, 720)) }
        val screen = saved?.let { bounds ->
            screens.maxByOrNull { it.intersection(Rectangle(bounds.x, bounds.y, bounds.width.coerceAtLeast(0), bounds.height.coerceAtLeast(0))).let { r ->
                r.width.coerceAtLeast(0).toLong() * r.height.coerceAtLeast(0)
            } }?.takeIf { it.intersects(Rectangle(bounds.x, bounds.y, bounds.width.coerceAtLeast(0), bounds.height.coerceAtLeast(0))) }
        } ?: screens.first()
        val width = (saved?.width?.takeIf { it > 0 } ?: 500).coerceIn(minOf(MIN_WIDTH, screen.width), screen.width)
        val height = (saved?.height?.takeIf { it > 0 } ?: 340).coerceIn(minOf(MIN_HEIGHT, screen.height), screen.height)
        return DesktopPipBounds(
            (saved?.x ?: (screen.x + screen.width - width - 24)).coerceIn(screen.x, screen.x + screen.width - width),
            (saved?.y ?: (screen.y + screen.height - height - 24)).coerceIn(screen.y, screen.y + screen.height - height), width, height)
    }
}
