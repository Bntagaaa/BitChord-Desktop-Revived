package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface
import org.openani.mediamp.playUri
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.MPVHandle

/**
 * Artist motion rendered on the GPU. No per-frame JVM ByteArray/Skia ImageBitmap.
 * The old renderer is mounted only when explicitly requested or native playback fails.
 * The video lives behind Compose's gradient, logo and controls.
 */
@Composable
internal fun DesktopGpuArtistMotion(
    url: String,
    fallbackUrl: String?,
    modifier: Modifier = Modifier,
    visible: Boolean,
) {
    val legacy by DesktopGpuVideoBackend.legacy.collectAsState()
    if (legacy) {
        DesktopContinuedArtistMotionView(
            url = url, fallbackUrl = fallbackUrl, modifier = modifier,
            maxEdge = 640, publishBackdrop = false, isPlaying = visible,
        )
        return
    }

    // Compose owns lifecycle, so changing artist, scrolling offscreen or tray hiding
    // will terminate native playback and release its GPU surfaces.
    if (!visible) return
    val scope = rememberCoroutineScope()
    val player = remember(url) { DesktopGpuVideoBackend.create(scope.coroutineContext) }
    if (player == null) {
        DesktopContinuedArtistMotionView(
            url = url, fallbackUrl = fallbackUrl, modifier = modifier,
            maxEdge = 640, publishBackdrop = false, isPlaying = visible,
        )
        return
    }
    val status by player.state.collectAsState()
    LaunchedEffect(status.mediaStatus) {
        (status.mediaStatus as? MediaStatus.Error)?.let { failure ->
            // A decoder can fail after playUri returned successfully. Without
            // observing this, the GPU surface stays black indefinitely.
            DesktopGpuVideoBackend.fallback(failure.error)
        }
    }
    DisposableEffect(player) {
        onDispose { player.close() }
    }
    LaunchedEffect(player, url, fallbackUrl) {
        try {
            val handle = player.impl as MPVHandle
            handle.setPropertyString("loop-file", "inf")
            // The renderer owns no audio output: BitChord's own playback engine is untouched.
            handle.setPropertyString("aid", "no")
            player.playUri(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (fallbackUrl != null && fallbackUrl != url) {
                try {
                    player.playUri(fallbackUrl)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (second: Exception) {
                    DesktopGpuVideoBackend.fallback(second)
                }
            } else {
                DesktopGpuVideoBackend.fallback(failure)
            }
        }
    }
    MpvMediampPlayerSurface(player, modifier)
}
