package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.LoopMix
import kotlin.math.*

/** Repeat the source waveform over the mix's virtual timeline, using the renderer's exact clock. */
internal fun desktopLoopView(session: LoopMixSession, outgoing: Song, incoming: Song): MixTransitionView {
    val clock = session.clock
    val duration = clock.elementAt
    fun deck(song: Song, region: LoopRegion, side: LoopMix.Side, start: Double, bpm: Double): MixDeck {
        val bins = (region.left.size.toDouble() / session.sampleRate * 100).toInt().coerceAtLeast(1)
        val source = Array(3) { FloatArray(bins) }
        val lowA = (1 - exp(-2 * PI * 200 / session.sampleRate)).toFloat()
        val highA = (1 - exp(-2 * PI * 2500 / session.sampleRate)).toFloat()
        var l1 = 0f; var l2 = 0f; var h1 = 0f; var h2 = 0f
        region.left.indices.forEach { i ->
            val value = (region.left[i] + region.right[i]) * .5f
            l1 += lowA * (value - l1); l2 += lowA * (l1 - l2)
            h1 += highA * (value - h1); h2 += highA * (h1 - h2)
            val at = (i.toLong() * bins / region.left.size).toInt().coerceAtMost(bins - 1)
            source[0][at] = max(source[0][at], abs(l2))
            source[1][at] = max(source[1][at], abs(h2 - l2))
            source[2][at] = max(source[2][at], abs(value - h2))
        }
        val projected = Array(3) { FloatArray(512) }
        repeat(512) { bin ->
            repeat(16) sample@ { sample ->
                val time = (bin + sample / 16.0) / 512 * duration
                val track = LoopMix.positionAt(clock, side, time) ?: return@sample
                val index = ((track - region.start) * 100).toInt()
                if (index in 0 until bins) repeat(3) { band ->
                    projected[band][bin] = max(projected[band][bin], source[band][index])
                }
            }
        }
        return MixDeck(song.title, song.artist, song.thumbnailUrl, bpm, "", (song.durationText.durationSeconds() * 1000).toLong(),
            start, start + duration, projected[0], projected[1], projected[2])
    }
    fun interpolate(time: Double, x: DoubleArray, y: FloatArray): Float {
        if (time <= x.first()) return y.first()
        for (i in 1 until x.size) if (time <= x[i]) {
            val fraction = ((time - x[i - 1]) / (x[i] - x[i - 1]).coerceAtLeast(1e-9)).toFloat()
            return y[i - 1] + (y[i] - y[i - 1]) * fraction
        }
        return y.last()
    }
    val outStart = session.plan.out.loopStart
    val inStart = session.plan.inn.loopStart
    return MixTransitionView(
        deck(outgoing, session.outgoingRegion, LoopMix.Side.OUT, outStart, session.plan.out.bpm),
        deck(incoming, session.handoverRegion, LoopMix.Side.IN, inStart, session.plan.inn.bpm),
        outStart, outStart + duration, inStart, inStart + duration, clock.beats,
        clock.beatTimes.filter { it in 0.0..duration }.map { (it / duration).toFloat() }.toFloatArray(),
        FloatArray(129) { interpolate(it / 128.0 * duration,
            doubleArrayOf(0.0, clock.enterEnd, clock.rampEnd, clock.releaseAt), floatArrayOf(1f, 1f, .6f, 0f)) },
        FloatArray(129) { interpolate(it / 128.0 * duration,
            doubleArrayOf(0.0, clock.enterEnd, clock.rampEnd), floatArrayOf(0f, .55f, 1f)) },
        (clock.handoffAt / duration).toFloat(),
    )
}
