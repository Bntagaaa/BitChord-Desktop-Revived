package com.music.bitchord.reworked

import com.music.bitchord.playback.manualCurve
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class MobileDspTest {
    @Test fun mobileEqualizerAndSpatialBypassLeaveSamplesUntouched() {
        val block = tone(880.0, channels = 1)
        val original = block.samples.copyOf()
        MobileEqualizer().apply { configure(44_100, 1) }.process(block)
        MobileSpatialAudio().apply { configure(44_100, 1); enabled = true }.process(block)
        assertContentEquals(original, block.samples)
    }

    @Test fun manualBassShelfChangesLowBandMoreThanMidrange() {
        fun gain(frequency: Double): Double {
            val audio = tone(frequency)
            val before = rms(audio.samples)
            MobileEqualizer().apply {
                setTuning(true, manualCurve(listOf(6f, 0f, 0f, 0f, 0f, 0f, 0f)), 0f)
                configure(44_100, 1)
            }.process(audio)
            return rms(audio.samples) / before
        }
        assertTrue(gain(40.0) > gain(1_000.0) * 1.5)
    }

    @Test fun mobileTransitionLowPassActuallyRejectsHighFrequencies() {
        fun level(frequency: Double): Double {
            val audio = tone(frequency)
            MobileTransitionFilter().apply { setCutoffs(300f, 20f); configure(44_100, 1) }.process(audio)
            return rms(audio.samples)
        }
        assertTrue(level(8_000.0) < level(100.0) * 0.02)
    }

    @Test fun mobileLoudnessLimiterProtectsOutputAndOffIsTransparent() {
        val audio = tone(440.0)
        val untouched = audio.samples.copyOf()
        val loudness = MobileLoudness().apply { configure(44_100, 1); track("fixture", null) }
        loudness.process(audio)
        assertContentEquals(untouched, audio.samples)
        loudness.gainFor = { 8f }
        loudness.flush()
        loudness.process(audio)
        assertTrue(audio.samples.all { abs(it) <= 0.98501f })
        assertTrue(audio.samples.all(Float::isFinite))
    }

    private fun tone(frequency: Double, channels: Int = 1): MobileAudioBlock = MobileAudioBlock(channels, 44_100).apply {
        setFrameCount(44_100)
        repeat(frameCount) { frame -> repeat(channels) { channel -> samples[frame * channels + channel] = (0.4 * sin(2 * PI * frequency * frame / 44_100)).toFloat() } }
    }
    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
}
