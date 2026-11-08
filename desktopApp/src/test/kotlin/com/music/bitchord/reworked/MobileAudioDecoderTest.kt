package com.music.bitchord.reworked

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.sound.sampled.AudioFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real decoder/DSP checks: no sound device, network or account is required. */
class MobileAudioDecoderTest {
    @Test fun decodeConvertsChannelsAndSampleRate() = withTone { path ->
        MobileAudioDecoder(PlayableStream(path), { false }).use { decoder ->
            decoder.open()
            assertEquals(44_100, decoder.sampleRate)
            assertEquals(1, decoder.channels)
            assertTrue(decoder.durationMs in 1_999..2_001)
            decoder.configureOutput(AudioFormat(48_000f, 16, 2, true, false))
            val pcm = collect(decoder)
            assertTrue(pcm.size / 4 in 95_800..96_100, "Output frame count was ${pcm.size / 4}")
            val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var peak = 0
            while (samples.hasRemaining()) peak = maxOf(peak, abs(samples.get().toInt()))
            assertTrue(peak > 5_000, "The decoded tone must remain audible")
        }
    }

    @Test fun seekDiscardsEarlierPcmAndKeepsTheRequestedTimeline() = withTone { path ->
        MobileAudioDecoder(PlayableStream(path), { false }).use { decoder ->
            decoder.open()
            decoder.configureOutput(AudioFormat(44_100f, 16, 1, true, false))
            decoder.seek(1_000)
            val pcm = collect(decoder)
            assertTrue(pcm.size / 2 in 43_900..44_200, "Seek returned ${pcm.size / 2} frames")
            val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var previous = 0
            var crossings = 0
            while (samples.hasRemaining()) {
                val next = samples.get().toInt()
                if (previous <= 0 && next > 0) crossings++
                previous = next
            }
            assertTrue(crossings in 875..885, "Seek should land in the 880 Hz section; got $crossings crossings")
        }
    }

    @Test fun speedChangesDurationWithoutRaisingPitchAndVolumeCanMute() = withTone { path ->
        MobileAudioDecoder(PlayableStream(path), { false }).use { decoder ->
            decoder.open()
            decoder.configureOutput(AudioFormat(44_100f, 16, 1, true, false))
            decoder.setControls(2f, 1f)
            decoder.seek(1_000)
            val pcm = collect(decoder)
            val frameCount = pcm.size / 2
            assertTrue(frameCount in 20_000..24_000, "2× playback returned $frameCount frames")
            val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var previous = 0
            var crossings = 0
            while (samples.hasRemaining()) {
                val next = samples.get().toInt()
                if (previous <= 0 && next > 0) crossings++
                previous = next
            }
            val frequency = crossings * 44_100.0 / frameCount
            assertTrue(frequency in 850.0..910.0, "Pitch changed to $frequency Hz")
            decoder.seek(0)
            decoder.setControls(1f, 0f)
            assertTrue(collect(decoder).all { it == 0.toByte() }, "Muted output contains non-zero samples")
        }
    }

    private fun collect(decoder: MobileAudioDecoder): ByteArray {
        val output = ByteArrayOutputStream()
        repeat(10_000) {
            val block = decoder.read() ?: return output.toByteArray()
            output.write(block)
        }
        error("Decoder did not reach the end of a two-second fixture")
    }

    private fun withTone(block: (String) -> Unit) {
        val samples = 44_100 * 2
        val dataBytes = samples * 2
        val wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(44_100).putInt(88_200)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataBytes)
        repeat(samples) { index ->
            val frequency = if (index < 44_100) 440.0 else 880.0
            wav.putShort((sin(2 * PI * frequency * index / 44_100) * 12_000).toInt().toShort())
        }
        val file = Files.createTempFile("reworked-mobile-audio-", ".wav")
        try {
            Files.write(file, wav.array())
            block(file.toString())
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
