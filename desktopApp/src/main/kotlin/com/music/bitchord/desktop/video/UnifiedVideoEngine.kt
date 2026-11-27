package com.music.bitchord.desktop

import androidx.compose.ui.geometry.Size
import com.music.bitchord.data.innertube.InnerTubeXResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal data class MotionVideoFrameInfo(val size: Size, val sequence: Long)

internal data class MotionVideoSource(
    val url: String,
    val fallbackUrl: String?,
    val maxEdge: Int,
    val clock: Clock,
    val track: String? = null,
) {
    enum class Clock { Artwork, NowPlaying, Artist }
}

internal interface VideoDecoder : AutoCloseable {
    val frameIntervalMillis: Long
    val frameTimeMs: Long
    val loopGeneration: Int get() = 0
    fun open(url: String, headers: Map<String, String> = emptyMap()): Result<Unit>
    fun seekTo(millis: Long): Boolean
    fun nextFrame(accept: (Long) -> Boolean = { true }): VideoFrame?
}

internal interface VideoBackend {
    fun decoder(maxEdge: Int, loop: Boolean, owner: Job?, preferMotionRendition: Boolean = false): VideoDecoder
}

private object FfmpegVideoBackend : VideoBackend {
    override fun decoder(maxEdge: Int, loop: Boolean, owner: Job?, preferMotionRendition: Boolean) =
        DesktopCanvasDecoder(maxEdge, loop, preferMotionRendition, owner)
}

internal object UnifiedVideoEngine {
    private val backend: VideoBackend = FfmpegVideoBackend
    private val motion = DesktopApplicationResources.owner.let { owner ->
        owner.own("motion-video") {
            MotionVideoSessions(owner.scope, ::loopMotion)
        }
    }

    fun motion(source: MotionVideoSource, running: Boolean) = motion.attach(source, running)
    fun hasMotionSession(source: MotionVideoSource) = motion.hasSession(source)

    fun musicDecoder(owner: Job?): DesktopVideoDecoder {
        return object : DesktopVideoDecoder {
            private var decoder: VideoDecoder? = null
            override val frameTimeMs get() = decoder?.frameTimeMs ?: 0
            override fun open(stream: InnerTubeXResolver.VideoStream) {
                val reportedEdge = maxOf(stream.width, stream.height)
                val maxEdge = if (stream.width > 0 && stream.height > 0) reportedEdge.coerceAtMost(8192) else 8192
                val next = backend.decoder(maxEdge, loop = false, owner = owner)
                decoder = next
                next.open(stream.url, stream.headers).getOrThrow()
                if (next is DesktopCanvasDecoder) {
                    DesktopTrackLog.log("music video: stream ${stream.width}x${stream.height}, output ${next.width}x${next.height}")
                }
            }
            override fun seekTo(millis: Long) = decoder?.seekTo(millis) == true
            override fun nextFrame(accept: (Long) -> Boolean) = decoder?.nextFrame(accept)
            override fun close() {
                decoder?.close()
                decoder = null
            }
        }
    }

