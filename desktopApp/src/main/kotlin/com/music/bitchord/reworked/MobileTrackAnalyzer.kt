package com.music.bitchord.reworked

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * New JVM boundary for mobile TrackAnalyzer. The actual structure, beat, vocal and transition
 * algorithms are the current mobile shared implementations and the same bundled model weights.
 * FFmpeg supplies the mono full-track pass and stereo head/tail windows in place of MediaCodec.
 */
internal class MobileTrackAnalyzer(
    private val resolve: suspend (Song) -> PlayableStream = ::resolveMobileStream,
    private val allowed: () -> Boolean = { true },
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { work -> Thread(work, "Reworked-MobileAnalysis").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val closed = AtomicBoolean()
    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val retries = ConcurrentHashMap<String, Long>()
    private val store = AnalysisStore { DesktopStore.cache.resolve("mobile-analysis").toFile() }
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status = _status.asStateFlow()
    private val _unavailable = MutableStateFlow<String?>(null)
    val unavailable = _unavailable.asStateFlow()
    private val beats = BeatTracker({ MobileAnalysisResources.model(BeatTracker.MODEL_ASSET) }, { minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1) })
    private val vocals = VocalTracker({ MobileAnalysisResources.model(VocalTracker.MODEL_ASSET) }, { minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1) })
    @Volatile private var activeDecoder: MobileAudioDecoder? = null

    init { MobileAnalysisResources.install() }

    fun analysisFor(song: Song): TrackAnalysis {
        val key = key(song)
        return results[key] ?: store.load(key)?.copy(trackId = song.videoId)?.also { results[key] = it }
            ?: TrackAnalysis(trackId = song.videoId)
    }

    fun analysisState(song: Song): TrackAnalysisState {
        val key = key(song)
        val ready = analysisFor(song).isUsable
        return when {
            key in requested && ready -> TrackAnalysisState.REFINING
            key in requested -> TrackAnalysisState.ANALYSING
            ready -> TrackAnalysisState.ANALYSED
            _status.value[song.videoId]?.startsWith("Crossfade fallback") == true -> TrackAnalysisState.FAILED
            else -> TrackAnalysisState.WAITING
        }
    }

    fun request(song: Song) {
        if (closed.get() || !allowed() || song.isVideoOrigin) return
        val key = key(song)
        if (analysisFor(song).isUsable || retries.getOrDefault(key, 0) > System.currentTimeMillis() || !requested.add(key)) return
        _status.update { it + (song.videoId to "Queued for analysis") }
        scope.launch {
            try {
                if (!NativeAnalysisLibrary.available) {
                    retries[key] = Long.MAX_VALUE
                    _unavailable.value = "The mobile analysis library could not be loaded"
                    _status.update { it + (song.videoId to "Crossfade fallback: analysis unavailable") }
                    return@launch
                }
                checkActive()
                _status.update { it + (song.videoId to "Measuring track structure") }
                val source = resolve(song)
                val structural = structure(source)
                checkActive()
                var analysis = structural.features.toAnalysis(song.videoId)
                results[key] = analysis
                _status.update { it + (song.videoId to "Measuring beats and vocals") }
                val duration = structural.duration
                val span = (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
                val headEnd = minOf(duration, 2 * span - 1)
                val tailStart = maxOf(0.0, duration - (3 * span - 2))
                val regions = modelWindows(headEnd, tailStart, duration, span).map { (start, end) ->
                    checkActive()
                    region(source, start, end)
                }
                checkActive()
                val combined = seamBeats(regions)
                val head = beatSide(combined, 0.0, headEnd)
                val tail = beatSide(combined, tailStart, duration)
                val leading = tail ?: head
                val vocalWindows = regions.mapNotNull { it.vocals }
                analysis = analysis.copy(
                    bpm = leading?.bpm ?: analysis.bpm,
                    beatInterval = leading?.bpm?.takeIf { it > 0 }?.let { 60 / it } ?: analysis.beatInterval,
                    beatConfidence = leading?.confidence ?: analysis.beatConfidence,
                    firstBeat = head?.beats?.firstOrNull() ?: analysis.firstBeat,
                    downbeats = (head?.downbeats.orEmpty() + tail?.downbeats.orEmpty()).distinct().sorted().ifEmpty { analysis.downbeats },
                    headBeats = head,
                    tailBeats = tail,
                    vocalWindows = vocalWindows,
                    levelWindows = regions.mapNotNull { it.levels },
                    vocalActivityMask = analysis.energyCurve.mapIndexed { index, point ->
                        val measured = vocalWindows.mapNotNull { window ->
                            val sample = ((point.time - window.startTime) * window.frameRate).toInt()
                            window.values.getOrNull(sample)?.takeIf { point.time >= window.startTime }?.toDouble()
                        }
                        measured.maxOrNull() ?: heuristicVocalOnModelScale(analysis.vocalActivityMask.getOrElse(index) { 0.0 })
                    },
                )
                results[key] = analysis
                store.save(key, analysis)
                retries[key] = Long.MAX_VALUE
                _status.update { it + (song.videoId to if (leading != null) "Analyzed: beat and vocal models" else "Analyzed: DSP fallback") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                retries[key] = System.currentTimeMillis() + 60_000
                _status.update { it + (song.videoId to "Crossfade fallback: ${failure.message ?: "analysis failed"}") }
            } finally {
                requested.remove(key)
            }
        }
    }

    private fun checkActive() {
        if (closed.get() || !allowed()) throw CancellationException("Analysis stopped")
    }

    private data class Structural(val features: TrackFeatures.Features, val duration: Double)

    private fun structure(source: PlayableStream): Structural = decode(source, TrackFeatures.sampleRate.toInt(), 1, 0.0, null) { pcm, duration ->
        check(pcm.size / TrackFeatures.sampleRate >= duration - 2) { "The whole track was not available for analysis" }
        val features = TrackFeatures.analyze(pcm, duration) ?: error("The mobile analyzer could not measure this recording")
        Structural(features, duration)
    }

    private data class Region(
        val start: Double,
        val seconds: Double,
        val grid: BeatTracker.Grid?,
        val vocals: CurveWindow?,
        val levels: CurveWindow?,
    )

    private fun region(source: PlayableStream, start: Double, end: Double): Region = decode(source, 44_100, 2, start, end) { interleaved, _ ->
        val count = interleaved.size / 2
        val left = FloatArray(count) { interleaved[it * 2] }
        val right = FloatArray(count) { interleaved[it * 2 + 1] }
        val mono = FloatArray(count) { (left[it] + right[it]) * 0.5f }
        val modelInput = MelSpectrogram.resample(mono, 44_100.0, MelSpectrogram.sampleRate)
        checkActive()
        val grid = modelInput?.let { beats.track(it, start) }
        checkActive()
        val mask = vocals.track(left, right, 44_100.0)
        val frame = (44_100 / LEVEL_FRAME_RATE).toInt()
        val levels = FloatArray(count / frame) { index ->
            var power = 0.0
            var samples = 0
            var offset = index * frame
            while (offset < (index + 1) * frame) { power += mono[offset] * mono[offset]; samples++; offset += 4 }
            levelFromRms(sqrt(power / samples)).toFloat()
        }
        Region(start, count / 44_100.0, grid, mask?.let { CurveWindow(start, VocalSpectrogram.frameRate, it) }, CurveWindow(start, LEVEL_FRAME_RATE, levels))
    }

    private inline fun <T> decode(source: PlayableStream, rate: Int, channels: Int, start: Double, end: Double?, consume: (FloatArray, Double) -> T): T {
        MobileAudioDecoder(source, { closed.get() || !allowed() }).use { decoder ->
            activeDecoder = decoder
            try {
                decoder.open()
                val duration = decoder.durationMs / 1_000.0
                check(duration > 0 && duration <= 1_800) { "This recording has no bounded music-length timeline" }
                decoder.configureOutput(AudioFormat(rate.toFloat(), 32, channels, true, false))
                if (start > 0) decoder.seek((start * 1_000).toLong())
                val seconds = ((end ?: duration) - start).coerceAtLeast(0.0)
                val wanted = ceil(seconds * rate).toInt() * channels
                var samples = FloatArray(wanted + if (end == null) rate * channels * 2 else 0)
                var size = 0
                var ended = false
                while (size < samples.size) {
                    checkActive()
                    val bytes = decoder.read()
                    if (bytes == null) { ended = true; break }
                    val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                    val count = minOf(input.remaining(), samples.size - size)
                    repeat(count) { samples[size++] = input.get() / 2_147_483_648f }
                    if (end != null && size >= wanted) break
                }
                if (end == null && !ended) check(decoder.read() == null) { "The audio timeline changed while measuring the track" }
                samples = samples.copyOf(if (end == null) size else minOf(size, wanted))
                checkActive()
                return consume(samples, duration)
            } finally { activeDecoder = null }
        }
    }

    private data class Beats(val beats: List<Double>, val downbeats: List<Double>, val confidence: Double)

    /** Same midpoint seam policy as mobile: each overlap contributes one copy of a beat. */
    private fun seamBeats(regions: List<Region>): Beats {
        val measured = regions.filter { it.grid != null }.sortedBy { it.start }
        val all = ArrayList<Double>()
        val down = ArrayList<Double>()
        var weighted = 0.0
        for ((index, region) in measured.withIndex()) {
            val grid = requireNotNull(region.grid)
            val previous = measured.getOrNull(index - 1)
            val next = measured.getOrNull(index + 1)
            val from = previous?.let { (it.start + it.seconds + region.start) / 2 }?.takeIf { it > region.start } ?: Double.NEGATIVE_INFINITY
            val until = next?.let { (region.start + region.seconds + it.start) / 2 }?.takeIf { it < region.start + region.seconds } ?: Double.POSITIVE_INFINITY
            val kept = grid.beats.filter { it >= from && it < until }
            all += kept
            down += grid.downbeats.filter { it >= from && it < until }
            weighted += grid.beatConfidence * kept.size
        }
        return Beats(all.sorted(), down.sorted(), if (all.isEmpty()) 0.0 else weighted / all.size)
    }

    private fun beatSide(all: Beats, from: Double, to: Double): BeatSide? {
        val times = all.beats.filter { it >= from && it <= to }
        val bpm = BeatTracker.tempoFromBeats(times)
        return if (bpm <= 0) null else BeatSide(times, all.downbeats.filter { it >= from && it <= to }, bpm, all.confidence)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeDecoder?.abortNetwork()
        scope.cancel()
        // ORT sessions are released by their owner after any in-flight inference returns.
        executor.execute { beats.release(); vocals.release() }
        dispatcher.close()
    }

    private fun key(song: Song): String = song.videoId + if (song.localPath != null) ":${song.localDateModifiedSeconds ?: 0}:${song.durationText.orEmpty()}" else ""
}

/** The mobile model spans: two overlapping head windows and three tail windows. */
internal fun modelWindows(headEnd: Double, tailStart: Double, duration: Double, span: Double): List<Pair<Double, Double>> {
    require(span > 1)
    val sections = if (tailStart <= headEnd) listOf(0.0 to duration) else listOf(0.0 to headEnd, tailStart to duration)
    return buildList {
        for ((from, to) in sections) {
            val length = to - from
            if (length < 1) continue
            if (length <= span) { add(from to to); continue }
            val count = ceil((length - 1) / (span - 1) - 1e-6).toInt().coerceAtLeast(2)
            val stride = (length - span) / (count - 1)
            repeat(count) { val start = from + it * stride; add(start to minOf(to, start + span)) }
        }
    }
}

private fun TrackFeatures.Features.toAnalysis(id: String) = TrackAnalysis(
    status = TrackAnalysis.STATUS_READY, trackId = id, duration = duration,
    bpm = bpm, beatInterval = beatInterval, beatConfidence = beatConfidence,
    downbeats = downbeats, phraseBoundaries = phraseBoundaries, firstBeat = firstBeat,
    key = key, keyConfidence = keyConfidence, audibleStartTime = audibleStartTime,
    pickupTime = pickupTime, introEndTime = introEndTime, contentEndTime = contentEndTime,
    outroStartTime = outroStartTime, mixInTime = mixInTime, mixOutTime = mixOutTime,
    mixInCandidates = mixInCandidates, mixOutCandidates = mixOutCandidates,
    energyCurve = energyCurve, lowEnergyCurve = lowEnergyCurve,
    vocalActivityMask = vocalActivityMask, vocalProbability = vocalProbability,
)

private object MobileAnalysisResources {
    private val installed by lazy {
        NativeAnalysisLibrary.install {
            val windows = System.getProperty("os.name").startsWith("Windows", true)
            val platform = if (windows) "windows-x86_64" else "linux-x86_64"
            val file = if (windows) "bitchord_analysis.dll" else "libbitchord_analysis.so"
            val bytes = javaClass.classLoader.getResourceAsStream("native/$platform/$file")?.use { it.readBytes() }
                ?: error("Mobile analysis library is missing from this package")
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
            val directory = DesktopStore.cache.resolve("mobile-native").resolve(hash)
            Files.createDirectories(directory)
            val destination = directory.resolve(file)
            if (!Files.isRegularFile(destination) || Files.size(destination) != bytes.size.toLong()) Files.write(destination, bytes)
            System.load(destination.toAbsolutePath().toString())
        }
    }

    fun install() { installed }

    @Synchronized fun model(name: String): String {
        require(name in setOf(BeatTracker.MODEL_ASSET, VocalTracker.MODEL_ASSET))
        val directory = DesktopStore.cache.resolve("mobile-models-v1.9.1")
        Files.createDirectories(directory)
        val destination = directory.resolve(name)
        if (!Files.isRegularFile(destination) || Files.size(destination) == 0L) {
            val resource = javaClass.classLoader.getResourceAsStream("models/$name") ?: javaClass.classLoader.getResourceAsStream(name)
                ?: error("Mobile model $name is missing from this package")
            val temporary = Files.createTempFile(directory, "model-", ".part")
            try {
                resource.use { input -> Files.newOutputStream(temporary).use { input.copyTo(it) } }
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
        return destination.toAbsolutePath().toString()
    }
}
