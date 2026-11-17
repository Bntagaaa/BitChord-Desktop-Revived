package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.ui.player.MusicVideoQualityUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.playUri
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface
import kotlin.math.abs

/**
 * Native Music Video renderer. Audio clock stays owned by BitChord's audio engine.
 * Skiko draws the native mpv video surface; no per-frame ByteArray.copyOf().
 * The FFmpeg surface activates only when GPU startup/playback fails.
 */
@Composable
internal fun DesktopGpuMusicVideoSurface(
    id: String,
    fit: Boolean,
    onRendered: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val player = remember(id) { DesktopGpuVideoBackend.create(scope.coroutineContext) }
    if (player == null) {
        // create() marks the global fallback; the wrapper will remount FFmpeg.
        return
    }
    val status by player.state.collectAsState()
    val held by DesktopMusicVideo.held.collectAsState()
    LaunchedEffect(status.mediaStatus) {
        onRendered(status.mediaStatus == MediaStatus.Ready)
    }
    DisposableEffect(player) {
        onDispose {
            onRendered(false)
            player.close()
        }
    }
    LaunchedEffect(player, id, held) {
        try {
            val stream = InnerTubeXResolver.extractVideo(id, held ?: 1080)
                ?: error("No GPU-playable video stream")
            DesktopGpuVideoBackend.configureHeaders(player, stream.headers)
            val handle = player.impl as MPVHandle
            handle.setPropertyString("aid", "no")
            handle.setPropertyBoolean("mute", true)
            handle.setPropertyString("loop-file", "no")
            DesktopMusicVideo.aspect.value = stream.aspect.takeIf { it > 0 } ?: 16f / 9f
            DesktopMusicVideo.quality.value = MusicVideoQualityUi(
                stream.heights, held, stream.height,
            )
            player.playUri(
                stream.url,
                playWhenReady = false,
                startPositionMillis = DesktopMusicVideo.clock.value.positionMs.coerceAtLeast(0L),
            )
            // Correct large drift only, rather than seeking dozens of times per second.
            // The audio engine remains authoritative for play/pause and position.
            var playing = false
            var lastSeekGeneration = DesktopMusicVideo.clock.value.seeks
            var lastCorrectionAt = 0L
            while (isActive) {
                val audio = DesktopMusicVideo.clock.value
                if (audio.song?.videoId != id) break
                if (!audio.isPlaying || audio.awaitingAudio) {
                    if (playing) {
                        player.pause()
                        playing = false
                    }
                    delay(120)
                    continue
                }
                val target = videoClockPosition(audio, DesktopMusicVideo.speed)
                val now = System.nanoTime()
                val requestedSeek = lastSeekGeneration != audio.seeks
                if (requestedSeek ||
                    (abs(player.currentPositionMillis.value - target) > 850 &&
                        now - lastCorrectionAt > 1_000_000_000L)) {
                    player.seekTo(target.coerceAtLeast(0L))
                    lastCorrectionAt = now
                    lastSeekGeneration = audio.seeks
                }
                if (!playing) {
                    player.play()
                    playing = true
                }
                delay(150)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DesktopGpuVideoBackend.fallback(failure)
        }
    }
    // MediaMP 0.3.2 uses native surfaces compatible with Compose/Skiko.
    // Surface aspect/fit follows its bounds, provided by the host overlay.
    MpvMediampPlayerSurface(player, modifier)
}
