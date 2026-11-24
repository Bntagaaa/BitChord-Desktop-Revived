package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.planTransition
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop half of Automix, end to end: a real file goes in, an analysis comes out, and the
 * shared planner makes a transition out of two of them.
 */
class DesktopAutomixTest {

    private val scratch = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = scratch.forEach { it.delete() }

    @Test
    fun `a decoded track is analysed and two analyses plan a transition`() = runBlocking {
        DesktopAnalysisRuntime.ensureStarted()
        if (!DesktopAnalysisRuntime.available) {
            println("analysis library unavailable — skipping")
            return@runBlocking
        }

        val analyzer = DesktopTrackAnalyzer()
        val seconds = 90.0
        val current = analyse(analyzer, "current", bpm = 120.0, seconds = seconds)
        val next = analyse(analyzer, "next", bpm = 124.0, seconds = seconds)

        assertTrue(current.isUsable, "the outgoing track was not analysed: ${current.status}")
        assertTrue(next.isUsable, "the incoming track was not analysed: ${next.status}")
        assertTrue(current.bpm > 0 && next.bpm > 0, "no tempo: ${current.bpm} / ${next.bpm}")
        for (measured in listOf(current, next)) {
            assertEquals(measured.downbeats.distinct().sorted(), measured.downbeats,
                "Overlapping head and tail grids must contribute each timestamp only once")
        }

        val song = Song("current", "Current", "Artist", null)
        val other = Song("next", "Next", "Artist", null)

        // Late in the outgoing track, which is when the engine asks.
        val plan = planTransition(
            analysis = current,
            nextAnalysis = next,
            currentTrack = song.transitionInfo((seconds * 1_000).toLong()),
            nextTrack = other.transitionInfo((seconds * 1_000).toLong()),
            currentTime = seconds - 8.0,
            duration = seconds,
            fadeSeconds = 6.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )

        assertTrue(!plan.blocked, "the planner refused the pair: ${plan.reason}")
        assertTrue(plan.fadeSeconds > 0, "a transition with no length")
        assertTrue(
            plan.transitionEnd > plan.transitionStart,
            "transition runs backwards: ${plan.transitionStart}..${plan.transitionEnd}",
        )
        analyzer.release()
    }

    @Test
    fun `optional loop-region failures do not discard a usable DSP analysis`() = runBlocking {
        DesktopAnalysisRuntime.ensureStarted()
        if (!DesktopAnalysisRuntime.available) {
            println("analysis library unavailable — skipping")
            return@runBlocking
        }

        val analyzer = DesktopTrackAnalyzer(
            loopRegion = { _, _, _, _, _ ->
                throw IllegalStateException("simulated optional range-read failure")
            },
        )
        try {
            val measured = analyse(
                analyzer,
                id = "optional-region-${System.nanoTime()}",
                bpm = 120.0,
                seconds = 90.0,
            )
            assertTrue(measured.isUsable, "an optional loop-region failure discarded the DSP analysis")
            assertTrue(measured.bpm > 0, "the DSP tempo estimate was lost")
        } finally {
            analyzer.release()
        }
    }

    @Test
    fun `with no analysis the planner still answers, which is what keeps a plain fade working`() {
        val song = Song("a", "A", "Artist", null)
        val other = Song("b", "B", "Artist", null)

        val plan = planTransition(
            currentTrack = song.transitionInfo(180_000),
            nextTrack = other.transitionInfo(180_000),
            currentTime = 176.0,
            duration = 180.0,
            fadeSeconds = 6.0,
            mode = CrossfadeMode.STANDARD,
        )

        // The engine now routes both modes through the planner, so an unanalysed pair on the manual
        // crossfade slider has to keep behaving.
        assertTrue(plan.shouldStart, "a standard crossfade did not start at the end of the track")
        assertTrue(plan.fadeSeconds > 0)
    }

