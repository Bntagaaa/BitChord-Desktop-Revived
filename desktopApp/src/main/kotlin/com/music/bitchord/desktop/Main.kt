package com.music.bitchord.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import com.music.bitchord.ui.LocalLyricAnimationAllowed
import com.music.bitchord.ui.ProvideDesktopPresentationLifecycle
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.spotify.SpotifyLibrary
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import kotlinx.coroutines.swing.Swing
import org.jetbrains.compose.resources.painterResource

fun main() {
    DesktopStartupDiagnostics.processEntry()
    val bootstrapStarted = System.nanoTime()
    DesktopStartupDiagnostics.record(DesktopStartupDiagnostics.Milestone.BOOTSTRAP_START)
    DesktopApplicationResources.installShutdownHook()
    DesktopImageLoader.install()
    // Read the per-install welcome marker before services initialize or write preferences.
    val welcomeLaunch = DesktopWelcomeOnboarding.inspectStartup()
    // Before any WebView exists, so sign-in and PoToken pages share the same cookie jar.
    DesktopWebCookies.install()
    // The player is the phone's, from the shared UI module; this is what it reads underneath.
    PlayerPlatform.install(DesktopPlayerHost)
    com.music.bitchord.ui.AppUi.install(DesktopAppUiHost)
    // The data layer both apps share logs through here, and keeps translated
    // lyrics beside the rest of the desktop's cache.
    DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
        DesktopTrackLog.log("$tag/$level: $message" + (error?.let { " (${it.message})" } ?: ""))
    }
    LyricsTranslation.cacheDir = DesktopMediaCache.directory.toFile()
    // Spotify's account library shares the web-player token implementation used by Canvas.
    SpotifyLibrary.auth = SpotifyLibrary.Auth {
        DesktopSpotifyLibraryAuth.tokens()
    }
    // YouTube playback is the phone's StreamResolver over InnerTubeX, with BotGuard PoTokens
    // minted in JavaFX's WebView where the phone uses Android's.
    TrackLog.echo = { level, tag, message, error ->
        DesktopTrackLog.log("$tag/$level: $message" + (error?.let { " (${it.message})" } ?: ""))
    }
    val preferences = DesktopPersistence()
    DesktopWindowVisibility.configureTray(
        preferences.boolean("tray_icon", true),
        preferences.boolean("close_to_tray", true),
    )
    // Unlike the main Window, this service survives tray/PiP transitions.
    val playbackService = DesktopPlaybackService(preferences)
    // Resolve System mode before Compose paints so the initial palette is correct.
    DesktopTheme.initialize()
    DesktopStartupDiagnostics.record(
        DesktopStartupDiagnostics.Milestone.BOOTSTRAP_COMPLETE,
        durationNanos = System.nanoTime() - bootstrapStarted,
    )
    try { desktopMain(welcomeLaunch, playbackService) } finally {
        playbackService.close()
        DesktopApplicationResources.close()
    }
}

