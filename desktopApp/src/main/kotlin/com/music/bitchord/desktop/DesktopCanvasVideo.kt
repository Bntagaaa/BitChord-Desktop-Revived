package com.music.bitchord.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.ui.player.CanvasContentMode
import com.music.bitchord.ui.player.CanvasVideoSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import org.jetbrains.skia.Rect

/**
 * The desktop's decoder behind the shared player's Canvas clip.
 *
 * The phone plays the clip in a silent ExoPlayer on a TextureView; here FFmpeg
 * decodes it into frames that Compose draws. Everything the shared player asks
 * of the clip is answered the same way as on the phone: nothing until a first
 * frame, then a 320ms fade reported through `onCoverChanged`, the aspect once
 * known, frames for the backdrop to re-tint off, the portrait fit, the bottom
 * dissolve, and a pause that keeps the last frame on screen.
 */
internal data class AutoMixCanvasFrame(val generation: Long, val image: androidx.compose.ui.graphics.ImageBitmap)

@Composable
internal fun DesktopCanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) {
    val canvas = spec.canvas
    val source = MotionVideoSource(canvas.url, canvas.fallbackUrl, DesktopCanvasDecoder.MAX_EDGE,
        MotionVideoSource.Clock.NowPlaying, spec.playbackIdentity ?: canvas.title)
    val presentation = rememberMotionPresentation(source, spec.isPlaying && !spec.pausedForTransition)
    val aspect by remember(presentation) { derivedStateOf { presentation.latest?.size?.let { it.width / it.height } } }
    val rendered = aspect != null
    val submitted = remember(presentation) { mutableStateOf(false) }
    val reportAspect by rememberUpdatedState(spec.onAspectRatioChanged)
    val reportRendered by rememberUpdatedState(spec.onRenderedChanged)
    val reportFrame by rememberUpdatedState(spec.onFrameCaptured)
    val reportCover by rememberUpdatedState(spec.onCoverChanged)
    val presentationAlpha by rememberUpdatedState(spec.presentationAlpha)

    LaunchedEffect(aspect) { aspect?.let(reportAspect) }

    val displayingFrame = rendered && submitted.value
    LaunchedEffect(displayingFrame) { reportRendered(displayingFrame) }

    val mix = rememberDesktopAutoMixPair(mini = false)
    LaunchedEffect(presentation, displayingFrame, mix?.generation, spec.pausedForTransition) {
        val active = mix ?: return@LaunchedEffect
        if (!displayingFrame || spec.pausedForTransition || active.outgoing.id != spec.playbackIdentity) {
            return@LaunchedEffect
        }
        // Take one bounded snapshot from the existing decoder before the player changes clips.
        val image = presentation.sample(384) ?: return@LaunchedEffect
        if (DesktopPlayerHost.autoMixVisual.value?.pair?.generation == active.generation) {
            DesktopPlayerHost.autoMixCanvasFrame.value = AutoMixCanvasFrame(active.generation, image)
        }
    }

    // Sample once for each requested backdrop update. A paused or stalled clip
    // keeps its image and should not allocate a new snapshot of the same frame.
    LaunchedEffect(presentation, displayingFrame, spec.refreshFrameEveryMs, spec.frameCapturePx) {
        if (!displayingFrame || spec.frameCapturePx <= 0) return@LaunchedEffect
        var sampled = presentation.latest?.sequence
        presentation.sample(spec.frameCapturePx)?.let(reportFrame)
        val interval = spec.refreshFrameEveryMs ?: return@LaunchedEffect
        while (isActive) {
            delay(interval)
            sampled = snapshotFlow { presentation.latest?.sequence }.first { it != null && it != sampled }
            presentation.sample(spec.frameCapturePx)?.let(reportFrame)
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (rendered) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "canvasAlpha",
    )
    LaunchedEffect(Unit) {
        snapshotFlow { alpha * presentationAlpha() }.collect { reportCover(it) }
    }
    DisposableEffect(Unit) {
        onDispose {
            reportRendered(false)
            reportCover(0f)
        }
    }

    // Removing the draw node also releases its offscreen layer when the last frame is gone.
    if (!rendered) return
    val bottomFade = spec.bottomFade
    val bottomFadeEndPx = spec.bottomFadeEndPx
    Canvas(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha * presentationAlpha()
            // Offscreen so the bottom dissolve masks the clip, not what is behind it.
            if (bottomFade > 0.001f) compositingStrategy = CompositingStrategy.Offscreen
        },
    ) {
        val clipAspect = presentation.latest?.size?.let { it.width / it.height } ?: return@Canvas
        val viewAspect = size.width / size.height
        val fit = spec.contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect < 1f
        val (drawWidth, drawHeight) = when {
            fit && clipAspect > viewAspect -> size.width to size.width / clipAspect
            fit -> size.height * clipAspect to size.height
            clipAspect > viewAspect -> size.height * clipAspect to size.height
            else -> size.width to size.width / clipAspect
        }
        val left = (size.width - drawWidth) / 2f
        val top = if (fit && spec.alignPortraitTop) 0f else (size.height - drawHeight) / 2f
        presentation.withFrame { image ->
            val drawn = with(image) {
                drawFrame(Rect.makeXYWH(left.roundToInt().toFloat(), top.roundToInt().toFloat(),
                    drawWidth.roundToInt().toFloat(), drawHeight.roundToInt().toFloat()))
            }
            if (drawn && !submitted.value) submitted.value = true
        }
        if (bottomFade > 0.001f) {
            val endY = bottomFadeEndPx?.coerceIn(0f, size.height) ?: size.height
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    1f to Color.Transparent,
                    startY = endY * (1f - bottomFade.coerceAtMost(1f)),
                    endY = endY,
                ),
                topLeft = Offset.Zero,
                size = size,
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

/** The desktop's clip, in the shared player's terms. */
internal fun DesktopCanvasArtwork.toShared(): CanvasArtwork = CanvasArtwork(
    url = url,
    fallbackUrl = fallbackUrl,
    title = title,
    artist = artist,
    album = album,
    source = if (source == DesktopCanvasSource.SPOTIFY) CanvasSource.SPOTIFY else CanvasSource.OTHER,
)
