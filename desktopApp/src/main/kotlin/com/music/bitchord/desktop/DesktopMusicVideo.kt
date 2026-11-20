package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.ui.player.MusicVideoQualityUi
import com.music.bitchord.ui.LocalDesktopPresentationVisible
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The view never owns this decoder; only the currently playing video and quality do. */
internal interface DesktopVideoDecoder {
    val frameTimeMs: Long
    fun open(stream: InnerTubeXResolver.VideoStream)
    fun seekTo(millis: Long): Boolean
    fun nextFrame(accept: (Long) -> Boolean = { true }): VideoFrame?
    fun close()
}

/** One audio-clocked decoder shared by the currently visible surfaces. */
internal class DesktopVideoSession(
    private val scope: CoroutineScope,
    val clock: StateFlow<DesktopPlaybackState>,
    private val speed: () -> Float,
    private val resolve: suspend (String, Int) -> InnerTubeXResolver.VideoStream?,
    private val decoderFactory: (Job?) -> DesktopVideoDecoder,
    private val onError: (Exception) -> Unit = {},
    enabled: StateFlow<Boolean> = MutableStateFlow(true),
) : AutoCloseable {
    private val users = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val liveSurfaces = MutableStateFlow<Set<String>>(emptySet())
    private val pendingRelease = mutableMapOf<String, Job>()
    private var closed = false
    private val retries = MutableStateFlow(0)
    val held = MutableStateFlow<Int?>(null)
    val quality = MutableStateFlow(MusicVideoQualityUi())
    val aspect = MutableStateFlow(16f / 9f)
    val frame = MutableStateFlow<Pair<String, VideoFrame>?>(null)
    val failed = MutableStateFlow(false)

    @Synchronized fun attach(id: String) {
        if (closed) return
        pendingRelease.remove(id)?.cancel()
        users.value = users.value + (id to ((users.value[id] ?: 0) + 1))
        liveSurfaces.value = liveSurfaces.value + id
    }

    @Synchronized fun detach(id: String) {
        val count = (users.value[id] ?: return) - 1
        users.value = if (count > 0) users.value + (id to count) else users.value - id
        if (count == 0) {
            val release = scope.launch(start = CoroutineStart.LAZY) {
                delay(SURFACE_HANDOFF_MS)
                expireSurface(id, currentCoroutineContext().job)
            }
            pendingRelease[id] = release
            release.start()
        }
    }

    @Synchronized private fun expireSurface(id: String, release: Job) {
        if (pendingRelease[id] !== release) return
        pendingRelease.remove(id)
        liveSurfaces.value = liveSurfaces.value - id
    }

    fun retry() { retries.value++ }

    @Synchronized
    private fun publish(next: Pair<String, VideoFrame>?) {
        if (closed) {
            next?.second?.close()
            return
        }
        val old = frame.value
        frame.value = next
        old?.second?.close()
    }

    @Synchronized
    fun withFrame(id: String, draw: (VideoFrame) -> Unit) {
        frame.value?.takeIf { it.first == id }?.second?.let(draw)
    }

    private val job = scope.launch {
        val media = clock.combine(enabled) { audio, beta ->
            audio.song?.takeIf { beta && it.isVideo }?.videoId
        }.distinctUntilChanged()
        val visibleMedia = media.combine(liveSurfaces) { id, active -> id?.takeIf { it in active } }
            .distinctUntilChanged()
        combine(visibleMedia, held, retries) { id, height, retry -> Triple(id, height, retry) }
            .collectLatest { (id, height, _) ->
                publish(null)
                failed.value = false
                quality.value = MusicVideoQualityUi(held = height)
                if (id == null) return@collectLatest
                val decoder = decoderFactory(currentCoroutineContext()[Job])
                try {
                    val stream = resolve(id, height ?: 1080) ?: error("No playable video stream")
                    ensureActive()
                    decoder.open(stream)
                    ensureActive()
                    aspect.value = stream.aspect.takeIf { it > 0 } ?: 16f / 9f
                    quality.value = MusicVideoQualityUi(stream.heights, height, stream.height)
                    var lastSeek = -1
                    var lastTargetMs: Long? = null
                    var producedFrame = false
                    while (currentCoroutineContext().isActive) {
                        val audio = clock.value
                        if (audio.song?.videoId != id || audio.awaitingAudio) { delay(30); continue }
                        if (users.value[id] == null) {
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
                        val context = currentCoroutineContext()
                        val next = decoder.nextFrame { pts ->
                            context.ensureActive()
                            pts + 80 >= target
                        }
                        if (next == null) {
                            check(producedFrame) { "Video has no decodable frames" }
                            delay(60)
                            continue
                        }
                        var submitted = false
                        try {
                            ensureActive()
                            val wait = ((decoder.frameTimeMs - target) / speed().coerceAtLeast(.1f)).toLong()
                            if (wait > 0) delay(wait.coerceAtMost(1000))
                            val latest = clock.value
                            if (latest.song?.videoId != id || latest.seeks != audio.seeks ||
                                (audio.isPlaying && !latest.isPlaying) || latest.awaitingAudio || users.value[id] == null) continue
                            publish(id to next)
                            submitted = true
                            producedFrame = true
                        } finally {
                            if (!submitted) next.close()
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    publish(null)
                    failed.value = true
                    onError(error)
                } finally { decoder.close() }
            }
    }
    @Synchronized
    override fun close() {
        publish(null)
        closed = true
        pendingRelease.values.forEach { it.cancel() }
        pendingRelease.clear()
        liveSurfaces.value = emptySet()
        users.value = emptyMap()
        job.cancel()
    }

    private companion object {
        const val SURFACE_HANDOFF_MS = 350L
    }
}

internal object DesktopMusicVideo {
    private val resources = DesktopApplicationResources.owner
    private val scope = resources.scope
    val clock = MutableStateFlow(DesktopPlaybackState())
    @Volatile var speed = 1f
    private val session = resources.own("music-video") {
        DesktopVideoSession(scope, clock, { speed },
            resolve = InnerTubeXResolver::extractVideo,
            decoderFactory = UnifiedVideoEngine::musicDecoder,
            onError = { DesktopTrackLog.log("music video: ${it.javaClass.simpleName}") },
            enabled = DesktopVideoBeta.enabled,
        )
    }
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
    fun withFrame(id: String, draw: (VideoFrame) -> Unit) = session.withFrame(id, draw)
    fun attach(id: String) = session.attach(id)
    fun detach(id: String) = session.detach(id)
    fun retry() = session.retry()
    fun stop() { clock.value = DesktopPlaybackState() }
}

internal fun videoClockPosition(state: DesktopPlaybackState, speed: Float, now: Long = System.nanoTime()): Long {
    if (!state.isPlaying || state.awaitingAudio || state.positionSampledAtNanos == 0L) return state.positionMs
    return state.positionMs + ((now - state.positionSampledAtNanos).coerceAtLeast(0L) / 1_000_000.0 * speed).toLong()
}

@Composable
internal fun DesktopMusicVideoSurface(id: String, fit: Boolean, onRendered: (Boolean) -> Unit, modifier: Modifier, showFailure: Boolean = true) {
    val current = DesktopMusicVideo.frame.collectAsState()
    val failed by DesktopMusicVideo.failed.collectAsState()
    val visible = LocalDesktopPresentationVisible.current
    val dimensions by remember(id, visible, current) {
        derivedStateOf { current.value?.takeIf { visible && it.first == id }?.second?.intrinsicSize }
    }
    val report by rememberUpdatedState(onRendered)
    val submitted = remember(id, dimensions) { mutableStateOf(false) }
    if (visible) DisposableEffect(id) {
        DesktopMusicVideo.attach(id)
        onDispose {
            DesktopMusicVideo.detach(id)
            report(false)
        }
    }
    LaunchedEffect(id, submitted.value) { report(submitted.value) }
    val size = dimensions
    if (size != null) {
        val painter = remember(id, size, current) {
            VideoSurfacePainter(size, { current.value }, { DesktopMusicVideo.withFrame(id, it) }) {
                if (!submitted.value) submitted.value = true
            }
        }
        Image(painter = painter, contentDescription = null, modifier = modifier,
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop)
    } else if (failed && visible && showFailure) androidx.compose.foundation.layout.Box(modifier.background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            androidx.compose.material3.Text("Video unavailable", color = androidx.compose.ui.graphics.Color.White)
            androidx.compose.material3.TextButton(onClick = DesktopMusicVideo::retry) { androidx.compose.material3.Text("Retry video") }
        }
    }
}
