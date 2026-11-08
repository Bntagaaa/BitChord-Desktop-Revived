package com.music.bitchord.reworked

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fresh PCM boundary for the mobile service's conservative silence policy: at least one second,
 * Media3's 20% retention capped at two seconds, threshold 1024/32768 and a 10% quiet floor.
 * A short musical rest is returned byte-for-byte; discarded time is dated at its output boundary.
 */
internal class MobileSilence(private val format: AudioFormat) {
    class Packet(val bytes: ByteArray, val skipBeforeFrames: Long = 0)
    private val pending = ArrayDeque<Packet>()
    private val quiet = ByteArrayOutputStream()
    private var chunk: ByteArray? = null
    private var offset = 0
    private var ended = false
    private var shortening = false
    private var kept = 0
    private var skipped = 0L
    private val frameSize = format.frameSize

    fun reset() {
        pending.clear(); quiet.reset(); chunk = null; offset = 0
        ended = false; shortening = false; kept = 0; skipped = 0
    }

    fun next(enabled: Boolean, speed: Float, read: () -> ByteArray?): Packet? {
        pending.removeFirstOrNull()?.let { return it }
        val minimum = (format.sampleRate / speed.coerceIn(0.5f, 2f)).roundToInt().coerceAtLeast(1)
        while (pending.isEmpty()) {
            if (!enabled && quiet.size() > 0) {
                emit(quiet.toByteArray()); quiet.reset(); shortening = false; kept = 0
                break
            }
            if (chunk == null) {
                if (!ended) { chunk = read(); offset = 0; if (chunk == null) ended = true }
                if (ended) {
                    flushQuiet(minimum, final = true)
                    if (skipped > 0) emit(ByteArray(0))
                    return pending.removeFirstOrNull()
                }
            }
            val input = checkNotNull(chunk)
            if (!enabled) {
                emit(input.copyOfRange(offset, input.size)); chunk = null; offset = 0
                break
            }
            val samples = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
            val silent = silentFrame(samples, offset)
            var end = offset + frameSize
            while (end < input.size && silentFrame(samples, end) == silent) end += frameSize
            if (silent) {
                val capacity = minimum * frameSize - quiet.size()
                val count = minOf(end - offset, capacity)
                quiet.write(input, offset, count)
                offset += count
                if (quiet.size() >= minimum * frameSize) flushQuiet(minimum, final = false)
            } else {
                flushQuiet(minimum, final = true)
                shortening = false; kept = 0
                emit(input.copyOfRange(offset, end)); offset = end
            }
            if (offset >= input.size) { chunk = null; offset = 0 }
        }
        return pending.removeFirstOrNull()
    }

    private fun flushQuiet(minimum: Int, final: Boolean) {
        if (quiet.size() == 0) return
        val bytes = quiet.toByteArray()
        quiet.reset()
        val frames = bytes.size / frameSize
        if (!shortening && frames < minimum) { emit(bytes); return }
        val first = !shortening
        shortening = true
        val retain = minOf((frames * 0.2).roundToInt(), (minimum * 2 - kept).coerceAtLeast(0))
        if (retain == 0) {
            skipped += frames
            emit(ByteArray(0))
            return
        }
        // Preserve the leading edge at entry and the trailing edge at exit, with quiet-floor fades.
        val at = if (final) frames - retain else 0
        skipped += at
        val keptAudio = bytes.copyOfRange(at * frameSize, (at + retain) * frameSize)
        scalePcm(keptAudio, format.sampleSizeInBits, format.channels,
            if (first) 1f else 0.1f, if (final) 1f else 0.1f)
        emit(keptAudio)
        skipped += frames - retain - at
        kept += retain
    }

    private fun emit(bytes: ByteArray) {
        pending.addLast(Packet(bytes, skipped))
        skipped = 0
    }

    private fun silentFrame(pcm: ByteBuffer, offset: Int): Boolean {
        val wide = format.sampleSizeInBits > 16
        repeat(format.channels) { channel ->
            val sample = if (wide) pcm.getInt(offset + channel * 4).toLong() else pcm.getShort(offset + channel * 2).toLong()
            val threshold = if (wide) 67_108_864 else 1_024
            if (abs(sample) > threshold) return false
        }
        return true
    }
}
