package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import bitchord.desktopapp.generated.resources.Res
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlin.system.exitProcess

/**
 * A composition owned by application{}, OUTSIDE the main Window.
 *
 * Tray, PiP and platform media controls remain independent of whether the main
 * Window is visible. Its Compose UI now remains mounted while hidden; process-owned
 * callbacks must still not depend on a specific page or window lifetime.
 */
@Composable
internal fun DesktopBackgroundPlaybackHost(
    service: DesktopPlaybackService,
    pipHost: DesktopPipHost,
) {
    val playback by service.engine.state.collectAsState()
    val trayEnabled by DesktopWindowVisibility.trayEnabled.collectAsState()
    val icon by DesktopAppIconSettings.selected.collectAsState()
    val shuffle by service.shuffleState
    val repeatMode by service.repeatState
    val rate by service.rateState

    val tray = remember(service) {
        DesktopStatusNotifierController(
            onActivate = DesktopWindowVisibility::show,
            onPlayPause = service::togglePlayPause,
        )
    }
    DisposableEffect(tray, trayEnabled) {
        if (trayEnabled) tray.start()
        onDispose { tray.stop() }
    }
    LaunchedEffect(tray, icon, trayEnabled) {
        if (!trayEnabled) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { Res.readBytes(icon.svgPath) }.onSuccess(tray::setIcon)
        }
    }

    val mpris = remember(service) {
        DesktopMprisController(
            onRaise = DesktopWindowVisibility::raise,
            onPlay = service::play,
            onPause = service::pause,
            onPlayPause = service::togglePlayPause,
            onNext = service::next,
            onPrevious = service::previous,
            onShuffleChanged = service::shuffle,
            onLoopStatusChanged = service::loopStatus,
            onRateChanged = service::rate,
            onVolumeChanged = service::volume,
            onSeek = service::seek,
        )
    }
    DisposableEffect(mpris) {
        mpris.start()
        onDispose { mpris.stop() }
    }

    // Preserve the player's clock, metadata and lyrics while the main UI is hidden.
    LaunchedEffect(service) {
        service.engine.state.collect { state ->
            service.pipPlayback.value = state
            service.position.report(state.positionMs, state.positionSampledAtNanos)
            service.position.seeks = state.seeks
            service.position.advancing = !state.awaitingAudio
            DesktopPlayerHost.videoClock.value = state
        }
    }
    LaunchedEffect(service) {
        service.lyricsSession.follow(
            snapshotFlow {
                val settings = service.lyricsSettings.value
                val song = service.selectedSong.value
                val state = service.pipPlayback.value
                settings.copy(
                    song = song,
                    durationMs = state.durationMs.takeIf { state.song?.videoId == song?.videoId } ?: 0L,
                    videoBeta = DesktopVideoBeta.enabled.value,
                )
            },
            currentTrack = { service.selectedSong.value?.videoId },
            onLoaded = { id, result -> service.engine.setLoopLyrics(id, result.lines) },
        )
    }
    LaunchedEffect(service) {
        while (isActive) {
            delay(1_000L)
            if (DesktopSleepTimer.isExpired()) {
                DesktopSleepTimer.cancel()
                service.engine.pause()
            }
        }
    }
    LaunchedEffect(
        playback.song?.videoId, playback.isPlaying, playback.durationMs,
        playback.volume, playback.error, shuffle, repeatMode, rate,
    ) {
        mpris.update(
            playback = service.engine.state.value,
            shuffle = shuffle,
            loopStatus = when (repeatMode) {
                "ONE" -> "Track"
                "ALL" -> "Playlist"
                else -> "None"
            },
            rate = rate.toDouble(),
        )
    }
    LaunchedEffect(playback.song?.videoId, playback.isPlaying, playback.durationMs) {
        val state = service.engine.state.value
        DesktopScrobbling.onPlaybackStateChanged(state)
        DesktopWindowsMedia.publish(state)
    }
    LaunchedEffect(playback.song?.videoId, playback.isPlaying) {
        val title = service.selectedSong.value?.let { "${it.title} — ${it.artist}" }
            ?: playback.song?.let { "${it.title} — ${it.artist}" }
        tray.update(title, playback.isPlaying)
        DesktopTrayMenu.publish(title, playback.isPlaying)
    }

    DisposableEffect(service, pipHost) {
        val session = DesktopPipSession(
            presentation = {
                val state = service.pipPlayback.value
                val lyrics = service.lyricsSession.state
                val song = service.selectedSong.value ?: state.song
                val queue = service.liveQueue.value
                DesktopPipState(
                    song = song, playing = state.isPlaying, loading = state.isLoading,
                    buffering = state.awaitingAudio, durationMs = state.durationMs,
                    previous = queue.hasPrevious, next = song != null,
                    controlsLocked = DesktopListenTogether.state.value.controlsLocked,
                    lyricsTrack = lyrics.trackId, lyrics = lyrics.result?.lines,
                    lyricsLoading = lyrics.loading, lyricsError = lyrics.error,
                )
            },
            position = service.position,
            onPlayPause = service::togglePlayPause,
            onPrevious = service::previous,
            onNext = service::next,
            onSeek = service::seek,
        )
        pipHost.session = session
        DesktopWindowVisibility.onMainShown = pipHost.controller::mainShown
        DesktopWindowVisibility.onClosedToTray = {
            if (DesktopWindowVisibility.keepRunningWhenClosed &&
                DesktopPipSettings.state.value.onClose) {
                pipHost.controller.show()
            }
        }
        DesktopTrayMenu.bind(
            onPlayPause = service::togglePlayPause,
            onNext = service::next,
            onPrevious = service::previous,
            onOpenPlayer = DesktopWindowVisibility::show,
            onOpenSettings = DesktopWindowVisibility::showSettings,
            onQuit = {
                // Quit is different from closing to tray: explicitly release the audio thread.
                DesktopApplicationResources.owner.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    DesktopPlaybackWrites.flush()
                    service.close()
                    DesktopWindowsMedia.stop()
                    exitProcess(0)
                }
            },
        )
        DesktopWindowsMedia.start(
            DesktopWindowsMedia.Controller(
                onPlay = service::play,
                onPause = service::pause,
                onNext = service::next,
                onPrevious = service::previous,
                onStop = service::pause,
            ),
        )
        DesktopStartupDiagnostics.once(DesktopStartupDiagnostics.Milestone.BACKGROUND_STARTED)
        onDispose {
            if (pipHost.session === session) pipHost.session = null
            DesktopWindowVisibility.onMainShown = {}
            DesktopWindowVisibility.onClosedToTray = {}
            pipHost.controller.dispose()
            DesktopWindowsMedia.stop()
        }
    }
}
