package com.music.bitchord.desktop

import androidx.compose.foundation.Image
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

/** One silent video deck for all visible surfaces; the existing audio engine owns the clock. */
internal object DesktopMusicVideo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val users = MutableStateFlow<Map<String, Int>>(emptyMap())
    val held = MutableStateFlow<Int?>(null)
    val quality = MutableStateFlow(MusicVideoQualityUi())
    val aspect = MutableStateFlow(16f / 9f)
    val frame = MutableStateFlow<Pair<String, VideoFrame>?>(null)

    @Synchronized fun attach(id: String) { users.value = users.value + (id to ((users.value[id] ?: 0) + 1)) }
    @Synchronized fun detach(id: String) {
        val count = (users.value[id] ?: return) - 1
        users.value = if (count > 0) users.value + (id to count) else users.value - id
    }

    init {
        scope.launch {
            combine(users.map { it.keys.lastOrNull() }.distinctUntilChanged(), held) { id, height -> id to height }
                .collectLatest { (id, height) ->
                    frame.value = null
                    quality.value = MusicVideoQualityUi(held = height)
                    if (id == null) return@collectLatest
                    val decoder = DesktopCanvasDecoder(maxEdge = 8192, loopAtEnd = false)
                    try {
                        val stream = InnerTubeXResolver.extractVideo(id, height ?: 1080)
                            ?: error("No playable video stream")
                        decoder.open(stream.url, stream.headers).getOrThrow()
                        aspect.value = stream.aspect.takeIf { it > 0 } ?: 16f / 9f
                        quality.value = MusicVideoQualityUi(stream.heights, height, stream.height)
                        val pixels = ByteArray(decoder.width * decoder.height * 4)
                        var lastSeek = -1
                        var lastTargetMs: Long? = null
                        var reachedEnd = false
                        while (currentCoroutineContext().isActive) {
                            val audio = DesktopPlayerHost.videoClock.value
                            if (audio.song?.videoId != id || audio.awaitingAudio) { delay(30); continue }
                            if (!audio.isPlaying && frame.value != null && lastSeek == audio.seeks) { delay(30); continue }
                            val target = videoClockPosition(audio, DesktopPlayerHost.videoSpeed).coerceAtLeast(0L)
                            val previousTarget = lastTargetMs
                            // A rewind or seek jumps the *audio* clock. Compare clock samples,
                            // not video PTS: an FFmpeg seek lands on an earlier keyframe, and
                            // repeatedly comparing that frame to target would seek forever.
                            val clockJump = previousTarget != null &&
                                (target < previousTarget - 750 || target > previousTarget + 2_000)
                            lastTargetMs = target
                            if (lastSeek != audio.seeks || clockJump) {
                                if (!decoder.seekTo(target)) { delay(100); continue }
                                lastSeek = audio.seeks
                                reachedEnd = false
                            }
                            // Keep the deck alive at EOF. Repeat-one rewinds the audio clock
                            // without changing the video id, so collectLatest never restarts it.
                            if (reachedEnd) { delay(100); continue }
                            if (!decoder.nextFrame(pixels)) {
                                reachedEnd = true
                                delay(100)
                                continue
                            }
                            val frameTime = decoder.frameTimeMs
                            if (frameTime + 80 < target) continue
                            val wait = ((frameTime - target) / DesktopPlayerHost.videoSpeed.coerceAtLeast(.1f)).toLong()
                            if (wait > 0) delay(wait.coerceAtMost(1000))
                            frame.value = id to VideoFrame(decoder.width, decoder.height, pixels.copyOf())
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { DesktopPlayerHost.showMessage("Video unavailable; audio continues") }
                    finally { decoder.close() }
                }
        }
    }
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
    val image = current?.takeIf { it.first == id }?.second
    val report by rememberUpdatedState(onRendered)
    DisposableEffect(id) {
        DesktopMusicVideo.attach(id)
        onDispose { DesktopMusicVideo.detach(id); report(false) }
    }
    LaunchedEffect(image != null) { report(image != null) }
    image?.let { Image(painter = it, contentDescription = null, modifier = modifier,
        contentScale = if (fit) ContentScale.Fit else ContentScale.Crop) }
}
