package com.music.bitchord.reworked

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

data class MixUiState(
    val analysis: SmartAnalysis = SmartAnalysis(),
    val window: TransitionWindow? = null,
    val preview: MixTransitionView? = null,
    val view: MixTransitionView? = null,
    val blend: MixBlend? = null,
    val detail: String? = null,
)

internal fun mixView(
    outgoing: Song,
    incoming: Song,
    plan: TransitionPlan,
    outAnalysis: TrackAnalysis,
    inAnalysis: TrackAnalysis,
    outDuration: Long,
    inDuration: Long,
    advanced: Boolean = false,
): MixTransitionView {
    val inEnd = plan.incomingCueTime + plan.fadeSeconds * plan.incomingPlaybackRate / plan.outgoingPlaybackRate
    fun deck(song: Song, analysis: TrackAnalysis, duration: Long, from: Double, to: Double) = MixDeck(
        song.title, song.artist, song.thumbnailUrl, analysis.bpm.takeIf { analysis.isUsable } ?: 0.0,
        analysis.key.takeIf { analysis.isUsable }.orEmpty(), duration,
        (from - 2).coerceAtLeast(0.0), (to + 2).let { if (duration > 0) it.coerceAtMost(duration / 1_000.0) else it },
        analysing = !analysis.isUsable,
    )
    val marks = if (plan.beatSeconds > 0 && plan.fadeSeconds > 0) {
        val anchor = outAnalysis.downbeats.lastOrNull { it <= plan.transitionStart } ?: outAnalysis.firstBeat
        val first = anchor + ceil((plan.transitionStart - anchor) / plan.beatSeconds) * plan.beatSeconds
        generateSequence(first) { it + plan.beatSeconds }.takeWhile { it <= plan.transitionEnd + 1e-6 }
            .map { ((it - plan.transitionStart) / plan.fadeSeconds).toFloat() }.toList().toFloatArray()
    } else FloatArray(0)
    val render = MobileMixRender(plan, advanced)
    fun faders(incomingSide: Boolean) = FloatArray(65) { index ->
        val p = index / 64f
        val outGain = render.fall(p)
        val inGain = render.rise(p)
        (if (incomingSide) inGain else outGain) / sqrt((render.level(p, plan.fadeMs.toFloat()) + inGain).coerceAtLeast(1f))
    }
    return MixTransitionView(
        deck(outgoing, outAnalysis, outDuration, plan.transitionStart, plan.transitionEnd),
        deck(incoming, inAnalysis, inDuration, plan.incomingCueTime, inEnd),
        plan.transitionStart, plan.transitionEnd, plan.incomingCueTime, inEnd,
        plan.transitionBeats, marks, faders(false), faders(true),
        bassSwapAt = if (plan.bassSwap) plan.bassSwapFraction.toFloat() else -1f,
    )
}

/** Fresh decoder boundary for mobile MixWaveform's original three-band peak envelopes. */
internal fun waveform(source: PlayableStream, deck: MixDeck, cancelled: () -> Boolean): MixDeck {
    val bins = 480
    val low = FloatArray(bins)
    val mid = FloatArray(bins)
    val high = FloatArray(bins)
    val rate = 44_100
    val span = deck.waveEnd - deck.waveStart
    if (span <= 0) return deck.withDecoding(false)
    val warmStart = (deck.waveStart - 0.05).coerceAtLeast(0.0)
    val lowCoefficient = (1 - exp(-2 * PI * 200 / rate)).toFloat()
    val highCoefficient = (1 - exp(-2 * PI * 2_500 / rate)).toFloat()
    var l1 = 0f; var l2 = 0f; var h1 = 0f; var h2 = 0f
    var sample = 0L
    MobileAudioDecoder(source, cancelled).use { audio ->
        audio.open()
        audio.configureOutput(AudioFormat(rate.toFloat(), 32, 1, true, false))
        if (warmStart > 0) audio.seek((warmStart * 1_000).toLong())
        while (!cancelled() && warmStart + sample.toDouble() / rate < deck.waveEnd) {
            val pcm = audio.read() ?: break
            val values = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            while (values.hasRemaining()) {
                val x = values.get() / 2_147_483_648f
                l1 += lowCoefficient * (x - l1); l2 += lowCoefficient * (l1 - l2)
                h1 += highCoefficient * (x - h1); h2 += highCoefficient * (h1 - h2)
                val at = (warmStart + sample++.toDouble() / rate - deck.waveStart) * bins / span
                if (at < 0) continue
                val bin = at.toInt()
                if (bin >= bins) break
                low[bin] = maxOf(low[bin], abs(l2))
                mid[bin] = maxOf(mid[bin], abs(h2 - l2))
                high[bin] = maxOf(high[bin], abs(x - h2))
            }
        }
    }
    fun normalize(values: FloatArray, ceiling: Float) {
        val positive = values.filter { it > 0 }.sorted()
        if (positive.isEmpty()) return
        val reference = positive[(positive.size * 0.97).toInt().coerceAtMost(positive.lastIndex)]
        if (reference <= 1e-6f) return
        for (index in values.indices) values[index] = (values[index] / reference * ceiling).coerceAtMost(ceiling * 1.08f)
    }
    normalize(low, 1f); normalize(mid, 0.82f); normalize(high, 0.5f)
    return deck.withWave(low, mid, high, deck.waveStart, deck.waveEnd).withDecoding(false)
}