    private suspend fun loopMotion(
        source: MotionVideoSource,
        publish: (VideoFrame) -> Boolean,
        running: StateFlow<Boolean>,
    ) {
        val context = currentCoroutineContext()
        val candidates = listOfNotNull(source.url, source.fallbackUrl).distinct()
        for (candidate in candidates) {
            val manifest = isManifest(candidate)
            val hls = isHlsManifest(candidate)
            var emptyPasses = 0
            var clipSize: Size? = null
            var cacheRejected = false
            var warmRequested = false
            var frameEndsAtNanos = 0L

            // Allow fallback only until a candidate has successfully presented a frame.
            while (context.isActive && (clipSize != null || emptyPasses < 2)) {
                running.first { it }
                val cached = if (hls && !cacheRejected) {
                    runInterruptible { DesktopCanvasCache.hlsFileIfCached(candidate, source.maxEdge) }
                } else null
                val path = cached?.toAbsolutePath()?.toString() ?: runInterruptible {
                    when {
                        hls -> DesktopCanvasCache.resolveHlsMediaUrl(candidate, source.maxEdge)
                        manifest -> candidate
                        else -> DesktopCanvasCache.fileFor(candidate)?.toAbsolutePath()?.toString()
                    }
                }
                if (path == null) {
                    emptyPasses++
                    delay(RETRY_DELAY_MS)
                    continue
                }

                val decoder = FfmpegVideoBackend.decoder(
                    source.maxEdge, loop = true, owner = context[Job], preferMotionRendition = true,
                )
                var shown = false
                try {
                    runInterruptible { decoder.open(path).getOrThrow() }
                    val openedSize = Size(decoder.width.toFloat(), decoder.height.toFloat())
                    check(clipSize == null || clipSize == openedSize) { "motion video changed dimensions" }

                    var clockOriginNanos = 0L
                    var firstPtsMs = 0L
                    var nextFramePtsMs = 0L
                    var generation = decoder.loopGeneration
                    while (context.isActive) {
                        if (!running.value) {
                            running.first { it }
                            clockOriginNanos = 0L
                            frameEndsAtNanos = 0L
                        }
                        // The HLS demuxer can report a successful seek and still yield no frames.
                        // Open a fresh demuxer at each HLS EOF and give the disk cache first refusal.
                        val frame = decoder.nextMotionFrame(
                            rewindAtEnd = !manifest,
                            accept = { pts ->
                                context.ensureActive()
                                val now = System.nanoTime()
                                if (clockOriginNanos == 0L || generation != decoder.loopGeneration) {
                                    generation = decoder.loopGeneration
                                    clockOriginNanos = maxOf(now, frameEndsAtNanos)
                                    firstPtsMs = pts
                                    nextFramePtsMs = pts
                                }
                                val targetPts = firstPtsMs + (now - clockOriginNanos) / 1_000_000L
                                pts >= nextFramePtsMs - 1 && pts + decoder.frameIntervalMillis >= targetPts
                            },
                            awaitPresentation = {
                                val pts = decoder.frameTimeMs
                                val elapsedMs = (System.nanoTime() - clockOriginNanos) / 1_000_000L
                                val waitMs = (pts - firstPtsMs - elapsedMs).coerceIn(0L, 200L)
                                if (waitMs > 0) delay(waitMs)
                                context.ensureActive()
                            },
                        ) ?: break
                        if (publish(frame)) {
                            shown = true
                            clipSize = openedSize
                            val pts = decoder.frameTimeMs
                            nextFramePtsMs = maxOf(nextFramePtsMs + DesktopCanvasDecoder.MIN_INTERVAL_MS, pts + 1)
                            frameEndsAtNanos = clockOriginNanos +
                                (pts - firstPtsMs + decoder.frameIntervalMillis) * 1_000_000L
                            if (hls && cached == null && !cacheRejected && !warmRequested) {
                                warmRequested = true
                                DesktopCanvasCache.warmHlsInBackground(candidate, source.maxEdge)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    DesktopTrackLog.log("motion video: ${failure.message}")
                } finally {
                    decoder.close()
                }
                context.ensureActive()
                if (cached != null && !shown) {
                    runInterruptible { DesktopCanvasCache.discardHlsPlaylist(cached) }
                    cacheRejected = true
                    continue
                }
                emptyPasses = if (shown) 0 else emptyPasses + 1
                if (!shown) delay(RETRY_DELAY_MS)
            }
        }
    }

    private const val RETRY_DELAY_MS = 750L

}

/** Compatible surfaces share one worker and one published frame. A closing worker is joined before reopening. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MotionVideoSessions(
    private val scope: CoroutineScope,
    private val play: suspend (MotionVideoSource, (VideoFrame) -> Boolean, StateFlow<Boolean>) -> Unit,
) : AutoCloseable {
    private var closed = false
    private val sessions = mutableMapOf<MotionVideoSource, Entry>()

    private class Entry {
        val frame = MutableStateFlow<VideoFrame?>(null)
        val presentation = MutableStateFlow<MotionVideoFrameInfo?>(null)
        var sequence = 0L
        val running = MutableStateFlow(false)
        val views = mutableMapOf<Any, Boolean>()
        lateinit var job: Job
    }

    internal class Lease(
        val frame: StateFlow<VideoFrame?>,
        val presentation: StateFlow<MotionVideoFrameInfo?>,
        private val read: ((VideoFrame) -> Unit) -> Unit,
        private val update: (Boolean) -> Unit,
        private val release: () -> Unit,
    ) : AutoCloseable {
        fun withFrame(readFrame: (VideoFrame) -> Unit) = read(readFrame)
        fun running(value: Boolean) = update(value)
        override fun close() = release()
    }

    @Synchronized
    fun attach(source: MotionVideoSource, running: Boolean): Lease {
        if (closed) return Lease(MutableStateFlow(null), MutableStateFlow(null), {}, {}, {})
        val previous = sessions[source]
        val entry = previous?.takeIf { it.views.isNotEmpty() } ?: Entry().also { created ->
            sessions[source] = created
            created.job = scope.launch(start = CoroutineStart.ATOMIC) {
                withContext(NonCancellable) { previous?.job?.join() }
                currentCoroutineContext().ensureActive()
                play(source, { publish(created, it) }, created.running)
            }
            created.job.invokeOnCompletion { removeIfIdle(source, created) }
        }
        val token = Any()
        entry.views[token] = running
        entry.running.value = entry.views.containsValue(true)
        return Lease(
            entry.frame, entry.presentation, { readFrame(entry, token, it) },
            { setRunning(entry, token, it) }, { release(source, entry, token) },
        )
    }

    @Synchronized
    private fun publish(entry: Entry, next: VideoFrame): Boolean {
        if (entry.views.isEmpty() || !entry.running.value) {
            next.close()
            return false
        }
        val old = entry.frame.value
        entry.frame.value = next
        entry.presentation.value = MotionVideoFrameInfo(next.intrinsicSize, ++entry.sequence)
        old?.close()
        return true
    }

    @Synchronized
    private fun readFrame(entry: Entry, token: Any, read: (VideoFrame) -> Unit) {
        if (token in entry.views) entry.frame.value?.let(read)
    }

    @Synchronized
    private fun setRunning(entry: Entry, token: Any, running: Boolean) {
        if (entry.views.replace(token, running) != null) entry.running.value = entry.views.containsValue(true)
    }

    @Synchronized
    private fun release(source: MotionVideoSource, entry: Entry, token: Any) {
        entry.views.remove(token) ?: return
        entry.running.value = entry.views.containsValue(true)
        if (entry.views.isEmpty()) {
            clearFrame(entry)
            entry.job.cancel()
            if (entry.job.isCompleted) removeIfIdle(source, entry)
        }
    }

    private fun clearFrame(entry: Entry) {
        val old = entry.frame.value
        entry.frame.value = null
        entry.presentation.value = null
        old?.close()
    }

    @Synchronized
    override fun close() {
        closed = true
        val entries = sessions.values.toList()
        sessions.clear()
        entries.forEach { entry ->
            entry.views.clear()
            entry.running.value = false
            clearFrame(entry)
            entry.job.cancel()
        }
    }

    @Synchronized
    fun hasSession(source: MotionVideoSource): Boolean = source in sessions

    @Synchronized
    private fun removeIfIdle(source: MotionVideoSource, entry: Entry) {
        if (entry.views.isEmpty() && sessions[source] === entry) sessions.remove(source)
    }
}
