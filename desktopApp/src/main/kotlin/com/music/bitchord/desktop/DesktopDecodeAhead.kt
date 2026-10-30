package com.music.bitchord.desktop

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What [DesktopDecodeAhead] reads from: a decoder, or a stand-in for one in tests. */
internal interface DesktopSampleSource {
    /** The next block as interleaved floats, or null at the end. The array may be reused. */
    fun readSamples(): FloatArray?

    /** How much of the array [readSamples] returned is actually this block. */
    val sampleCount: Int

    fun seek(micros: Long): Boolean

    fun close()
}

/** Lifecycle of the worker behind [DesktopDecodeAhead]. */
internal enum class DesktopDecodeState {
    RUNNING,
    EOF,
    FAILED,
    CLOSED,
}

/**
 * Decodes a track a few seconds ahead of the speakers, on a thread of its own.
 *
 * The audio thread used to call the decoder directly, and the decoder reads straight from the
 * network: a YouTube stream stops for a whole 512 KiB range request every half-minute or so, and a
 * lossless stream waits on its socket whenever the connection hiccups. WASAPI holds only a fifth
 * of a second, so any read slower than that was heard as a gap of a few milliseconds. Here those
 * waits land on this thread while the audio thread plays what was decoded earlier.
 *
 * Only this thread touches [source] once constructed — seeks and the close are handed to it — so
 * the native decoder never sees two threads.
 */
