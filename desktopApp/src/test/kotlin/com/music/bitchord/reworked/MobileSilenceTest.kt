package com.music.bitchord.reworked

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileSilenceTest {
    private val format = AudioFormat(8_000f, 16, 1, true, false)

    @Test fun restsShorterThanOneSecondRemainByteForByteUnchanged() {
        val audio = pcm(800, 10_000) + pcm(7_000, 100) + pcm(800, 10_000)
        val (result, skipped) = process(audio)
        assertEquals(0, skipped)
        assertContentEquals(audio, result)
    }

    @Test fun threeSecondsOfSilenceKeepsTwentyPercentAndDatesRemovedTime() {
        val audio = pcm(800, 10_000) + pcm(24_000, 0) + pcm(800, 10_000)
        val (result, skipped) = process(audio)
        assertEquals(19_200, skipped)
        assertEquals((800 + 4_800 + 800) * 2, result.size)
        assertContentEquals(pcm(800, 10_000), result.copyOfRange(0, 1_600))
        assertContentEquals(pcm(800, 10_000), result.takeLast(1_600).toByteArray())
    }

    @Test fun longSilenceCapsRetentionAtTwoSecondsAndFlushesItsClockAtEof() {
        val (result, skipped) = process(pcm(96_000, 0))
        assertEquals(80_000, skipped)
        assertEquals(16_000 * 2, result.size)
        assertTrue(result.all { it == 0.toByte() })
    }

    @Test fun disabledSilenceSkippingReturnsQuietPcmUnchanged() {
        val audio = pcm(6_000, 100)
        val skipper = MobileSilence(format)
        var read = 0
        val first = skipper.next(false, 1f) { if (read++ == 0) audio else null }
        assertContentEquals(audio, first?.bytes)
        assertEquals(0L, first?.skipBeforeFrames)
    }

    private fun process(audio: ByteArray): Pair<ByteArray, Int> {
        val processor = MobileSilence(format)
        val output = ByteArrayOutputStream()
        var position = 0
        var skipped = 0
        while (true) {
            val packet = processor.next(true, 1f) {
                if (position >= audio.size) null else {
                    val next = minOf(position + 1_026, audio.size)
                    audio.copyOfRange(position, next).also { position = next }
                }
            } ?: break
            output.write(packet.bytes)
            skipped += packet.skipBeforeFrames.toInt()
        }
        return output.toByteArray() to skipped
    }

    private fun pcm(frames: Int, sample: Int): ByteArray = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(frames) { putShort(sample.toShort()) }
    }.array()
}
