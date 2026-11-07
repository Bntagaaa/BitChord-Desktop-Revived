/*
 * Desktop model-window orchestration adapted from BitChord b690dc14.
 * Modeled on Orchard's own TrackAnalyzer (https://github.com/SFG5453/Orchard).
 * Phase 1 was the DSP-only pass (native/analyzer/audio_analysis.cpp); Phase 2
 * adds the Beat This! ONNX model (see [BeatTracker]) and Phase 3 the
 * open-unmix vocal mask (see [VocalTracker]), both over the head and tail of
 * the track, which is the only part a transition ever reads.
 *
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.AnalysisStore
import com.music.bitchord.playback.smart.BeatTracker
import com.music.bitchord.playback.smart.MelSpectrogram
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TrackFeatures
import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.VocalTracker
import com.music.bitchord.playback.smart.VocalSpectrogram
import com.music.bitchord.playback.smart.levelFromRms
import com.music.bitchord.playback.smart.heuristicVocalOnModelScale
import kotlin.math.ceil
import kotlin.math.max
import com.music.bitchord.playback.loopmix.LoopRegion
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/** A file on this machine — a download or a local track — rather than anything streamed. */
internal val DesktopStream.isLocalFile: Boolean
    get() = !url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)

/**
 * Produces the evidence Automix mixes on: tempo, beat grid, key, structure and where a track can be
 * entered and left.
 */
