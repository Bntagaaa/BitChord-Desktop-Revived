package com.music.bitchord.reworked

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.ui.player.CanvasVideoSpec
import com.music.bitchord.ui.player.MusicVideoQualityUi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import kotlin.math.abs

/** Fresh platform decoder for the mobile player's CanvasVideoSpec contract. No second audio output. */
@Composable
internal fun MobileCanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) {
    val latest by rememberUpdatedState(spec)
    var url by remember(spec.canvas.url) { mutableStateOf(spec.canvas.url) }
    var rendered by remember(url) { mutableStateOf(false) }
    var lastFrame by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var lastPosition by remember(url) { mutableStateOf(0L) }
    var capturedAt by remember(url) { mutableStateOf<Long?>(null) }
    var resumed by remember(url) { mutableStateOf(false) }
    var mayPlay by remember { mutableStateOf(false) }
    val fade = remember(url) { Animatable(if (spec.revealInstantly) 1f else 0f) }
    LaunchedEffect(spec.isPlaying, spec.pausedForTransition, spec.resumeDelayMs) {
        mayPlay = false; resumed = false
        if (spec.isPlaying && !spec.pausedForTransition) { delay(spec.resumeDelayMs.coerceAtLeast(0)); mayPlay = true }
    }
    LaunchedEffect(rendered) { if (rendered) { if (latest.revealInstantly) fade.snapTo(1f) else fade.animateTo(1f, tween(280)) } }
    LaunchedEffect(fade.value) { latest.onCoverChanged(if (rendered) fade.value else 0f) }
    LaunchedEffect(spec.captureStill, spec.pausedForTransition) { if (spec.captureStill && spec.pausedForTransition) spec.onStillCaptured(lastFrame, lastPosition) }
    DesktopVideoSurface(
        url = url,
        headers = emptyMap(),
        looping = true,
        shouldPlay = { mayPlay },
        position = null,
        startMs = spec.startPositionMs,
        fit = spec.contentMode != com.music.bitchord.ui.player.CanvasContentMode.CROP && (lastFrame?.let { it.width < it.height } ?: false),
        alignment = if (spec.alignPortraitTop) Alignment.TopCenter else Alignment.Center,
        onFrame = { bitmap, at ->
            lastFrame = bitmap; lastPosition = at; rendered = true
            latest.onRenderedChanged(true)
            latest.onAspectRatioChanged(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
            val now = System.nanoTime() / 1_000_000
            if (latest.frameCapturePx > 0 && (capturedAt == null || latest.refreshFrameEveryMs?.let { now - capturedAt!! >= it.coerceAtLeast(50) } == true)) {
                latest.onFrameCaptured(scaledFrame(bitmap, latest.frameCapturePx)); capturedAt = now
            }
            if (mayPlay && !resumed && at > latest.startPositionMs) { resumed = true; latest.onResumed() }
        },
        onStopped = { latest.onRenderedChanged(false) },
        onError = { if (url != spec.canvas.fallbackUrl && spec.canvas.fallbackUrl != null) url = spec.canvas.fallbackUrl!! },
        modifier = modifier.graphicsLayer { alpha = fade.value * latest.presentationAlpha(); compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (latest.bottomFade > 0f) {
                    val end = (latest.bottomFadeEndPx ?: size.height).coerceIn(0f, size.height)
                    drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = end * (1f-latest.bottomFade.coerceIn(0f,1f)), endY = end), blendMode = BlendMode.DstIn)
                }
            },
    )
}