    @Test
    fun `an analysis survives a restart`() = runBlocking {
        DesktopAnalysisRuntime.ensureStarted()
        if (!DesktopAnalysisRuntime.available) return@runBlocking

        // Named after the track, so every run measures one the cache has never seen. A fixed id
        // would pass on the strength of the file the previous run left behind.
        val seconds = 60.0
        val file = clickTrackFile(120.0, seconds)
        val id = "cache-probe-${file.name}"
        // Wait for the completion callback before checking disk restoration with a fresh analyzer.
        // The callback runs after the persistence attempt; publication ordering is tested below.
        val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val first = DesktopTrackAnalyzer(onAnalysed = { observed ->
            if (observed == id) persisted.complete(Unit)
        })
        try {
            first.request(Song(id, id, "Artist", null), DesktopStream(url = file.absolutePath), seconds)
            kotlinx.coroutines.withTimeout(120_000L) { persisted.await() }
            val measured = first.analysisFor(id)
            assertTrue(measured.isUsable, "the probe track was not analysed")

            // A second analyzer has no in-memory results: loading must now
            // succeed from disk, not from the first analyzer's results map.
            val second = DesktopTrackAnalyzer()
            try {
                assertTrue(!second.playbackAnalysisFor(id).isUsable)
                second.request(Song(id, id, "Artist", null), DesktopStream(url = file.absolutePath), seconds)
                kotlinx.coroutines.withTimeout(5_000L) {
                    while (!second.playbackAnalysisFor(id).isUsable) kotlinx.coroutines.delay(5)
                }
                assertTrue(second.measurementFor(id)?.cacheHit == true)
                assertEquals(measured.bpm, second.playbackAnalysisFor(id).bpm, 0.001)
            } finally {
                second.release()
            }
        } finally {
            first.release()
        }
    }


    @Test
    fun `persistence and observer failures cannot stop later track analysis`() = runBlocking {
        DesktopAnalysisRuntime.ensureStarted()
        assertTrue(DesktopAnalysisRuntime.available, "this regression requires the packaged native analyser")
        val publishedBeforePersistence = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var analyzer: DesktopTrackAnalyzer
        analyzer = DesktopTrackAnalyzer(
            persistResult = { trackId, _ ->
                if (analyzer.isAnalysed(trackId)) publishedBeforePersistence.set(true)
                throw java.io.IOException("simulated full cache disk")
            },
            onAnalysed = { throw IllegalStateException("simulated observer failure") },
            // Exercise the real decoder and DSP without expensive optional inference here.
            loopRegion = { _, _, _, _, _ -> null },
        )
        try {
            repeat(3) { index ->
                val id = "analysis-recovery-$index-${System.nanoTime()}"
                assertTrue(analyse(analyzer, id, 120.0, 55.0).isUsable)
                assertTrue(!publishedBeforePersistence.get(), "analysis was published before persistence completed")
                repeat(100) {
                    if (analyzer.stateFor(id) == com.music.bitchord.data.settings.TrackAnalysisState.REFINING)
                        kotlinx.coroutines.delay(10)
                }
                assertEquals(com.music.bitchord.data.settings.TrackAnalysisState.ANALYSED, analyzer.stateFor(id))
            }
        } finally { analyzer.release() }
    }


