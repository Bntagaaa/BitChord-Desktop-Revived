package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.ui.player.MusicVideoQualityUi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.skia.*
import kotlin.math.abs

/** The view never owns this decoder; only the currently playing video and quality do. */
internal interface DesktopVideoDecoder {
    val width: Int
    val height: Int
    val frameTimeMs: Long
    fun open(stream: InnerTubeXResolver.VideoStream)
    fun seekTo(millis: Long): Boolean
    fun nextFrame(pixels: ByteArray): Boolean
    fun close()
}

/** Injectable session used by both windowed and fullscreen views, including while minimized. */
internal class DesktopVideoSession(
    scope: CoroutineScope,
    val clock: StateFlow<DesktopPlaybackState>,
    private val speed: () -> Float,
    private val resolve: suspend (String, Int) -> InnerTubeXResolver.VideoStream?,
    private val decoderFactory: () -> DesktopVideoDecoder,
    private val onError: (Exception) -> Unit = {},
    enabled: StateFlow<Boolean> = MutableStateFlow(true),
) {
    private val users = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val retries = MutableStateFlow(0)
    val held = MutableStateFlow<Int?>(null)
    val quality = MutableStateFlow(MusicVideoQualityUi())
    val aspect = MutableStateFlow(16f / 9f)
    val frame = MutableStateFlow<Pair<String, VideoFrame>?>(null)
    val failed = MutableStateFlow(false)

    @Synchronized fun attach(id: String) { users.value = users.value + (id to ((users.value[id] ?: 0) + 1)) }
    @Synchronized fun detach(id: String) {
        val count = (users.value[id] ?: return) - 1
        users.value = if (count > 0) users.value + (id to count) else users.value - id
    }
    fun retry() { retries.value++ }

    private val job = scope.launch {
        // Playback identity survives disposal/recreation of a view. Never key this collector on
        // surface count: fullscreen, minimize and restore must not resolve or open the URL again.
        combine(clock.combine(enabled) { audio, beta -> audio.song?.takeIf { beta && it.isVideo }?.videoId }.distinctUntilChanged(),
            held, retries) { id, height, retry -> Triple(id, height, retry) }
            .collectLatest { (id, height, _) ->
                if (frame.value?.first != id) frame.value = null
                failed.value = false
                quality.value = MusicVideoQualityUi(held = height)
                if (id == null) { frame.value = null; return@collectLatest }
                val decoder = decoderFactory()
                try {
                    val stream = resolve(id, height ?: 1080) ?: error("No playable video stream")
                    ensureActive()
                    decoder.open(stream)
                    ensureActive()
                    aspect.value = stream.aspect.takeIf { it > 0 } ?: 16f / 9f
                    quality.value = MusicVideoQualityUi(stream.heights, height, stream.height)
                    val pixels = ByteArray(decoder.width * decoder.height * 4)
                    var lastSeek = -1
                    var lastTargetMs: Long? = null
                    var producedFrame = false
                    while (currentCoroutineContext().isActive) {
                        val audio = clock.value
                        if (audio.song?.videoId != id || audio.awaitingAudio) { delay(30); continue }
                        // Decode one frame to warm the session, then retain the decoder and last
                        // frame while hidden. Restoring only seeks to the live clock if needed.
                        if (producedFrame && users.value[id] == null) {
                            users.first { (it[id] ?: 0) > 0 }
                            continue
                        }
                        if (!audio.isPlaying && producedFrame && lastSeek == audio.seeks) {
                            clock.first { it.isPlaying || it.seeks != lastSeek }
                            continue
                        }
                        val target = videoClockPosition(audio, speed())
                        // Compare successive audio clock samples, not video frame PTS:
                        // FFmpeg may seek to an earlier keyframe and otherwise seek forever.
                        val oldTarget = lastTargetMs
                        val jumped = oldTarget != null &&
                            (target < oldTarget - 750 || target > oldTarget + 2_000)
                        lastTargetMs = target
                        if (lastSeek != audio.seeks || jumped) {
                            check(decoder.seekTo(target)) { "Video seek failed" }
                            lastSeek = audio.seeks
                        }
                        if (!decoder.nextFrame(pixels)) {
                            check(producedFrame) { "Video has no decodable frames" }
                            delay(60)
                            continue
                        }
                        ensureActive()
                        val frameTime = decoder.frameTimeMs
                        if (frameTime + 80 < target) continue
                        val wait = ((frameTime - target) / speed().coerceAtLeast(.1f)).toLong()
                        if (wait > 0) delay(wait.coerceAtMost(1000))
                        val latest = clock.value
                        if (latest.song?.videoId != id || latest.seeks != audio.seeks ||
                            (audio.isPlaying && !latest.isPlaying) || latest.awaitingAudio) continue
                        frame.value = id to VideoFrame(decoder.width, decoder.height, pixels.copyOf())
                        producedFrame = true
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    frame.value = null
                    failed.value = true
                    onError(error)
                } finally { decoder.close() }
            }
    }
    fun close() { job.cancel(); frame.value = null }
}

internal object DesktopMusicVideo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val clock = MutableStateFlow(DesktopPlaybackState())
    @Volatile var speed = 1f
    private val session = DesktopVideoSession(scope, clock, { speed },
        resolve = InnerTubeXResolver::extractVideo,
        decoderFactory = {
            object : DesktopVideoDecoder {
                private val decoder = DesktopCanvasDecoder(maxEdge = 8192, loopAtEnd = false)
                override val width get() = decoder.width
                override val height get() = decoder.height
                override val frameTimeMs get() = decoder.frameTimeMs
                override fun open(stream: InnerTubeXResolver.VideoStream) { decoder.open(stream.url, stream.headers).getOrThrow() }
                override fun seekTo(millis: Long) = decoder.seekTo(millis)
                override fun nextFrame(pixels: ByteArray) = decoder.nextFrame(pixels)
                override fun close() = decoder.close()
            }
        }, onError = { DesktopTrackLog.log("music video: ${it.javaClass.simpleName}") }, enabled = DesktopVideoBeta.enabled)
    private const val QUALITY_KEY = "music_video_quality"
    init {
        session.held.value = DesktopPersistence().int(QUALITY_KEY, 0).takeIf { it in 144..4320 }
    }
    fun setQuality(height: Int?) {
        val quality = height?.takeIf { it in 144..4320 }
        session.held.value = quality
        scope.launch { DesktopPersistence().saveInt(QUALITY_KEY, quality ?: 0) }
    }
    val held get() = session.held
    val quality get() = session.quality
    val aspect get() = session.aspect
    val frame get() = session.frame
    val failed get() = session.failed
    fun attach(id: String) = session.attach(id)
    fun detach(id: String) = session.detach(id)
    fun retry() = session.retry()
    fun stop() { clock.value = DesktopPlaybackState() }
}

