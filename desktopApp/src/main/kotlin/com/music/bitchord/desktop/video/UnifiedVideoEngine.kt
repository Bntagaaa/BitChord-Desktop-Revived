package com.music.bitchord.desktop

import androidx.compose.ui.geometry.Size
import com.music.bitchord.data.innertube.InnerTubeXResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        publish: (VideoFrame) -> Unit,
        running: StateFlow<Boolean>,
    ) {
        // A manifest can fail to rewind. Limit retries without changing the normal native loop path.
        val candidates = listOfNotNull(source.url, source.fallbackUrl).distinct()
        for (candidate in candidates) {
            var emptyPasses = 0
            var everShown = false
            val maxEmpty = { if (everShown) 4 else 2 }
            while (currentCoroutineContext().isActive && emptyPasses < maxEmpty()) {
                running.first { it }
                val path = runInterruptible {
                    if (isManifest(candidate)) {
                        DesktopCanvasCache.hlsFileIfCached(candidate, source.maxEdge)?.toAbsolutePath()?.toString()
                            ?: run {
                                DesktopCanvasCache.warmHlsInBackground(candidate, source.maxEdge)
                                DesktopCanvasCache.resolveHlsMediaUrl(candidate, source.maxEdge)
                            }
                    } else {
                        DesktopCanvasCache.fileFor(candidate)?.toAbsolutePath()?.toString()
                    }
                }
                if (path == null) break
                val context = currentCoroutineContext()
                context.ensureActive()
                val decoder = backend.decoder(
                    source.maxEdge,
                    loop = true,
                    owner = context[Job],
                    preferMotionRendition = true,
                )
                var shown = false
                try {
                    runInterruptible { decoder.open(path).getOrThrow() }
                    var clockOriginNanos = 0L
                    var firstPtsMs = 0L
                    var seenLoopGeneration = decoder.loopGeneration
                    while (context.isActive) {
                        if (!running.value) {
                            running.first { it }
                            clockOriginNanos = 0L
                        }
                        motion.awaitDrawIfBehind(source)
                        val frame = decoder.nextFrame { pts ->
                            context.ensureActive()
                            if (clockOriginNanos == 0L || decoder.loopGeneration != seenLoopGeneration) {
                                seenLoopGeneration = decoder.loopGeneration
                                clockOriginNanos = System.nanoTime()
                                firstPtsMs = pts
                                true
                            } else {
                                val targetPts = firstPtsMs + (System.nanoTime() - clockOriginNanos) / 1_000_000L
                                pts + decoder.frameIntervalMillis >= targetPts
                            }
                        } ?: break
                        val pts = decoder.frameTimeMs
                        if (clockOriginNanos == 0L || decoder.loopGeneration != seenLoopGeneration) {
                            seenLoopGeneration = decoder.loopGeneration
                            clockOriginNanos = System.nanoTime()
                            firstPtsMs = pts
                        }
                        val targetPts = firstPtsMs + (System.nanoTime() - clockOriginNanos) / 1_000_000L
                        val waitMs = (pts - targetPts - VSYNC_LEAD_MS).coerceIn(0L, 200L)
                        if (waitMs > 0L) delay(waitMs)
                        publish(frame)
                        shown = true
                        everShown = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    DesktopTrackLog.log("motion video: ${failure.javaClass.simpleName}")
                } finally {
                    decoder.close()
                }
                emptyPasses = if (shown) 0 else emptyPasses + 1
                delay(if (shown) 16L else 750L)
            }
        }
    }

    private const val VSYNC_LEAD_MS = 8L

}

/** Compatible surfaces share one worker and one published frame. A closing worker is joined before reopening. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MotionVideoSessions(
    private val scope: CoroutineScope,
    private val play: suspend (MotionVideoSource, (VideoFrame) -> Unit, StateFlow<Boolean>) -> Unit,
) : AutoCloseable {
    private var closed = false
    private val sessions = mutableMapOf<MotionVideoSource, Entry>()

    private class Entry {
        val frame = MutableStateFlow<VideoFrame?>(null)
        val presentation = MutableStateFlow<MotionVideoFrameInfo?>(null)
        var sequence = 0L
        var awaitingDraw = false
        val drawSignal = Channel<Unit>(Channel.CONFLATED)
        val running = MutableStateFlow(false)
        val views = mutableMapOf<Any, Boolean>()
        lateinit var job: Job
    }

    internal class Lease(
        val frame: StateFlow<VideoFrame?>,
        val presentation: StateFlow<MotionVideoFrameInfo?>,
        private val read: ((VideoFrame) -> Unit, Boolean) -> Unit,
        private val update: (Boolean) -> Unit,
        private val release: () -> Unit,
    ) : AutoCloseable {
        fun withFrame(markDrawn: Boolean = true, readFrame: (VideoFrame) -> Unit) = read(readFrame, markDrawn)
        fun running(value: Boolean) = update(value)
        override fun close() = release()
    }

    @Synchronized
    fun attach(source: MotionVideoSource, running: Boolean): Lease {
        if (closed) return Lease(MutableStateFlow(null), MutableStateFlow(null), { _, _ -> }, {}, {})
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
            entry.frame, entry.presentation, { read, markDrawn -> readFrame(entry, token, markDrawn, read) },
            { setRunning(entry, token, it) }, { release(source, entry, token) },
        )
    }

    @Synchronized
    private fun publish(entry: Entry, next: VideoFrame) {
        if (entry.views.isEmpty() || !entry.running.value) {
            next.close()
            return
        }
        val old = entry.frame.value
        entry.frame.value = next
        entry.drawSignal.tryReceive()
        entry.awaitingDraw = true
        entry.presentation.value = MotionVideoFrameInfo(next.intrinsicSize, ++entry.sequence)
        old?.close()
    }

    @Synchronized
    private fun readFrame(entry: Entry, token: Any, markDrawn: Boolean, read: (VideoFrame) -> Unit) {
        if (token in entry.views) {
            entry.frame.value?.let(read)
            if (markDrawn && entry.awaitingDraw) {
                entry.awaitingDraw = false
                entry.drawSignal.trySend(Unit)
            }
        }
    }

    suspend fun awaitDrawIfBehind(source: MotionVideoSource) {
        val entry = synchronized(this) {
            sessions[source]?.takeIf { it.awaitingDraw && it.views.isNotEmpty() && it.running.value }
        } ?: return
        withTimeoutOrNull(33L) { entry.drawSignal.receive() }
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
        entry.awaitingDraw = false
        entry.drawSignal.trySend(Unit)
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