internal class DesktopTrackAnalyzer(
    /**
     * How much CPU the listener has allowed background analysis, read fresh on every pass so a
     * change takes effect on the next track rather than the next launch.
     */
    private val performance: () -> AutomixPerformanceMode = { AutomixPerformanceMode.BALANCED },
    /**
     * True while analysis has no use — in a party, where Automix is off. Checked on request, before
     * a queued pass starts, and between and during its decodes, as Android's `TrackAnalyzer` does:
     * a request made a moment before the party started would otherwise run a whole-track decode
     * and a model pass for a transition that cannot happen. A stopped pass records nothing.
     */
    private val stopped: () -> Boolean = { false },
    private val onAnalysed: (String) -> Unit = {},
    private val persistResult: ((String, TrackAnalysis) -> Unit)? = null,
    /**
     * Optional transition windows enrich the DSP result but must never invalidate it.
     */
    private val loopRegion: (DesktopStream, Double, Double, Int, () -> Boolean) -> LoopRegion? =
        ::desktopLoopRegion,
) {
    /** One analysis at a time, on a thread of this class's own making. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "BitChord-Analysis", ANALYSIS_STACK_BYTES).apply {
            isDaemon = true
        }
    }

    /** Analyses kept between runs, so a track measured yesterday is not measured again today. */
    private val store = AnalysisStore { DesktopAnalysisRuntime.analysisHome().resolve("upstream-b690dc14-v2").also { it.mkdirs() } }

    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    /** Only one drain is scheduled; only current and next are eligible for queued work. */
    private data class AnalysisJob(val song: Song, val stream: DesktopStream?, val seconds: Double)
    private val pending = DesktopAutomixWorkQueue<AnalysisJob>()
    private val schedulerLock = Any()
    private var drainScheduled = false
    private val activeTaskId = ThreadLocal<String?>()

    /** Tracks the analyser could not measure. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** When each of [failed] last failed, so it is retried on a cooldown rather than every tick. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Said once, not once a tick. */
    private val warnedNoAnalyser = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Leave logical cores free for real-time playback on modest CPUs. */
    private fun inferenceThreads(): Int = performance().inferenceThreads.coerceAtMost(
        (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(1),
    )

    private val vocals by lazy {
        VocalTracker({ DesktopAnalysisRuntime.modelPath(VocalTracker.MODEL_ASSET) }, { inferenceThreads() })
    }
    private var vocalsUsed = false
    private var beatsUsed = false
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun cancelled(): Boolean {
        val active = activeTaskId.get()
        return released.get() || Thread.currentThread().isInterrupted || stopped() ||
            (active != null && !pending.isRelevant(active))
    }

    private val beats by lazy {
        BeatTracker(
            modelPath = { DesktopAnalysisRuntime.modelPath(BeatTracker.MODEL_ASSET) },
            inferenceThreads = { inferenceThreads() },
        )
    }

    /** Replace obsolete work as the playing pair changes. */
    fun focusOn(currentTrackId: String?, nextTrackId: String?) {
        pending.focus(currentTrackId, nextTrackId)
    }

    /** What is known now, never a computation — the planner asks this every tick. */
    fun analysisFor(trackId: String): TrackAnalysis =
        results[trackId] ?: restored(trackId) ?: TrackAnalysis(trackId = trackId)

    /** A stored analysis, promoted into memory on first ask. */
    private fun restored(trackId: String): TrackAnalysis? =
        store.load(trackId)?.also { results[trackId] = it }

    fun isAnalysed(trackId: String): Boolean =
        results.containsKey(trackId) || restored(trackId) != null

    /** How far this track has got, for the player's Automix line. */
    fun stateFor(trackId: String): TrackAnalysisState = when {
        trackId.isBlank() -> TrackAnalysisState.WAITING
        !DesktopAnalysisRuntime.available -> TrackAnalysisState.FAILED
        // Usable first and a pass in flight second, the order Android settled on.
        results[trackId]?.isUsable == true ->
            if (trackId in running) TrackAnalysisState.REFINING else TrackAnalysisState.ANALYSED
        trackId in running -> TrackAnalysisState.ANALYSING
        // A recorded-but-unusable result is the analyser saying it tried and got nothing, and that
        // it will not try again.
        trackId in failed || results.containsKey(trackId) -> TrackAnalysisState.FAILED
        else -> TrackAnalysisState.WAITING
    }

    /**
     * Asks for [song] to be analysed, if it has not been already.
     *
     * [stream] is only read when it is a file on this machine. Anything streamed is measured on
     * the track's YouTube Opus instead — never on the addon, JioSaavn or lossless copy playback
     * may be using — exactly as Android's analyser reads its own pinned Opus rendition (see
     * `AutomixAnalysisSource`). That keeps an addon from being asked for a track nobody is
     * playing yet, and one recording's measurements from being stored under another's id.
     */
    fun request(song: Song, stream: DesktopStream?, durationSeconds: Double) {
        val trackId = song.videoId
        if (trackId.isBlank() || durationSeconds <= 0 || cancelled() || !pending.isRelevant(trackId)) return
        if (!DesktopAnalysisRuntime.available) {
            if (warnedNoAnalyser.compareAndSet(false, true)) {
                DesktopTrackLog.log("automix: this build has no analyser, so nothing will be measured")
            }
            return
        }
        if (results[trackId]?.isUsable == true || restored(trackId)?.isUsable == true) return
        failedAt[trackId]?.let { if (System.currentTimeMillis() - it < RETRY_AFTER_MS) return }
        val job = AnalysisJob(song, stream?.takeIf { it.isLocalFile }, durationSeconds)
        synchronized(schedulerLock) {
            if (trackId in running || !pending.offer(trackId, job)) return
            if (!drainScheduled) {
                drainScheduled = true
                try {
                    worker.execute(::drainRequests)
                } catch (closed: java.util.concurrent.RejectedExecutionException) {
                    drainScheduled = false
                    pending.clear()
                    if (!released.get()) throw closed
                }
            }
        }
    }

    /** One executor task drains the bounded, current-song-first queue. */
    private fun drainRequests() {
        while (!released.get()) {
            val picked = synchronized(schedulerLock) {
                pending.poll().also {
                    if (it == null) drainScheduled = false
                    else running.add(it.first)
                }
            } ?: return
            activeTaskId.set(picked.first)
            try {
                runAnalysis(picked.second)
            } finally {
                activeTaskId.remove()
                running.remove(picked.first)
            }
        }
    }

    private fun runAnalysis(job: AnalysisJob) {
        if (cancelled()) return
        val song = job.song
        val trackId = song.videoId
        Thread.currentThread().priority =
            if (performance().yieldsToPlayback || Runtime.getRuntime().availableProcessors() <= 4)
                Thread.MIN_PRIORITY else Thread.NORM_PRIORITY
        failed.remove(trackId)
        DesktopTrackLog.log(
            "automix: analysing '${song.title}' (${"%.0f".format(job.seconds)}s)" +
                if (job.stream != null) " from the file" else " from YouTube Opus",
        )
        val analysis = runCatching {
            val source = job.stream ?: youTubeOpus(song) ?: return@runCatching null
            analyse(trackId, source, job.seconds)
        }.onFailure {
            if (!cancelled()) DesktopTrackLog.log("analysis of '${song.title}' failed: ${it.message}")
        }.getOrNull()
        if (cancelled()) return
        if (analysis == null) {
            failed += trackId
            failedAt[trackId] = System.currentTimeMillis()
            return
        }
        // isAnalysed() signals completion. Publish only after persistence so callers that
        // release this analyzer and immediately reload the track cannot outrun the cache write.
        // Keep the result in memory if the disk write fails: AutoMix can still use it.
        runCatching {
            if (persistResult != null) persistResult.invoke(trackId, analysis)
            else store.save(trackId, analysis)
        }.onFailure { DesktopTrackLog.log("automix: could not persist '$trackId': ${it.message}") }
        if (cancelled()) return
        results[trackId] = analysis
        failedAt.remove(trackId)
        if (!analysis.isUsable) {
            failed += trackId
            failedAt[trackId] = System.currentTimeMillis()
        }
        DesktopTrackLog.log(
            "analysed '${song.title}': ${"%.1f".format(analysis.bpm)} bpm" +
                ", ${analysis.downbeats.size} downbeats" +
                ", key ${analysis.key.ifBlank { "unknown" }}",
        )
        runCatching { onAnalysed(trackId) }
            .onFailure { DesktopTrackLog.log("automix: analysis observer failed for '$trackId': ${it.message}") }
    }

    /**
     * [song]'s YouTube Opus stream, bypassing the source race playback runs — or null when there
     * is no YouTube copy of it to measure.
     */
    private fun youTubeOpus(song: Song): DesktopStream? = runBlocking {
        val identity = DesktopSourceRegistry.youTubeIdentity(song)
        if (identity == null) {
            DesktopTrackLog.log("automix: no YouTube copy of '${song.title}' to measure")
            return@runBlocking null
        }
        if (identity.videoId != song.videoId) {
            DesktopTrackLog.log("automix: measuring '${song.title}' on YouTube's ${identity.videoId}")
        }
        kotlinx.coroutines.withTimeoutOrNull(20_000L) {
            DesktopStreamClient.resolve(identity).getOrThrow()
        } ?: run {
            DesktopTrackLog.log("automix: YouTube analysis source timed out")
            null
        }
    }

    private fun analyse(
        trackId: String,
        stream: DesktopStream,
        durationSeconds: Double,
    ): TrackAnalysis? {
        // Reuse the same range-cache namespace across the independent decodes. Signed YouTube
        // URLs are not persisted to disk, but local/immutable sources still benefit from reuse.
        val cacheIdentity = "automix|$trackId|${stream.sourceId.orEmpty()}|${stream.format}"
        val features = structuralFeatures(stream, durationSeconds, cacheIdentity) ?: return null
        if (cancelled()) return null
        // Same bounded head/tail model windows, overlap stitching and mask fusion as
        // upstream TrackAnalyzer at b690dc14. Only decoding/transport is desktop-specific.
        val span = if (VocalSpectrogram.available) {
            (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        } else BeatTracker.WINDOW_SECONDS
        val headEnd = minOf(durationSeconds, 2 * span - WINDOW_OVERLAP_SECONDS)
        val tailStart = max(0.0, durationSeconds - (3 * span - 2 * WINDOW_OVERLAP_SECONDS))
        val regions = ArrayList<Region>()
        for ((from, to) in modelWindows(headEnd, tailStart, durationSeconds, span)) {
            if (cancelled()) return null
            analyseRegion(stream, from, to, features)?.let(regions::add)
        }
        if (cancelled()) return null
        val all = seamBeats(regions)
        val headSide = beatSide(all, 0.0, headEnd)
        val tailSide = beatSide(all, tailStart, durationSeconds)
        val head = headSide?.toGrid()
        val tail = tailSide?.toGrid()
        val leading = tail ?: head
        val masks = regions.mapNotNull { it.vocalMask }
        val merged = if (features.energyCurve.isEmpty() || masks.isEmpty()) null else {
            val values = DoubleArray(features.energyCurve.size) { NEUTRAL_VOCAL }
            for (source in masks) for (index in values.indices) {
                if (index < source.size && source[index] != NEUTRAL_VOCAL) values[index] = source[index]
            }
            values.toList()
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY, trackId = trackId, duration = durationSeconds,
            contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: durationSeconds,
            bpm = leading?.bpm ?: features.bpm,
            beatInterval = leading?.beatInterval ?: features.beatInterval,
            beatConfidence = leading?.beatConfidence ?: features.beatConfidence,
            downbeats = (head?.downbeats.orEmpty() + tail?.downbeats.orEmpty()).ifEmpty { features.downbeats }.sorted(),
            firstBeat = head?.firstBeat ?: features.firstBeat,
            phraseBoundaries = features.phraseBoundaries, key = features.key, keyConfidence = features.keyConfidence,
            audibleStartTime = features.audibleStartTime, pickupTime = features.pickupTime,
            introEndTime = features.introEndTime, outroStartTime = features.outroStartTime,
            mixInTime = features.mixInTime, mixOutTime = features.mixOutTime,
            mixInCandidates = features.mixInCandidates, mixOutCandidates = features.mixOutCandidates,
            energyCurve = features.energyCurve, lowEnergyCurve = features.lowEnergyCurve,
            vocalActivityMask = merged ?: features.vocalActivityMask.map(::heuristicVocalOnModelScale),
            vocalProbability = features.vocalProbability, headBeats = headSide, tailBeats = tailSide,
            vocalWindows = regions.mapNotNull { it.vocalWindow }, levelWindows = regions.mapNotNull { it.levelWindow },
        )
    }

    // Returning only features releases whole-track PCM before any ONNX inference begins.
    private fun structuralFeatures(stream: DesktopStream, seconds: Double, cacheIdentity: String): TrackFeatures.Features? =
        decode(stream, TrackFeatures.sampleRate, cacheIdentity)?.let { TrackFeatures.analyze(it, seconds) }

    private class Region(val grid: BeatTracker.Grid?, val vocalMask: DoubleArray?, val seconds: Double,
        val start: Double, val vocalWindow: CurveWindow?, val levelWindow: CurveWindow?)

    private fun modelInput(region: LoopRegion): FloatArray? {
        val mono = FloatArray(region.left.size) { (region.left[it] + region.right[it]) * 0.5f }
        return MelSpectrogram.resample(mono, 44100.0, MelSpectrogram.sampleRate)
    }

    private fun analyseRegion(stream: DesktopStream, from: Double, to: Double, features: TrackFeatures.Features): Region? {
        val stereo = runCatching { loopRegion(stream, from, to, 44100, ::cancelled) }
            .onFailure { if (it is CancellationException) throw it
                DesktopTrackLog.log("automix: optional region [$from, $to] failed: ${it.message}") }.getOrNull() ?: return null
        if (stereo.left.size < 44100 || stereo.left.size != stereo.right.size) return null
        val input = modelInput(stereo)
        beatsUsed = true
        val grid = runCatching { input?.let { beats.track(it, offsetSeconds = stereo.start) } }
            .onFailure { if (it is CancellationException) throw it
                DesktopTrackLog.log("automix: optional beat pass failed: ${it.message}") }.getOrNull()
        val maxSamples = ((VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate * 44100).toInt()
            .coerceAtMost(stereo.left.size)
        vocalsUsed = true
        val vocal = runCatching {
            if (!VocalSpectrogram.available || maxSamples <= 0) null else vocals.track(
                if (maxSamples < stereo.left.size) stereo.left.copyOf(maxSamples) else stereo.left,
                if (maxSamples < stereo.right.size) stereo.right.copyOf(maxSamples) else stereo.right, 44100.0)
        }.onFailure { if (it is CancellationException) throw it
            DesktopTrackLog.log("automix: optional vocal pass failed: ${it.message}") }.getOrNull()
        val mask = vocal?.let { values -> DoubleArray(features.energyCurve.size) { index ->
            val at = ((features.energyCurve[index].time - stereo.start) * VocalSpectrogram.frameRate).toInt()
            if (at in values.indices) values[at].toDouble() else NEUTRAL_VOCAL
        } }
        val frame = 4410
        val levels = FloatArray(stereo.left.size / frame) { index ->
            var sum = 0.0; var n = 0
            for (i in index * frame until (index + 1) * frame step 4) {
                val sample = (stereo.left[i] + stereo.right[i]) * 0.5f
                sum += sample * sample; n++
            }
            levelFromRms(kotlin.math.sqrt(sum / n)).toFloat()
        }
        return Region(grid, mask, stereo.left.size / 44100.0, stereo.start,
            vocal?.let { CurveWindow(stereo.start, VocalSpectrogram.frameRate, it) },
            if (levels.isEmpty()) null else CurveWindow(stereo.start, 10.0, levels))
    }

    private fun modelWindows(headEnd: Double, tailStart: Double, duration: Double, span: Double): List<Pair<Double, Double>> {
        val spans = if (tailStart <= headEnd) {
            listOf(0.0 to duration)
        } else {
            listOf(0.0 to headEnd, tailStart to duration)
        }
        val windows = ArrayList<Pair<Double, Double>>()
        for ((from, to) in spans) {
            val length = to - from
            if (length < 1.0) continue
            if (length <= span) {
                windows += from to to
                continue
            }
            // As few windows as cover the span with at least the overlap between
            // neighbours, spread evenly from its start to its end.
            val count = ceil((length - WINDOW_OVERLAP_SECONDS) / (span - WINDOW_OVERLAP_SECONDS) - 1e-6).toInt()
            val stride = (length - span) / (count - 1)
            for (index in 0 until count) {
                val start = from + index * stride
                windows += start to minOf(to, start + span)
            }
        }
        return windows
    }

    /** One beat list across the windows: at each overlap, the earlier window's beats before its midpoint, the later's after. */
    private class Beats(val beats: List<Double>, val downbeats: List<Double>, val confidence: Double)

    private fun seamBeats(regions: List<Region>): Beats {
        val graded = regions.filter { it.grid != null }.sortedBy { it.start }
        if (graded.isEmpty()) return Beats(emptyList(), emptyList(), 0.0)
        val beats = ArrayList<Double>()
        val downbeats = ArrayList<Double>()
        var weighted = 0.0
        var count = 0
        for ((index, region) in graded.withIndex()) {
            val grid = region.grid!!
            val previous = graded.getOrNull(index - 1)
            val next = graded.getOrNull(index + 1)
            val from = previous?.let { (it.start + it.seconds + region.start) / 2 }
                ?.takeIf { it > region.start } ?: Double.NEGATIVE_INFINITY
            val until = next?.let { (region.start + region.seconds + it.start) / 2 }
                ?.takeIf { it < region.start + region.seconds } ?: Double.POSITIVE_INFINITY
            val kept = grid.beats.filter { it >= from && it < until }
            beats += kept
            downbeats += grid.downbeats.filter { it >= from && it < until }
            weighted += grid.beatConfidence * kept.size
            count += kept.size
        }
        return Beats(beats.sorted(), downbeats.sorted(), if (count > 0) weighted / count else 0.0)
    }

    /** The model's grid over [from]..[to], or null where it found too few beats there. */
    private fun beatSide(all: Beats, from: Double, to: Double): BeatSide? {
        val beats = all.beats.filter { it >= from && it <= to }
        val bpm = BeatTracker.tempoFromBeats(beats)
        if (bpm <= 0) return null
        return BeatSide(
            beats = beats,
            downbeats = all.downbeats.filter { it >= from && it <= to },
            bpm = bpm,
            confidence = all.confidence,
        )
    }

    private fun BeatSide.toGrid() = BeatTracker.Grid(
        beats = beats,
        downbeats = downbeats,
        bpm = bpm,
        beatInterval = 60 / bpm,
        firstBeat = beats.first(),
        beatConfidence = confidence,
    )

    /** The whole track as mono float at [rate]. */
    private fun decode(stream: DesktopStream, rate: Double, cacheIdentity: String): FloatArray? {
        val decoder = DesktopAudioDecoder()
        val opened = decoder.open(
            url = stream.url,
            headers = stream.headers,
            requested = DesktopPcmFormat(rate.toInt(), channels = 1, bytesPerSample = 4, isFloat = true),
            windowed = stream.windowedReads,
            transport = stream.transport,
            cacheIdentity = cacheIdentity,
            cancelled = ::cancelled,
        )
        if (opened.isFailure) {
            val failure = opened.exceptionOrNull()
            DesktopTrackLog.log(
                "automix: could not open ${stream.sourceId ?: "source"} stream " +
                    "(${stream.format.summary}): " +
                    (failure?.message ?: failure?.javaClass?.simpleName ?: "unknown decoder error"),
            )
            decoder.close()
            return null
        }
        return try {
            val out = ArrayList<FloatArray>()
            var total = 0
            while (true) {
                if (cancelled()) return null
                val block = decoder.readSamples() ?: break
                val count = decoder.sampleCount
                if (count <= 0) continue
                out += block.copyOf(count)
                total += count
                if (total > MAX_SAMPLES) break
            }
            if (total == 0) {
                DesktopTrackLog.log(
                    "automix: decoder returned no PCM samples from ${stream.sourceId ?: "source"} " +
                        "(${stream.format.summary})",
                )
                return null
            }
            val samples = FloatArray(total)
            var at = 0
            for (block in out) {
                block.copyInto(samples, at)
                at += block.size
            }
            if (decoder.outputFormat.sampleRate == rate.toInt()) samples else {
                MelSpectrogram.resample(samples, decoder.outputFormat.sampleRate.toDouble(), rate).also {
                    if (it == null || it.isEmpty()) {
                        DesktopTrackLog.log(
                            "automix: resampling ${stream.sourceId ?: "source"} audio to " +
                                "${rate.toInt()} Hz returned no samples",
                        )
                    }
                }
            }
        } finally {
            decoder.close()
        }
    }

    fun release() {
        released.set(true)
        pending.clear()
        worker.shutdownNow()
        // Inference/native reads may finish after an interrupt. Close only after their owner exits.
        Thread({
            if (worker.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                if (vocalsUsed) vocals.release()
                if (beatsUsed) beats.release()
            }
        }, "BitChord-Analysis-Cleanup").apply { isDaemon = true; start() }
        results.clear()
        running.clear()
        failed.clear()
        failedAt.clear()
    }

    internal companion object {

        /** How long a track that failed to be measured waits before it is tried again. */
        const val RETRY_AFTER_MS = 60_000L
        private const val NEUTRAL_VOCAL = 0.2
        private const val WINDOW_OVERLAP_SECONDS = 1.0

        /**
         * A ceiling of about twenty minutes at the analyser's rate, so a mis-tagged eight-hour
         * upload cannot eat the heap.
         */
        const val MAX_SAMPLES = 20 * 60 * 11_025

        /** Stack for the analysis thread. */
        const val ANALYSIS_STACK_BYTES = 64L * 1024 * 1024
    }
}

/** The queue facts the shared planner needs, from a [Song]. */
internal fun Song.transitionInfo(durationMs: Long) = com.music.bitchord.playback.smart.TransitionTrackInfo(
    id = videoId,
    durationMs = durationMs,
    title = title,
    artist = artist,
    album = albumName.orEmpty(),
    albumId = albumId.orEmpty(),
)

/** "3:27" as seconds, or zero when there is nothing to read. */
internal fun String?.durationSeconds(): Double {
    val parts = this?.split(':')?.mapNotNull { it.trim().toIntOrNull() } ?: return 0.0
    return when (parts.size) {
        2 -> (parts[0] * 60 + parts[1]).toDouble()
        3 -> (parts[0] * 3_600 + parts[1] * 60 + parts[2]).toDouble()
        else -> 0.0
    }
}
