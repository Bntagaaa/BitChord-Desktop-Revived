package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import java.awt.Toolkit
import java.beans.PropertyChangeListener

internal const val X11_WINDOW_SCALE_PROPERTY = "gnome.Gdk/WindowScalingFactor"

@Composable
internal fun rememberX11WindowScale(): State<Int?> {
    val toolkit = remember {
        if (DesktopPlatform.isLinux) Toolkit.getDefaultToolkit() else null
    }
    val scale = remember(toolkit) { mutableStateOf(toolkit?.readX11WindowScale()) }
    DisposableEffect(toolkit) {
        val stop = toolkit?.observeX11WindowScale { scale.value = it }
        onDispose { stop?.invoke() }
    }
    return scale
}

private fun Toolkit.readX11WindowScale(): Int? {
    if (javaClass.name != "sun.awt.X11.XToolkit") return null
    return (getDesktopProperty(X11_WINDOW_SCALE_PROPERTY) as? Int)?.takeIf { it in 1..8 }
}

internal fun Toolkit.observeX11WindowScale(update: (Int?) -> Unit): (() -> Unit)? {
    if (javaClass.name != "sun.awt.X11.XToolkit") return null
    val listener = PropertyChangeListener { update(readX11WindowScale()) }
    addPropertyChangeListener(X11_WINDOW_SCALE_PROPERTY, listener)
    update(readX11WindowScale())
    return { removePropertyChangeListener(X11_WINDOW_SCALE_PROPERTY, listener) }
}

internal fun desktopContentDensity(
    systemDensity: Density,
    zoomPercent: Int,
    x11WindowScale: Int? = null,
): Density {
    // XSettings supplies the intended X11 pixels per logical unit, including on XWayland.
    // Mutter publishes its X11 UI scale here. The compositor maps those pixels to the
    // fractional output scale itself. AWT's transform still governs the surface and input.
    val scale = x11WindowScale?.takeIf { it in 1..8 }?.toFloat()
        ?: systemDensity.density.takeIf { it.isFinite() && it > 0f }
        ?: 1f
    val fontScale = systemDensity.fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    return Density(scale * (zoomPercent / 100f), fontScale)
}
