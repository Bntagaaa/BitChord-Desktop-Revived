package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import java.awt.Toolkit
import java.beans.PropertyChangeListener
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal object DesktopScaleDiagnostics {
    val enabled by lazy { System.getenv("BITCHORD_SCALE_DIAGNOSTICS") == "1" }

    fun logEnvironment() {
        if (!enabled) return
        val distro = runCatching {
            Files.readAllLines(Path.of("/etc/os-release"))
                .firstOrNull { it.startsWith("PRETTY_NAME=") }
                ?.substringAfter('=')?.trim('"')
        }.getOrNull()
        val settings = listOf("XDG_CURRENT_DESKTOP", "XDG_SESSION_TYPE", "GDK_SCALE", "GDK_DPI_SCALE", "J2D_UISCALE")
            .mapNotNull { key -> System.getenv(key)?.let { "$key=${it.filterNot(Char::isISOControl).take(80)}" } }
        val distroName = listOfNotNull(distro, System.getProperty("os.name")).joinToString("/")
        DesktopTrackLog.log(
            "[scale] os=$distroName java=${System.getProperty("java.vendor")} ${Runtime.version()} " +
                "compose=${jarVersion(ComposeWindow::class.java, "ui-desktop")} " +
                "skiko=${jarVersion(org.jetbrains.skiko.SkiaLayer::class.java, "skiko-awt")} " +
                "distribution=${if (System.getenv("APPIMAGE") != null) "appimage" else System.getProperty("bitchord.distribution", "development")} " +
                "java2d.uiScale=${System.getProperty("sun.java2d.uiScale")} ${settings.joinToString(" ")}",
        )
    }

    private fun jarVersion(type: Class<*>, artifact: String): String {
        val file = type.protectionDomain?.codeSource?.location?.path?.substringAfterLast('/')
        return file?.takeIf { it.startsWith("$artifact-") && it.endsWith(".jar") }
            ?.removePrefix("$artifact-")?.removeSuffix(".jar") ?: "unknown"
    }
}

@Composable
internal fun LogDesktopWindowScale(
    name: String,
    window: ComposeWindow,
    system: Density,
    effective: Density,
    zoomPercent: Int,
    x11WindowScale: Int?,
) {
    if (!DesktopScaleDiagnostics.enabled) return

    val toolkit = remember { Toolkit.getDefaultToolkit() }
    val container = LocalWindowInfo.current.containerSize
    var configurationRevision by remember(window) { mutableIntStateOf(0) }
    var previous by remember(window) { mutableStateOf<String?>(null) }
    DisposableEffect(window) {
        val listener = PropertyChangeListener { configurationRevision++ }
        window.addPropertyChangeListener("graphicsConfiguration", listener)
        onDispose { window.removePropertyChangeListener("graphicsConfiguration", listener) }
    }
    LaunchedEffect(window, configurationRevision, system, effective, zoomPercent, x11WindowScale, container) {
        withFrameNanos { }
        delay(200) // Coalesce intermediate resize and display-configuration notifications.
        val configuration = window.graphicsConfiguration ?: return@LaunchedEffect
        val transform = configuration.defaultTransform
        val bounds = configuration.bounds
        val insets = toolkit.getScreenInsets(configuration)
        val mode = configuration.device.displayMode
        val message = "[scale] window=$name toolkit=${toolkit.javaClass.name} renderer=${window.renderApi} " +
            "displayMode=${mode.width}x${mode.height} logicalBounds=${bounds.x},${bounds.y},${bounds.width},${bounds.height} " +
            "availableBounds=${bounds.x + insets.left},${bounds.y + insets.top}," +
            "${bounds.width - insets.left - insets.right},${bounds.height - insets.top - insets.bottom} " +
            "awtTransform=${transform.scaleX}x${transform.scaleY} dpi=${toolkit.screenResolution} " +
            "x11WindowScale=$x11WindowScale systemDensity=${system.density} fontScale=${system.fontScale} " +
            "appZoom=$zoomPercent effectiveDensity=${effective.density} " +
            "contentToAwtPixelRatio=${effective.density / transform.scaleX} " +
            "containerPx=${container.width}x${container.height}"
        if (message != previous) {
            previous = message
            withContext(Dispatchers.IO) { DesktopTrackLog.log(message) }
        }
    }
}
