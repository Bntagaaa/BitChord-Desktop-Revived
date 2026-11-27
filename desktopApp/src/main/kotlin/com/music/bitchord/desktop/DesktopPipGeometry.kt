package com.music.bitchord.desktop

import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.ceil

internal data class DesktopPipBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** AWT coordinates are logical desktop pixels, including negative monitor origins. */
internal object DesktopPipGeometry {
    const val MIN_WIDTH = 340
    const val MIN_HEIGHT = 210

    /** Keep the compact controls in dp while the native minimum remains in AWT coordinates. */
    fun minimumSize(
        contentDensity: Float,
        awtScaleX: Double,
        awtScaleY: Double,
        frameInsets: Insets,
        workArea: Rectangle,
    ): Dimension {
        val density = contentDensity.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 1.0
        fun minimum(clientDp: Int, nativeScale: Double, decorations: Int, legacyMinimum: Int, available: Int): Int {
            val scale = nativeScale.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            val client = ceil(clientDp * density / scale).toInt()
            return maxOf(legacyMinimum, client + decorations).coerceAtMost(available.coerceAtLeast(1))
        }
        return Dimension(
            minimum(320, awtScaleX, frameInsets.left + frameInsets.right, MIN_WIDTH, workArea.width),
            minimum(180, awtScaleY, frameInsets.top + frameInsets.bottom, MIN_HEIGHT, workArea.height),
        )
    }

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
