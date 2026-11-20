package com.music.bitchord.desktop

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface

/**
 * MediaMP 0.5.1 always draws a contained (letterboxed) frame, even when
 * BitChord requests ContentScale.Crop. Scaling the native Skia surface inside
 * a clipped viewport gives us Cover without copying GPU frames to the JVM.
 */
internal fun gpuVideoCoverScale(
    viewportWidth: Float,
    viewportHeight: Float,
    videoAspect: Float,
    fit: Boolean,
): Float {
    if (fit || viewportWidth <= 0f || viewportHeight <= 0f ||
        !videoAspect.isFinite() || videoAspect <= 0f) return 1f
    val viewportAspect = viewportWidth / viewportHeight
    return maxOf(viewportAspect / videoAspect, videoAspect / viewportAspect)
        .coerceAtLeast(1f)
}

/**
 * Evidence that mpv has decoded at least one video frame, not merely entered
 * MediaStatus.Ready (which only means the source was opened). These properties
 * are supplied by libmpv; they do NOT prove that the Skiko compositor painted
 * a frame, so we still keep the legacy fallback on a startup timeout.
 */
internal fun mpvHasDecodedFrame(handle: MPVHandle): Boolean =
    handle.getPropertyLongOrNull("estimated-frame-number") != null ||
        !handle.getPropertyString("video-frame-info/picture-type").isNullOrBlank()

@Composable
internal fun DesktopGpuVideoSurface(
    player: MpvMediampPlayer,
    fit: Boolean,
    modifier: Modifier = Modifier,
    provisionalAspect: Float = 16f / 9f,
) {
    val properties by player.mediaProperties.collectAsState()
    val w = properties?.videoWidth ?: 0
    val h = properties?.videoHeight ?: 0
    val videoAspect = if (w > 0 && h > 0) w.toFloat() / h else provisionalAspect
    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val scale = gpuVideoCoverScale(
            viewportWidth = maxWidth.value,
            viewportHeight = maxHeight.value,
            videoAspect = videoAspect,
            fit = fit,
        )
        MpvMediampPlayerSurface(
            player,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        )
    }
}
