package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.music.bitchord.playback.PlaybackPosition
import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.beans.PropertyChangeListener
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import org.jetbrains.compose.resources.painterResource

@OptIn(FlowPreview::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopPipWindow(
    controller: DesktopPipController,
    presentation: DesktopPipState,
    position: PlaybackPosition,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    x11WindowScale: Float? = null,
) {
    val open by controller.open.collectAsState()
    if (!open) return
    val preferences by DesktopPipSettings.state.collectAsState()
    val reduceBlur by DesktopAppearanceSettings.reduceDynamicBlur.collectAsState()
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val raise by controller.raise.collectAsState()
    val appIcon by DesktopAppIconSettings.selected.collectAsState()
    var spaceHeld by remember { mutableStateOf(false) }
    val initial = remember { DesktopPipGeometry.restore(preferences.bounds, DesktopPipGeometry.screens()) }
    val state = rememberWindowState(position = WindowPosition.Absolute(initial.x.dp, initial.y.dp),
        width = initial.width.dp, height = initial.height.dp)
    val pinSupported = remember { runCatching { Toolkit.getDefaultToolkit().isAlwaysOnTopSupported }.getOrDefault(false) }
    val handler = remember(controller, raise) {
        object : WindowExceptionHandlerFactory {
            override fun exceptionHandler(window: java.awt.Window) = object : WindowExceptionHandler {
                override fun onException(throwable: Throwable) {
                    DesktopTrackLog.log("PiP window failed: ${throwable.javaClass.simpleName}")
                    EventQueue.invokeLater { controller.failed(raise) }
                }
            }
        }
    }
    CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides handler) {
        Window(onCloseRequest = { controller.close(raise) }, state = state, title = "BitChord · Picture in picture", icon = painterResource(appIcon.resource),
            undecorated = false, transparent = false, resizable = true,
            alwaysOnTop = preferences.pinned && pinSupported,
            onPreviewKeyEvent = { event ->
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.Escape -> { controller.restore(); true }
                    event.key == Key.Spacebar -> {
                        if (event.type == KeyEventType.KeyUp) spaceHeld = false
                        else if (event.type == KeyEventType.KeyDown && !spaceHeld) {
                            spaceHeld = true
                            if (!presentation.controlsLocked && presentation.song != null) onPlayPause()
                        }
                        true
                    }
                    else -> false
                }
            }) {
            val native = window
            val systemDensity = LocalDensity.current
            // PiP keeps its own compact layout and does not inherit the main window's zoom.
            val pipDensity = remember(systemDensity, x11WindowScale) {
                desktopContentDensity(systemDensity, 100, x11WindowScale)
            }
            val currentDensity by rememberUpdatedState(pipDensity.density)
            val currentRequest by rememberUpdatedState(raise)
            DisposableEffect(native) {
                updatePipMinimumSize(native, currentDensity)
                val listener = object : WindowAdapter() {
                    override fun windowLostFocus(event: WindowEvent) { spaceHeld = false }
                    override fun windowClosed(event: WindowEvent) { controller.close(currentRequest) }
                }
                val hierarchy = HierarchyListener { event ->
                    if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L && !native.isDisplayable) {
                        controller.close(currentRequest)
                    }
                }
                val configuration = PropertyChangeListener { updatePipMinimumSize(native, currentDensity) }
                native.addWindowListener(listener)
                native.addWindowFocusListener(listener)
                native.addHierarchyListener(hierarchy)
                native.addPropertyChangeListener("graphicsConfiguration", configuration)
                onDispose {
                    native.removeWindowListener(listener)
                    native.removeWindowFocusListener(listener)
                    native.removeHierarchyListener(hierarchy)
                    native.removePropertyChangeListener("graphicsConfiguration", configuration)
                    val bounds = native.bounds
                    if (bounds.width > 0 && bounds.height > 0) DesktopPipSettings.update {
                        it.copy(bounds = DesktopPipBounds(bounds.x, bounds.y, bounds.width, bounds.height))
                    }
                }
            }
            LaunchedEffect(native, pipDensity) {
                withFrameNanos { }
                updatePipMinimumSize(native, pipDensity.density)
            }
            LaunchedEffect(native, raise) {
                repeat(6) {
                    withFrameNanos { }
                    if (native.isShowing) {
                        // Insets are final only once the native frame is mapped.
                        updatePipMinimumSize(native, currentDensity)
                        controller.windowReady(raise)
                        return@LaunchedEffect
                    }
                }
                controller.failed(raise)
            }
            LaunchedEffect(raise) {
                state.isMinimized = false
                EventQueue.invokeLater { native.toFront(); native.requestFocus() }
            }
            LaunchedEffect(state, native) {
                snapshotFlow { state.position to state.size }.debounce(350).collect {
                    if (!state.isMinimized) {
                        val bounds = native.bounds
                        DesktopPipSettings.update { it.copy(bounds = DesktopPipBounds(bounds.x, bounds.y, bounds.width, bounds.height)) }
                    }
                }
            }
            LogDesktopWindowScale("pip", native, systemDensity, pipDensity, 100, x11WindowScale)
            CompositionLocalProvider(LocalDensity provides pipDensity) {
                MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography()) {
                    // Remove the image/lyric composition while iconified. Playback remains owned by app.
                    if (!state.isMinimized) DesktopPipUi(presentation, position, preferences, reduceBlur, reduceAnimation,
                        onRestore = controller::restore, onClose = { controller.close(raise) },
                        onPin = { DesktopPipSettings.update { it.copy(pinned = !it.pinned) } },
                        onLyrics = { DesktopPipSettings.update { it.copy(lyrics = !it.lyrics) } },
                        onPlayPause = onPlayPause, onPrevious = onPrevious, onNext = onNext, onSeek = onSeek,
                        pinSupported = pinSupported)
                    else Box(Modifier.fillMaxSize())
                }
            }
        }
    }
}

private fun updatePipMinimumSize(window: java.awt.Window, contentDensity: Float) {
    val configuration = window.graphicsConfiguration ?: return
    val workArea = Rectangle(configuration.bounds)
    runCatching { Toolkit.getDefaultToolkit().getScreenInsets(configuration) }.getOrNull()?.let {
        workArea.width -= it.left + it.right
        workArea.height -= it.top + it.bottom
    }
    val transform = configuration.defaultTransform
    val minimum = DesktopPipGeometry.minimumSize(
        contentDensity, transform.scaleX, transform.scaleY, window.insets, workArea,
    )
    if (window.minimumSize != minimum) window.minimumSize = minimum
}
