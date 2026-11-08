package com.music.bitchord.reworked

import com.music.bitchord.playback.loopmix.LoopRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import kotlin.math.ceil

/** Exact trimmed stereo region supplied to the unchanged mobile native loop renderer. */
internal fun loopRegion(source: PlayableStream, from: Double, to: Double, rate: Int, cancelled: () -> Boolean): LoopRegion? {
    val start = from.coerceAtLeast(0.0)
    if (to <= start || to - start > 180 || cancelled()) return null
    MobileAudioDecoder(source, cancelled).use { audio ->
        audio.open()
        audio.configureOutput(AudioFormat(rate.toFloat(), 32, 2, true, false))
        if (start > 0) audio.seek((start * 1_000).toLong())
        val wanted = ceil((to - start) * rate).toInt()
        val left = FloatArray(wanted)
        val right = FloatArray(wanted)
        var size = 0
        while (size < wanted && !cancelled()) {
            val bytes = audio.read() ?: break
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            val count = minOf(input.remaining() / 2, wanted - size)
            repeat(count) {
                left[size] = input.get() / 2_147_483_648f
                right[size] = input.get() / 2_147_483_648f
                size++
            }
        }
        if (cancelled() || size < rate) return null
        return LoopRegion(left.copyOf(size), right.copyOf(size), start)
    }
}