    @Test
    fun `profile cold and cached AutoMix analysis`() = runBlocking {
        val enabled = System.getenv("BITCHORD_AUTOMIX_BENCHMARK") == "1"
        org.junit.Assume.assumeTrue("Set BITCHORD_AUTOMIX_BENCHMARK=1 to measure", enabled)
        assertTrue(DesktopAnalysisRuntime.available)
        val fixtures = listOf(
            clickTrackFile(120.0, 65.0) to 65.0,
            clickTrackFile(124.0, 65.0) to 65.0,
            clickTrackFile(172.0, 65.0) to 65.0,
        ) + System.getenv("BITCHORD_AUTOMIX_AUDIO_FIXTURES").orEmpty()
            .split(File.pathSeparatorChar).filter(String::isNotBlank).map { entry ->
                val file = File(entry).absoluteFile
                assertTrue(file.isFile, file.path)
                val probe = DesktopAudioDecoder()
                val duration = try {
                    probe.open(file.path, requested = DesktopPcmFormat(44_100, 2, 4, isFloat = true)).getOrThrow()
                    requireNotNull(probe.durationUs) / 1_000_000.0
                } finally {
                    probe.close()
                }
                file to duration
            }
        val ids = fixtures.indices.map { "automix-benchmark-${System.nanoTime()}-$it" }
        val ready = kotlinx.coroutines.channels.Channel<String>(fixtures.size)
        val analyzer = DesktopTrackAnalyzer(onAnalysed = { ready.trySend(it) })
        val cold = mutableListOf<com.music.bitchord.playback.smart.TrackAnalysis>()
        val pairStart = System.nanoTime()
        try {
            fixtures.forEachIndexed { index, (file, duration) ->
                val id = ids[index]
                val start = System.nanoTime()
                analyzer.request(Song(id, if (index < 3) "click-$index" else file.name, "benchmark", null),
                    DesktopStream(file.absolutePath), duration)
                assertEquals(id, kotlinx.coroutines.withTimeout(180_000) { ready.receive() })
                val result = analyzer.analysisFor(id)
                println("AUTOMIX_ANALYSIS fixture=$index cold_ms=${(System.nanoTime() - start) / 1e6} " +
                    "bpm=${result.bpm} confidence=${result.beatConfidence} downbeats=${result.downbeats.size}")
                cold += result
                if (index == 1) {
                    val plan = planTransition(cold[0], cold[1],
                        Song(ids[0], "first", "benchmark", null).transitionInfo(65_000),
                        Song(ids[1], "next", "benchmark", null).transitionInfo(65_000),
                        currentTime = 50.0, duration = 65.0, fadeSeconds = 6.0,
                        mode = CrossfadeMode.SMART, advanced = true)
                    assertTrue(plan.fadeSeconds > 0)
                    println("AUTOMIX_PLAN first_pair_ms=${(System.nanoTime() - pairStart) / 1e6} " +
                        "style=${plan.transitionStyle} cue_s=${plan.incomingCueTime}")
                }
            }
        } finally {
            analyzer.release()
        }
        val cache = DesktopTrackAnalyzer()
        try {
            ids.forEachIndexed { index, id ->
                val start = System.nanoTime()
                val restored = cache.analysisFor(id)
                println("AUTOMIX_ANALYSIS fixture=$index disk_cache_ms=${(System.nanoTime() - start) / 1e6}")
                assertEquals(cold[index].bpm, restored.bpm, 0.001)
            }
        } finally {
            cache.release()
        }
    }

    private suspend fun analyse(
        analyzer: DesktopTrackAnalyzer,
        id: String,
        bpm: Double,
        seconds: Double,
    ): com.music.bitchord.playback.smart.TrackAnalysis {
        val file = clickTrackFile(bpm, seconds)
        analyzer.request(
            song = Song(id, id, "Artist", null),
            stream = DesktopStream(url = file.absolutePath),
            durationSeconds = seconds,
        )
        // Analysis is deliberately off the caller's thread; wait for it the way the engine does not
        // have to — it simply reads whatever is ready.
        repeat(600) {
            if (analyzer.isAnalysed(id)) return analyzer.analysisFor(id)
            kotlinx.coroutines.delay(100)
        }
        return analyzer.analysisFor(id)
    }

    /** A metronome at [bpm], written as a WAV the decoder will read. */
    private fun clickTrackFile(bpm: Double, seconds: Double): File {
        val rate = 44_100
        val total = (seconds * rate).toInt()
        val bytes = ByteArray(total * 2)
        val period = (60.0 / bpm * rate).toInt()
        var start = 0
        while (start < total) {
            val length = minOf(period / 4, total - start)
            for (index in 0 until length) {
                val decay = exp(-index / (rate * 0.02))
                val value = (sin(2.0 * PI * 1_000.0 * index / rate) * decay * 26_000).toInt().toShort()
                val at = (start + index) * 2
                bytes[at] = value.toInt().toByte()
                bytes[at + 1] = (value.toInt() shr 8).toByte()
            }
            start += period
        }
        val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
        val file = File.createTempFile("bitchord-automix", ".wav").also { scratch += it }
        AudioInputStream(ByteArrayInputStream(bytes), format, total.toLong()).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, file)
        }
        return file
    }
}
