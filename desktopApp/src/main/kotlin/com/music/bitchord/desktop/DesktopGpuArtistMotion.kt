package com.music.bitchord.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    var firstFrame by remember(player) { mutableStateOf(false) }
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
            // A successful open is NOT proof that Skiko received an actual
            // video frame. Keep the still image below this layer visible until
            // libmpv reports a decoded frame; detect stuck-black sessions.
            val start = System.nanoTime()
            while (isActive && !firstFrame) {
                if (mpvHasDecodedFrame(handle)) {
                    firstFrame = true
                    break
                }
                if (System.nanoTime() - start > 9_000_000_000L) {
                    DesktopGpuVideoBackend.fallback(
                        IllegalStateException("Artist Motion: no decoded frame within 9s"),
                    )
                    break
                }
                delay(100)
            }
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
    DesktopGpuVideoSurface(
        player = player,
        fit = false, // Artist hero uses ContentScale.Crop, not letterbox Fit.
        modifier = modifier.alpha(if (firstFrame) 1f else 0f),
        provisionalAspect = 2.119f,
    )
}