internal class DesktopDecodeAhead(
    private val source: DesktopSampleSource,
    val outputFormat: DesktopPcmFormat,
    val durationUs: Long?,
    val measuredFormat: DesktopStreamFormat?,
    /** How far ahead to decode, in samples across all channels. */
    private var aheadSamples: Int = outputFormat.sampleRate * outputFormat.channels * AHEAD_SECONDS,
    name: String = "BitChord-Decode",
) {

    private val lock = ReentrantLock()
    /** Signalled when there is something for the audio thread to take. */
    private val ready = lock.newCondition()
    /** Signalled when the worker has room, a seek, or a close to act on. */
    private val wake = lock.newCondition()

    private val queue = ArrayDeque<FloatArray>()
    private var queuedSamples = 0
    private var lifecycle = DesktopDecodeState.RUNNING
    private var failure: Throwable? = null
    /** Set only while a consumer is genuinely waiting on an otherwise-live worker. */
    private var underrunSinceNanos = 0L
    private var pendingSeekUs: Long? = null
    /** Bumped by every seek, so a block decoded before it is never played after it. */
    private var generation = 0
    /** Whether a block has been handed out since the last seek; a wait before the first is a load. */
    private var started = false

    /** How much of the array [readSamples] returned is actually this block. */
    var sampleCount: Int = 0
        private set

    private val worker = Thread(::decodeLoop, name).apply {
        isDaemon = true
        // Above the UI, below the audio thread itself.
        priority = Thread.NORM_PRIORITY + 1
        start()
    }

    /** Expand a speculative opening to the normal underrun-recovery budget when selected. */
    fun prepareForPlayback() = lock.withLock {
        aheadSamples = maxOf(aheadSamples, outputFormat.sampleRate * outputFormat.channels * AHEAD_SECONDS)
        wake.signalAll()
    }

    /** The next decoded block, waiting for the decoder only if it has fallen behind. */
    fun readSamples(): FloatArray? = readSamples(Long.MAX_VALUE)

    /**
     * The next decoded block, but gives the caller back control after [maxWaitMillis].
     *
     * A live decoder can be blocked in FFmpeg/network I/O for seconds. The playback thread also
     * drains Start/Flush/Seek/Reconfigure commands, so it must never wait for that decoder
     * indefinitely. A null result is either a real end (see [isEnded]) or a temporary underrun.
     */
    fun readSamples(maxWaitMillis: Long): FloatArray? = lock.withLock {
        if (queue.isEmpty() && lifecycle == DesktopDecodeState.RUNNING) {
            val waitedFrom = System.nanoTime()
            if (maxWaitMillis == Long.MAX_VALUE) {
                while (queue.isEmpty() && lifecycle == DesktopDecodeState.RUNNING) ready.await()
            } else {
                var remaining = TimeUnit.MILLISECONDS.toNanos(maxWaitMillis.coerceAtLeast(0L))
                while (queue.isEmpty() && lifecycle == DesktopDecodeState.RUNNING && remaining > 0L) {
                    remaining = ready.awaitNanos(remaining)
                }
            }
            val waitedMs = (System.nanoTime() - waitedFrom) / 1_000_000
            if (started && queue.isNotEmpty() && waitedMs >= STARVED_LOG_MS) {
                DesktopTrackLog.log("decode-ahead recovered after waiting ${waitedMs}ms for the decoder")
            }
        }
        val block = queue.removeFirstOrNull()
        if (block == null) {
            sampleCount = 0
            if (lifecycle == DesktopDecodeState.RUNNING) {
                if (underrunSinceNanos == 0L) {
                    underrunSinceNanos = System.nanoTime()
                    if (started && DesktopPlaybackDiagnostics.enabled) DesktopPlaybackDiagnostics.underruns.incrementAndGet()
                }
            } else {
                underrunSinceNanos = 0L
            }
            return null
        }
        underrunSinceNanos = 0L
        queuedSamples -= block.size
        started = true
        wake.signal()
        sampleCount = block.size
        block
    }

    /**
     * Non-destructive readiness probe for lossless/Hi-Res source handoffs.
     *
     * Opening an FFmpeg container does not guarantee the URL can actually decode PCM.
     * Keep the first block queued while the candidate is checked off the audio thread,
     * so a failed plugin/manifest never silences the currently playing track.
     */
    fun awaitFirstAudio(timeoutMillis: Long): Boolean = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0L))
        while (queue.isEmpty() && lifecycle == DesktopDecodeState.RUNNING && remaining > 0L) {
            remaining = ready.awaitNanos(remaining)
        }
        queue.isNotEmpty()
    }

    /** The worker's actual lifecycle, so a dead worker cannot masquerade as buffering forever. */
    val state: DesktopDecodeState
        get() = lock.withLock { lifecycle }

    /** Why [state] became [DesktopDecodeState.FAILED], when there is one. */
    val failureCause: Throwable?
        get() = lock.withLock { failure }

    /** Time spent with an empty queue while the worker still claims to be live. */
    val underrunMillis: Long
        get() = lock.withLock {
            if (underrunSinceNanos == 0L) 0L
            else (System.nanoTime() - underrunSinceNanos).coerceAtLeast(0L) / 1_000_000
        }

    /** Compatibility for callers that only need terminal-vs-temporary-null semantics. */
    val isEnded: Boolean
        get() = lock.withLock { lifecycle != DesktopDecodeState.RUNNING }

    /** Moves to [micros]; whatever was decoded ahead of the old position is thrown away. */
    fun seek(micros: Long): Boolean {
        lock.withLock {
            if (lifecycle == DesktopDecodeState.CLOSED || lifecycle == DesktopDecodeState.FAILED) return false
            generation++
            queue.clear()
            queuedSamples = 0
            lifecycle = DesktopDecodeState.RUNNING
            failure = null
            underrunSinceNanos = 0L
            started = false
            pendingSeekUs = micros
            wake.signalAll()
            ready.signalAll()
        }
        return true
    }

    /** Stops decoding and wakes/interrupts the worker so parked reads cannot leak a thread. */
    fun close() {
        lock.withLock {
            if (lifecycle == DesktopDecodeState.CLOSED) return
            lifecycle = DesktopDecodeState.CLOSED
            queue.clear()
            queuedSamples = 0
            underrunSinceNanos = 0L
            wake.signalAll()
            ready.signalAll()
        }
        worker.interrupt()
    }

    private fun decodeLoop() {
        var terminalFailure: Throwable? = null
        try {
            while (true) {
                var seekTo: Long? = null
                val decodingFor = lock.withLock {
                    while (
                        lifecycle != DesktopDecodeState.CLOSED &&
                        lifecycle != DesktopDecodeState.FAILED &&
                        pendingSeekUs == null &&
                        (lifecycle == DesktopDecodeState.EOF || queuedSamples >= aheadSamples)
                    ) {
                        wake.await()
                    }
                    if (lifecycle == DesktopDecodeState.CLOSED || lifecycle == DesktopDecodeState.FAILED) return
                    seekTo = pendingSeekUs
                    pendingSeekUs = null
                    generation
                }

                val micros = seekTo
                if (micros != null) {
                    if (!source.seek(micros)) {
                        DesktopTrackLog.log("decode-ahead: seek to ${micros / 1_000}ms failed")
                    }
                    continue
                }

                // Do not translate decoder exceptions into EOF. EOF is a valid end-of-song state;
                // a thrown decoder/network/native failure needs a different state so the engine can
                // reopen the stream instead of either skipping the song or waiting forever.
                val decoded = source.readSamples()?.copyOf(source.sampleCount)
                lock.withLock {
                    // A seek came in while this block was being read; it belongs to the old position.
                    if (decodingFor != generation) return@withLock
                    if (lifecycle == DesktopDecodeState.CLOSED || lifecycle == DesktopDecodeState.FAILED) {
                        return@withLock
                    }
                    if (decoded == null) {
                        lifecycle = DesktopDecodeState.EOF
                        underrunSinceNanos = 0L
                    } else if (decoded.isNotEmpty()) {
                        queue.addLast(decoded)
                        queuedSamples += decoded.size
                    }
                    ready.signalAll()
                }
            }
        } catch (interrupted: InterruptedException) {
            val expected = lock.withLock { lifecycle == DesktopDecodeState.CLOSED }
            if (!expected) terminalFailure = interrupted
        } catch (failed: Throwable) {
            terminalFailure = failed
        } finally {
            val closeFailure = runCatching { source.close() }.exceptionOrNull()
            val unexpected = terminalFailure ?: closeFailure
            var report: Throwable? = null
            lock.withLock {
                if (lifecycle != DesktopDecodeState.CLOSED) {
                    report = unexpected ?: IllegalStateException("decode worker stopped unexpectedly")
                    failure = report
                    lifecycle = DesktopDecodeState.FAILED
                    underrunSinceNanos = 0L
                }
                ready.signalAll()
                wake.signalAll()
            }
            report?.let { failed ->
                val message = failed.message?.takeIf { it.isNotBlank() } ?: failed.javaClass.simpleName
                runCatching { DesktopTrackLog.log("decode-ahead worker failed: $message") }
            }
        }
    }

    private companion object {
        /** Long enough to ride out a slow range request or a reconnect. */
        const val AHEAD_SECONDS = 8

        /** Waits shorter than this are ordinary scheduling, not a network stall worth logging. */
        const val STARVED_LOG_MS = 20L
    }
}