@Composable
internal fun MobileMusicVideo(
    videoId: String,
    player: DesktopPlayback,
    quality: MutableStateFlow<MusicVideoQualityUi>,
    aspect: MutableStateFlow<Float>,
    fit: Boolean,
    onRenderedChanged: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val choice by quality.collectAsState()
    var stream by remember(videoId) { mutableStateOf<InnerTubeXResolver.VideoStream?>(null) }
    var failure by remember(videoId) { mutableStateOf<String?>(null) }
    var attempt by remember(videoId) { mutableIntStateOf(0) }
    val rendered by rememberUpdatedState(onRenderedChanged)
    LaunchedEffect(videoId, choice.held, attempt) {
        stream = null; failure = null; rendered(false)
        try {
            val found = InnerTubeXResolver.extractVideo(videoId, choice.held ?: 1080)
            stream = found
            if (found != null) {
                aspect.value = found.aspect.takeIf { it > 0f } ?: 16f / 9f
                quality.value = MusicVideoQualityUi(found.heights, choice.held, found.height)
            } else failure = "The music video is unavailable"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = "Could not load the music video"; rendered(false) }
    }
    Box(modifier) {
        key(videoId, attempt, choice.held) { stream?.let { video ->
            DesktopVideoSurface(video.url, video.headers, looping = false,
                shouldPlay = { player.state.value.isPlaying }, position = { player.state.value.positionMs }, fit = fit,
                onFrame = { _, _ -> rendered(true) }, onStopped = { rendered(false) },
                onError = { failure = "Could not play the video picture. Audio playback is continuing." }, modifier = Modifier.fillMaxSize())
        } }
        failure?.let { message ->
            Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
                    TextButton(onClick = { attempt++ }) { Text("Retry") }
                }
            }
        }
    }
}

/**
 * Only pixels cross into Compose. FFmpeg resources live and close on their own coroutine;
 * replacing a track cancels that coroutine before a new video can publish its frames.
 */
@Composable
internal fun DesktopVideoSurface(
    url: String,
    headers: Map<String, String> = emptyMap(),
    looping: Boolean = true,
    shouldPlay: () -> Boolean = { true },
    position: (() -> Long)? = null,
    startMs: Long = 0,
    fit: Boolean = false,
    alignment: Alignment = Alignment.Center,
    onFrame: (ImageBitmap, Long) -> Unit = { _, _ -> },
    onStopped: () -> Unit = {},
    onError: (Throwable) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    val currentPlay by rememberUpdatedState(shouldPlay)
    val currentPosition by rememberUpdatedState(position)
    val frameCallback by rememberUpdatedState(onFrame)
    val stopped by rememberUpdatedState(onStopped)
    val reportError by rememberUpdatedState(onError)
    LaunchedEffect(url, headers) {
        try {
            withContext(Dispatchers.IO) {
                val decoder = FFmpegFrameGrabber(url)
                val converter = Java2DFrameConverter()
                try {
                    decoder.setOption("rw_timeout", "8000000")
                    if (headers.isNotEmpty()) decoder.setOption("headers", headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
                    decoder.imageWidth = 960
                    decoder.start()
                    if (startMs > 0) decoder.timestamp = startMs * 1000
                    var first = true
                    var lastMediaTime = 0L
                    var clock = System.nanoTime()
                    while (isActive) {
                        if (!first && !currentPlay()) { delay(35); clock = System.nanoTime(); continue }
                        val target = currentPosition?.invoke()
                        if (target != null && (first || abs(target - lastMediaTime) > 300)) decoder.timestamp = target.coerceAtLeast(0) * 1000
                        val frame = decoder.grabImage()
                        if (frame == null) {
                            if (!looping) break
                            decoder.timestamp = 0; lastMediaTime = 0; clock = System.nanoTime(); continue
                        }
                        val at = decoder.timestamp / 1000
                        val image = converter.convert(frame) ?: continue
                        val nextBitmap = image.toComposeImageBitmap()
                        ensureActive()
                        withContext(Dispatchers.Main) { bitmap = nextBitmap; frameCallback(nextBitmap, at) }
                        first = false
                        if (target == null) {
                            val frameMs = (1000.0 / decoder.frameRate.coerceAtLeast(1.0)).toLong().coerceIn(8,100)
                            val spent = (System.nanoTime() - clock) / 1_000_000
                            delay((frameMs - spent).coerceAtLeast(1))
                            clock = System.nanoTime()
                        } else {
                            val wait = at - currentPosition!!.invoke()
                            if (wait > 0) delay(wait.coerceAtMost(50))
                        }
                        lastMediaTime = at
                    }
                } finally { runCatching { decoder.close() }; converter.close() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { bitmap = null; reportError(error) }
        finally { stopped() }
    }
    bitmap?.let { Image(it, null, modifier, alignment = alignment, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop) }
}

private fun scaledFrame(bitmap: ImageBitmap, maxSide: Int): ImageBitmap {
    val ratio = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
    if (ratio >= 1f) return bitmap
    val size = IntSize((bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1))
    return ImageBitmap(size.width, size.height).also { Canvas(it).drawImageRect(bitmap, dstSize = size, paint = Paint()) }
}
