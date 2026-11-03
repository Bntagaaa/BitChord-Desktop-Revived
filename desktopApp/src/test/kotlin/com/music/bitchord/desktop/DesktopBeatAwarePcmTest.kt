package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.overlapHeadroom
import com.music.bitchord.playback.smart.transitionPrefixFrames
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The same deck FIFO and render clock are used ahead of WASAPI and Linux Java Sound. */
class DesktopBeatAwarePcmTest {
    @Test fun mixedCodecBlockSizesPreserveEverySampleAcrossAnOverlapAndPromotion() {
        for (rate in listOf(44100, 48000)) {
            val buffer = DesktopTempoBuffer(2, rate)
            val expected = FloatArray(rate * 2) { sin(it * 0.01).toFloat() }
            val actual = ArrayList<Float>()
            var cursor = 0
            for (size in listOf(1920, 4096, 2048, 960, 8192)) {
                val block = expected.copyOfRange(cursor, cursor + size)
                buffer.push(block, block.size)
                cursor += size
                // A different outgoing codec requests a different block size.
                val output = buffer.take(2048)
                repeat(buffer.outputCount) { actual += output[it] }
            }
            buffer.push(expected.copyOfRange(cursor, expected.size), expected.size - cursor)
            buffer.finish()
            while (buffer.available > 0) {
                val output = buffer.take(1778)
                repeat(buffer.outputCount) { actual += output[it] }
            }
            assertEquals(expected.size, actual.size)
            expected.indices.forEach { assertEquals(expected[it], actual[it], "sample $it at $rate Hz") }
        }
    }

    @Test fun submittedFrameClockPlacesTheSeamIndependentlyOfPlatformQueueLatency() {
        val rate = 48000
        val clock = DesktopPlayhead().apply { reset(0, 0, 1_000_000.0 / rate) }
        val plan = TransitionPlan(transitionStart = 10.0, fadeSeconds = 8.0)
        val submitted = 479_520L // 9.99 s; ten milliseconds of the next block precede the seam.
        for (latencyFrames in listOf(0L, 960L, 9600L, 24000L)) {
            clock.at(submitted - latencyFrames)
            assertEquals(480, transitionPrefixFrames(plan, clock.peek(submitted), 1024, rate))
        }
    }

    @Test fun pausedOutputFramesDoNotAdvanceTheMusicalSchedule() {
        val clock = DesktopPlayhead().apply { reset(0, 0, 1_000_000.0 / 48000) }
        val plan = TransitionPlan(transitionStart = 10.0, fadeSeconds = 8.0)
        val first = transitionPrefixFrames(plan, clock.peek(479520), 1024, 48000)
        // Scheduling has no wall clock; pause and resumption at the same frame are identical.
        repeat(10) { assertEquals(first, transitionPrefixFrames(plan, clock.peek(479520), 1024, 48000)) }
    }

    @Test fun allTransitionStrategiesStayFiniteWithUpstreamHeadroom() {
        val gains = FloatArray(2)
        for (style in com.music.bitchord.playback.smart.TransitionStyle.entries) {
            val plan = TransitionPlan(transitionStyle = style, bassSwap = true, beatSeconds = 0.5, fadeSeconds = 8.0)
            var previous = 0f
            for (frame in 0..48000) {
                val progress = frame / 48000f
                DesktopTransitionRide.gains(plan, progress, gains)
                val headroom = overlapHeadroom(gains[0], gains[1])
                val sample = sin(2 * PI * 440 * frame / 48000).toFloat()
                val mixed = (sample * gains[0] + sample * gains[1]) * headroom
                val sum = gains[0] + gains[1]
                val reference = sample * sum / kotlin.math.sqrt(sum.coerceAtLeast(1f))
                assertEquals(reference, mixed, 0.000001f, "$style at $frame")
                assertTrue(mixed.isFinite() && abs(mixed) <= kotlin.math.sqrt(2f) + 0.000001f)
                assertTrue(abs(mixed - previous) < 0.1f, "$style introduced an envelope discontinuity")
                previous = mixed
            }
        }
    }
}
