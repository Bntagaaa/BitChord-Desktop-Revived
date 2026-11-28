package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import java.awt.EventQueue
import java.awt.Toolkit
import java.beans.PropertyChangeListener
import java.util.concurrent.atomic.AtomicBoolean

internal const val X11_WINDOW_SCALE_PROPERTY = "gnome.Gdk/WindowScalingFactor"
internal const val X11_UNSCALED_DPI_PROPERTY = "gnome.Gdk/UnscaledDPI"
private const val XSETTINGS_BASE_DPI = 96f * 1024f

@Composable
internal fun rememberX11WindowScale(): State<Float?> {
    val toolkit = remember {
        if (DesktopPlatform.isLinux) Toolkit.getDefaultToolkit()
            .takeIf { it.javaClass.name == "sun.awt.X11.XToolkit" } else null
    }
    val kde = remember { isKdeDesktop() }
    val xsettingsScale = remember(toolkit, kde) { mutableStateOf(toolkit?.readX11WindowScale(kde)) }
    DisposableEffect(toolkit, kde) {
        val stop = toolkit?.observeX11WindowScale(kde) { xsettingsScale.value = it }
        onDispose { stop?.invoke() }
    }
    // KWin publishes the X11 client scale separately from the output scale. Its exact
    // value also works without xsettingsd and does not include older KDE font-DPI overrides.
    val kdeScale = rememberKdeX11Scale(enabled = toolkit != null)
    return remember(kdeScale, xsettingsScale) {
        derivedStateOf { kdeScale.value ?: xsettingsScale.value }
    }
}

private fun Toolkit.readX11WindowScale(isKde: Boolean): Float? {
    if (javaClass.name != "sun.awt.X11.XToolkit") return null
    return resolveX11WindowScale(
        getDesktopProperty(X11_WINDOW_SCALE_PROPERTY) as? Int,
        getDesktopProperty(X11_UNSCALED_DPI_PROPERTY) as? Int,
        isKde,
    )
}

internal fun resolveX11WindowScale(windowScale: Int?, unscaledDpi: Int?, isKde: Boolean): Float? {
    val integerScale = windowScale?.takeIf { it in 1..8 }?.toFloat() ?: return null
    // Plasma splits fractional X11 scaling between these two XSettings keys: at 150%,
    // WindowScalingFactor is 1 and UnscaledDPI is 147456 (144 DPI in 1024ths).
    // https://github.com/KDE/kde-gtk-config/blob/20c6849a4ed188d87b40a01e269ffdae59336cba/kded/gtkconfig.cpp
    // GNOME uses UnscaledDPI for accessibility text scaling instead, so do not use it
    // to resize GNOME controls or multiply Compose's existing font scale by it.
    if (isKde && unscaledDpi != null) {
        val fractionalScale = integerScale * (unscaledDpi / XSETTINGS_BASE_DPI)
        if (fractionalScale.isFinite() && fractionalScale in 1f..8f) return fractionalScale
    }
    return integerScale
}

internal fun Toolkit.observeX11WindowScale(
    isKde: Boolean = isKdeDesktop(),
    update: (Float?) -> Unit,
): (() -> Unit)? {
    if (javaClass.name != "sun.awt.X11.XToolkit") return null
    val active = AtomicBoolean(true)
    val pending = AtomicBoolean(false)
    var initialized = false
    var previous: Float? = null
    fun scheduleUpdate() {
        if (!active.get() || !pending.compareAndSet(false, true)) return
        // XToolkit publishes each key separately. Read the complete snapshot after the
        // notification batch so 175% -> 200% cannot briefly combine the old DPI with 2x.
        EventQueue.invokeLater {
            pending.set(false)
            if (active.get()) {
                val current = readX11WindowScale(isKde)
                if (!initialized || current != previous) {
                    initialized = true
                    previous = current
                    update(current)
                }
            }
        }
    }
    val listener = PropertyChangeListener { scheduleUpdate() }
    val properties = listOf(X11_WINDOW_SCALE_PROPERTY, X11_UNSCALED_DPI_PROPERTY)
    properties.forEach { addPropertyChangeListener(it, listener) }
    scheduleUpdate()
    return {
        active.set(false)
        properties.forEach { removePropertyChangeListener(it, listener) }
    }
}

internal fun desktopContentDensity(
    systemDensity: Density,
    zoomPercent: Int,
    x11WindowScale: Float? = null,
): Density {
    // XSettings supplies the intended X11 pixels per logical unit, including on XWayland.
    // Mutter publishes its X11 UI scale here. The compositor maps those pixels to the
    // fractional output scale itself. AWT's transform still governs the surface and input.
    val scale = x11WindowScale?.takeIf { it.isFinite() && it in 1f..8f }
        ?: systemDensity.density.takeIf { it.isFinite() && it > 0f }
        ?: 1f
    val fontScale = systemDensity.fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    return Density(scale * (zoomPercent / 100f), fontScale)
}