internal fun videoClockPosition(state: DesktopPlaybackState, speed: Float, now: Long = System.nanoTime()): Long {
    if (!state.isPlaying || state.awaitingAudio || state.positionSampledAtNanos == 0L) return state.positionMs
    return state.positionMs + ((now - state.positionSampledAtNanos).coerceAtLeast(0L) / 1_000_000.0 * speed).toLong()
}

internal class VideoFrame(val width: Int, val height: Int, private val pixels: ByteArray) : Painter() {
    override val intrinsicSize = Size(width.toFloat(), height.toFloat())
    override fun DrawScope.onDraw() {
        org.jetbrains.skia.Image.makeRaster(ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE), pixels, width * 4).use { image ->
            drawIntoCanvas { it.nativeCanvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()),
                Rect.makeWH(size.width, size.height), SamplingMode.LINEAR, null, true) }
        }
    }
}

@Composable
internal fun DesktopMusicVideoSurface(id: String, fit: Boolean, onRendered: (Boolean) -> Unit, modifier: Modifier) {
    val current by DesktopMusicVideo.frame.collectAsState()
    val failed by DesktopMusicVideo.failed.collectAsState()
    val image = current?.takeIf { it.first == id }?.second
    val report by rememberUpdatedState(onRendered)
    DisposableEffect(id) {
        DesktopMusicVideo.attach(id)
        onDispose { DesktopMusicVideo.detach(id) }
    }
    LaunchedEffect(id, image != null) { report(image != null) }
    if (image != null) Image(painter = image, contentDescription = null, modifier = modifier,
        contentScale = if (fit) ContentScale.Fit else ContentScale.Crop)
    else if (failed) androidx.compose.foundation.layout.Box(modifier.background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            androidx.compose.material3.Text("Video unavailable", color = androidx.compose.ui.graphics.Color.White)
            androidx.compose.material3.TextButton(onClick = DesktopMusicVideo::retry) { androidx.compose.material3.Text("Retry video") }
        }
    }
}