@OptIn(FlowPreview::class)
private fun desktopMain(
    welcomeLaunch: DesktopWelcomeOnboarding.Launch,
    playbackService: DesktopPlaybackService,
) = application {
    val startup = remember { DesktopStartup() }
    var backgroundReady by remember { mutableStateOf(false) }
    DesktopStartupDiagnostics.once(DesktopStartupDiagnostics.Milestone.AWT_READY)
    DisposableEffect(Unit) { onDispose { DesktopApplicationResources.close() } }
    LaunchedEffect(startup) {
        startup.awaitInteractive()
        playbackService.startRestoring()
        backgroundReady = true
        withContext(Dispatchers.IO) {
            runCatching { DesktopPresence.install(DesktopApplicationResources.owner) }
                .onFailure { DesktopTrackLog.log("presence initialization failed: ${it.javaClass.simpleName}") }
        }
    }
    // Closing puts the window away rather than ending the process, while there is a tray icon to
    // bring it back from — see [DesktopWindowVisibility].
    val visible by DesktopWindowVisibility.visible.collectAsState()
    val raiseRequest by DesktopWindowVisibility.raiseRequest.collectAsState()
    // Hoisted so the player can fill the screen and system caption actions stay synchronized.
    val placement by DesktopWindowMode.placement.collectAsState()
    val fullScreen by DesktopWindowMode.fullScreen.collectAsState()
    // Where and how large the window was left last time, recovered onto a monitor that exists
    // now — see [DesktopMainWindowGeometry.restore].
    val openingBounds = remember {
        DesktopMainWindowGeometry.restore(
            DesktopMainWindowSettings.bounds.value,
            DesktopMainWindowGeometry.screens(),
        )
    }
    val state = rememberWindowState(
        position = WindowPosition.Absolute(openingBounds.x.dp, openingBounds.y.dp),
        width = openingBounds.width.dp,
        height = openingBounds.height.dp,
    )
    val pipHost = remember { DesktopPipHost() }
    val appIcon by DesktopAppIconSettings.selected.collectAsState()
    // Linux restores its native fullscreen state first (see below). Updating WindowState
    // directly to Maximized would skip fullscreen=false in ComposeWindow's native setter.
    LaunchedEffect(placement) {
        if (DesktopPlatform.isWindows) state.placement = placement
    }
    // Observe externally initiated maximize/restore changes. Do not echo a stale Fullscreen
    // observation back into our controller after a Linux exit request: on KDE/Wayland, Compose
    // can publish Fullscreen once more while the compositor is processing the restore.
    LaunchedEffect(state) {
        snapshotFlow { state.placement }.collect { observed ->
            if (shouldIgnoreStaleFullscreenObservation(
                    DesktopPlatform.isLinux, observed, DesktopWindowMode.placement.value
                )) {
                return@collect
            }
            DesktopWindowMode.adopt(observed)
        }
    }
    LaunchedEffect(visible, state.isMinimized) {
        if (!visible || state.isMinimized) {
            // The main Window remains composed while hidden, so trim inactive caches
            // without waiting for native window destruction (which no longer happens).
            DesktopMemoryOptimizer.onWindowHidden()
        } else {
            DesktopMemoryOptimizer.onWindowShown()
        }
    }
    // Background transport services and PiP stay independent of main-window visibility.
    if (backgroundReady) DesktopBackgroundPlaybackHost(playbackService, pipHost)
    DesktopPipHostWindow(pipHost)
    // Keep the main Window IN composition when hidden to tray or PiP, just like 1.9.3.
    // Its Compose page state, Home scroll and native peer survive show/hide cycles.
    // The playback engine stays process-owned; no stream or UI reconstruction on restore.
    Window(
        onCloseRequest = { if (DesktopWindowVisibility.onCloseRequest()) exitApplication() },
        visible = visible,
        title = "BitChord",
        icon = painterResource(appIcon.resource),
        state = state,
        // The peer must be opaque and decorated from creation. Windows owns the entire
        // non-client frame: caption, drag, resize, system menu and Snap.
        undecorated = false,
        transparent = false,
        resizable = true,
    ) {
        val composeWindow = window
        DisposableEffect(composeWindow) {
            DesktopStartupDiagnostics.once(DesktopStartupDiagnostics.Milestone.WINDOW_CREATED)
            if (DesktopStartupDiagnostics.isEnabled) {
                composeWindow.onRenderApiChanged {
                    DesktopStartupDiagnostics.once(DesktopStartupDiagnostics.Milestone.SKIKO_READY)
                }
            }
            val listener = HierarchyListener { event ->
                if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && composeWindow.isShowing) {
                    startup.nativeWindowShowing()
                }
            }
            composeWindow.addHierarchyListener(listener)
            if (composeWindow.isShowing) startup.nativeWindowShowing()
            onDispose { composeWindow.removeHierarchyListener(listener) }
        }
        // Remember the ordinary floating bounds across launches. A maximised, fullscreen or
        // minimised window has no size or place of its own worth keeping, so the last floating
        // ones are what the next launch restores — quitting while maximised still opens at the
        // size the reader had before it.
        LaunchedEffect(composeWindow) {
            snapshotFlow { Triple(state.placement, state.isMinimized, state.position to state.size) }
                .debounce(350)
                .collect { (placement, minimized, _) ->
                    if (placement == androidx.compose.ui.window.WindowPlacement.Floating &&
                        !minimized && !DesktopWindowMode.fullScreen.value
                    ) {
                        val bounds = composeWindow.bounds
                        if (bounds.width > 0 && bounds.height > 0) {
                            DesktopMainWindowSettings.update(
                                DesktopMainWindowBounds(bounds.x, bounds.y, bounds.width, bounds.height),
                            )
                        }
                    }
                }
        }
        // The debounce may not have run when the process is sent away (tray Quit, an update
        // install); this is the last chance to record the window as the reader left it.
        DisposableEffect(composeWindow) {
            onDispose {
                val bounds = composeWindow.bounds
                if (state.placement == androidx.compose.ui.window.WindowPlacement.Floating &&
                    !state.isMinimized && !DesktopWindowMode.fullScreen.value &&
                    bounds.width > 0 && bounds.height > 0
                ) {
                    DesktopMainWindowSettings.update(
                        DesktopMainWindowBounds(bounds.x, bounds.y, bounds.width, bounds.height),
                    )
                }
            }
        }
        // ComposeWindow.placement=Maximized only sets the maximized bit; it does NOT
        // clear the fullscreen bit (unlike placement=Floating). Going directly from
        // Fullscreen -> Maximized therefore leaves KWin fullscreen. Always exit the
        // graphics-device fullscreen session and pass through Floating first.
        LaunchedEffect(composeWindow, placement) {
            if (DesktopPlatform.isLinux) {
                val destination = placement
                val device = composeWindow.graphicsConfiguration.device
                // JVM graphics-device fullscreen can still be active even if a late
                // window-state observation describes a different placement.
                val current = if (device.fullScreenWindow === composeWindow)
                    androidx.compose.ui.window.WindowPlacement.Fullscreen
                else composeWindow.placement
                val transitions = linuxFullscreenRestorationPath(
                    current = current, requested = destination,
                )
                for (step in transitions) {
                    if (DesktopWindowMode.placement.value != destination) return@LaunchedEffect
                    withContext(Dispatchers.Swing) {
                        if (step != androidx.compose.ui.window.WindowPlacement.Fullscreen) {
                            // JBR's Wayland GraphicsDevice issues requestUnsetFullScreen()
                            // to KWin; this is the same fullscreen session Compose owns.
                            val device = composeWindow.graphicsConfiguration.device
                            if (device.fullScreenWindow === composeWindow) {
                                device.fullScreenWindow = null
                            }
                        }
                        composeWindow.placement = step
                    }
                    state.placement = step
                    // Ensure the floating step has been dispatched to the compositor
                    // before attempting to maximize the restored window.
                    if (step == androidx.compose.ui.window.WindowPlacement.Floating &&
                        destination == androidx.compose.ui.window.WindowPlacement.Maximized) {
                        delay(100)
                    }
                }
                DesktopTrackLog.log("linux window placement: requested $destination, native=${composeWindow.placement}")
            }
        }

        // AWT receives these keys even when a Compose child/embedded video has focus.
        // Exit is deliberately on KEY_PRESSED (not KEY_UP), which is not guaranteed when
        // a Wayland fullscreen transition replaces/defocuses the current surface.
        DisposableEffect(composeWindow) {
            val focusManager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
            val dispatcher = java.awt.KeyEventDispatcher { event ->
                if (!DesktopPlatform.isLinux ||
                    event.id != java.awt.event.KeyEvent.KEY_PRESSED ||
                    (event.keyCode != java.awt.event.KeyEvent.VK_ESCAPE &&
                        event.keyCode != java.awt.event.KeyEvent.VK_F11) ||
                    !javax.swing.SwingUtilities.isDescendingFrom(event.component, composeWindow) ||
                    (!DesktopWindowMode.fullScreen.value &&
                        composeWindow.placement != androidx.compose.ui.window.WindowPlacement.Fullscreen &&
                        state.placement != androidx.compose.ui.window.WindowPlacement.Fullscreen)
                ) {
                    false
                } else {
                    DesktopWindowMode.exit()
                    // Emergency path must also clear the GraphicsDevice fullscreen session
                    // before attempting to restore a maximized window.
                    val device = composeWindow.graphicsConfiguration.device
                    if (device.fullScreenWindow === composeWindow) {
                        device.fullScreenWindow = null
                    }
                    val restore = DesktopWindowMode.placement.value
                    composeWindow.placement = androidx.compose.ui.window.WindowPlacement.Floating
                    if (restore != androidx.compose.ui.window.WindowPlacement.Floating) {
                        composeWindow.placement = restore
                    }
                    state.placement = restore
                    true
                }
            }
            focusManager.addKeyEventDispatcher(dispatcher)
            onDispose { focusManager.removeKeyEventDispatcher(dispatcher) }
        }
        // Windows native borderless fullscreen preserves the Skiko window surface.
        // Do not route through WindowPlacement.Maximized (which retains the caption).
        LaunchedEffect(composeWindow, fullScreen) {
            if (DesktopPlatform.isWindows) {
                java.awt.EventQueue.invokeLater {
                    DesktopWindowsFullscreen.apply(composeWindow, fullScreen)
                }
            }
        }
        DisposableEffect(composeWindow) {
            onDispose { if (DesktopPlatform.isWindows) DesktopWindowsFullscreen.apply(composeWindow, false) }
        }

        // Neither Windows nor AppImage should treat spawning a process as a healthy restart.
        // A real, mapped Compose window acknowledges the launch using a unique local marker.
        LaunchedEffect(composeWindow) {
            if ((DesktopPlatform.isLinux || DesktopPlatform.isWindows) && System.getenv("BITCHORD_UPDATE_READY_FILE") != null) {
                DesktopWindowVisibility.raise()
                state.isMinimized = false
                // A mapped window plus a second frame callback, rather than JVM process entry.
                var visibleFrames = 0
                repeat(900) {
                    androidx.compose.runtime.withFrameNanos { }
                    if (composeWindow.isShowing && !state.isMinimized && composeWindow.width > 0 && composeWindow.height > 0) {
                        visibleFrames++
                        if (visibleFrames >= 2 && withContext(Dispatchers.IO) { DesktopUpdateReadiness.acknowledge() }) return@LaunchedEffect
                    } else visibleFrames = 0
                    delay(100)
                }
            }
        }
        val isDarkTheme = DesktopTheme.isDark
        // System mode follows changes while the app remains open and visible. Native probes run off the UI thread.
        LaunchedEffect(startup, visible, state.isMinimized, DesktopTheme.mode) {
            if (!visible || state.isMinimized || DesktopTheme.mode != DesktopThemeMode.SYSTEM) {
                return@LaunchedEffect
            }
            startup.awaitInteractive()
            DesktopTheme.updateSystemDark(withContext(Dispatchers.IO) { DesktopSystemTheme.isDark() })
            while (true) {
                delay(15_000)
                DesktopTheme.updateSystemDark(withContext(Dispatchers.IO) { DesktopSystemTheme.isDark() })
            }
        }
        // Update both the opaque AWT surface and Windows' system caption without remounting UI.
        LaunchedEffect(composeWindow, isDarkTheme) {
            val background = if (!isDarkTheme) {
                java.awt.Color(0xF7F7F9)
            } else {
                java.awt.Color.BLACK
            }
            composeWindow.background = background
            composeWindow.contentPane.background = background
            if (DesktopPlatform.isWindows) {
                DesktopWindowsCaptionAppearance.setDarkMode(isDarkTheme)
            }
        }
        DisposableEffect(composeWindow) {
            onDispose { DesktopWindowsCaptionAppearance.detach(composeWindow) }
        }
        LaunchedEffect(raiseRequest) {
            if (raiseRequest == 0L) return@LaunchedEffect

            // MPRIS Raise is also what desktop-shell media widgets use for "open player".
            // Restore a minimized/hidden BitChord first, then defer the foreground request by one
            // AWT turn so the compositor sees a mapped window before toFront/requestFocus.
            state.isMinimized = false
            composeWindow.isVisible = true
            java.awt.EventQueue.invokeLater {
                composeWindow.toFront()
                composeWindow.requestFocus()
            }
        }
        val openingSize = remember { state.size }
        LaunchedEffect(composeWindow) {
            // The client stays opaque even when DWM accepts a caption material.
            val initialBackground = if (!isDarkTheme) {
                java.awt.Color(0xF7F7F9)
            } else {
                java.awt.Color.BLACK
            }
            composeWindow.background = initialBackground
            composeWindow.contentPane.background = initialBackground
            if (DesktopPlatform.isWindows) {
                // AWT dimensions use logical screen coordinates; its peer applies monitor DPI.
                // Leave the opening size to rememberWindowState, including native frame insets.
                composeWindow.minimumSize = Dimension(DesktopMainWindowGeometry.MIN_WIDTH, DesktopMainWindowGeometry.MIN_HEIGHT)
                // Styling may need the HWND created a frame after composition. Its failure never
                // changes or recreates the decorated window.
                repeat(10) {
                    if (DesktopWindowsCaptionAppearance.attach(composeWindow)) {
                        DesktopWindowBackdrop.apply()
                        return@LaunchedEffect
                    }
                    delay(50)
                }
                DesktopWindowBackdrop.apply()
            } else {
                // Linux AWT/JVM already reports window dimensions in scaled logical units.
                // Applying the graphics transform again doubles HiDPI/fractional scaling
                // under X11/XWayland (notably GNOME on Wayland). Keep macOS unchanged.
                val transform = composeWindow.graphicsConfiguration.defaultTransform
                val scaleX = if (DesktopPlatform.isLinux) 1.0 else transform.scaleX
                val scaleY = if (DesktopPlatform.isLinux) 1.0 else transform.scaleY
                composeWindow.minimumSize = Dimension(
                    (DesktopMainWindowGeometry.MIN_WIDTH * scaleX).roundToInt(),
                    (DesktopMainWindowGeometry.MIN_HEIGHT * scaleY).roundToInt(),
                )
                composeWindow.size = Dimension(
                    (openingSize.width.value * scaleX).roundToInt(),
                    (openingSize.height.value * scaleY).roundToInt(),
                )
            }
        }
        // Apply app-wide UI scale inside Compose, preserving native window frame sizing
        // and the monitor/accessibility font scale. Flyouts share the same density.
        val uiScalePercent by DesktopUiScaleSettings.percent.collectAsState()
        val systemDensity = LocalDensity.current
        val uiDensity = remember(systemDensity, uiScalePercent) {
            Density(systemDensity.density * (uiScalePercent / 100f), systemDensity.fontScale)
        }
        // Only lyrics can opt out of the focus-based frame limiter; when the main window is
        // minimized or hidden, keep the frame loop suspended regardless of the preference.
        val animateLyricsWhileUnfocused by DesktopAppearanceSettings.animateLyricsWhileUnfocused.collectAsState()
        val lyricsWindowFocused = LocalWindowInfo.current.isWindowFocused
        val lyricsAnimationAllowed = shouldAnimateDesktopLyrics(
            windowVisible = visible,
            windowMinimized = state.isMinimized,
            windowFocused = lyricsWindowFocused,
            animateWhenUnfocused = animateLyricsWhileUnfocused,
        )
        CompositionLocalProvider(
            LocalDensity provides uiDensity,
            LocalLyricAnimationAllowed provides lyricsAnimationAllowed,
        ) {
            ProvideDesktopPresentationLifecycle(visible && !state.isMinimized) {
                DesktopFlyoutHost {
                    BitChordDesktopApp(welcomeLaunch, pipHost, playbackService, startup)
                }
            }
        }
    }
}
