package com.music.bitchord.reworked

import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.data.innertube.StreamResolver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import kotlin.math.pow
import kotlin.math.roundToInt

/** JVM PCM boundary around the original mobile Float32 transition filter. */
internal class MobileMixDsp(private val format: AudioFormat, mediaId: String) {
    val filter = MobileTransitionFilter().apply { configure(format.sampleRate.toInt(), format.channels) }
    private val spatial = MobileSpatialAudio().apply { configure(format.sampleRate.toInt(), format.channels) }
    private val equalizer = MobileEqualizer().apply { configure(format.sampleRate.toInt(), format.channels) }
    private val loudness = MobileLoudness().apply {
        configure(format.sampleRate.toInt(), format.channels)
        track(mediaId, null)
        gainFor = { id ->
            if (MobileDspSettings.state.value.loudness) {
                StreamResolver.loudnessDbFor(id)?.let { db ->
                    val gainMb = (-db * 100).roundToInt().coerceIn(-1_500, 300)
                    10.0.pow(gainMb / 2_000.0).toFloat()
                } ?: 1f
            } else 1f
        }
    }
    private var settings: DspSettings? = null
    private var block = MobileAudioBlock(format.channels)

    fun process(bytes: ByteArray) {
        val current = MobileDspSettings.state.value
        if (current !== settings) {
            settings = current
            spatial.enabled = current.spatial
            equalizer.setTuning(current.equalizerEnabled, MobileDspSettings.curve, current.balance)
        }
        loudness.holdLimiter = equalizer.isEnabled || equalizer.isShaping || spatial.enabled || filter.isProcessing
        if (!filter.isProcessing && !spatial.enabled && !equalizer.isEnabled && !equalizer.isShaping && !loudness.isProcessing) return
        val frames = bytes.size / format.frameSize
        if (frames == 0) return
        if (block.capacityFrames < frames) block = MobileAudioBlock(format.channels, frames)
        block.reset(frames)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val wide = format.sampleSizeInBits > 16
        for (index in 0 until block.sampleCount) {
            block.samples[index] = if (wide) buffer.getInt(index * 4) / 2_147_483_648f else buffer.getShort(index * 2) / 32_768f
        }
        spatial.process(block)
        equalizer.process(block)
        filter.process(block)
        loudness.holdLimiter = equalizer.isShaping || spatial.enabled || filter.isProcessing
        loudness.process(block)
        for (index in 0 until block.sampleCount) {
            val sample = block.samples[index]
            if (wide) buffer.putInt(index * 4, (sample.toDouble() * 2_147_483_648.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
            else buffer.putShort(index * 2, (sample * 32_768).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }

    /** Loop decks remain Float32 through the whole mobile DSP chain, preserving mix headroom. */
    fun process(samples: FloatArray, frames: Int) {
        val current = MobileDspSettings.state.value
        if (current !== settings) {
            settings = current
            spatial.enabled = current.spatial
            equalizer.setTuning(current.equalizerEnabled, MobileDspSettings.curve, current.balance)
        }
        if (block.capacityFrames < frames) block = MobileAudioBlock(format.channels, frames)
        block.reset(frames)
        samples.copyInto(block.samples, endIndex = frames * format.channels)
        spatial.process(block); equalizer.process(block); filter.process(block)
        loudness.holdLimiter = true
        loudness.process(block)
        block.samples.copyInto(samples, endIndex = frames * format.channels)
    }

    fun reset() { spatial.flush(); equalizer.flush(); filter.reset(); loudness.flush() }

    /** Preserve spatial/EQ history, changing normalization at the exact gapless sample boundary. */
    fun nextTrack(mediaId: String) {
        loudness.nextMediaId = mediaId
        loudness.onStreamBoundary()
        loudness.nextMediaId = null
    }
}

/** Classic mobile CrossfadeController filter rides, evaluated on the same progress as its faders. */
internal fun rideMobileMix(plan: TransitionPlan, progress: Double, outgoing: MobileTransitionFilter?, incoming: MobileTransitionFilter?) {
    val p = progress.coerceIn(0.0, 1.0)
    val clash = plan.vocalOverlap.coerceIn(0.0, 1.0)
    val open = MobileTransitionFilter.OPEN_HZ.toDouble()
    val off = MobileTransitionFilter.OFF_HZ.toDouble()
    fun glide(from: Double, to: Double, amount: Double) = from * (to / from).pow(amount.coerceIn(0.0, 1.0))
    fun bass(amount: Double) = glide(off, 200.0, amount)
    fun entry(amount: Double, top: Double, openBy: Double) = glide(off, top, amount * (1.0 - p / openBy).coerceIn(0.0, 1.0).pow(0.35))
    fun apply(outLow: Double = open, outHigh: Double = off, inLow: Double = open, inHigh: Double = off) {
        outgoing?.setCutoffs(outLow.toFloat(), outHigh.toFloat())
        incoming?.setCutoffs(inLow.toFloat(), inHigh.toFloat())
    }
    when (plan.transitionStyle) {
        TransitionStyle.GAPLESS -> apply()
        TransitionStyle.EQUAL_POWER -> apply(
            outLow = glide(open, glide(open, 1_600.0, clash), p.pow(0.75)),
            inHigh = entry(clash, 700.0, 0.6),
        )
        TransitionStyle.DJ_BLEND -> if (plan.bassSwap) {
            val handover = ((p - (plan.bassSwapFraction - 0.1)) / 0.2).coerceIn(0.0, 1.0)
            val exitFrom = 0.3 + (0.12 - 0.3) * clash
            apply(
                outLow = glide(open, glide(2_200.0, 1_100.0, clash), (p - exitFrom) / (1 - exitFrom)),
                outHigh = bass(handover),
                inHigh = maxOf(bass(1 - handover), entry(1.0, glide(520.0, 950.0, clash), 0.45 + 0.25 * clash)),
            )
        } else apply(
            outLow = glide(open, glide(open, 1_600.0, clash), p.pow(0.75)),
            inHigh = entry(clash, 700.0, 0.6),
        )
        TransitionStyle.DJ_FILTER -> {
            val swap = when (plan.filterVariant) {
                FilterTransitionVariant.SWEEP -> 0.5
                FilterTransitionVariant.BASS_HANDOFF -> 0.43
                FilterTransitionVariant.ECHO_RIDE -> 0.58
            }
            val handover = ((p - (swap - 0.2)) / 0.2).coerceIn(0.0, 1.0)
            if (plan.filterVariant == FilterTransitionVariant.BASS_HANDOFF) {
                val exit = ((p - swap) / (1 - swap)).coerceIn(0.0, 1.0)
                apply(
                    outLow = minOf(glide(open, 1_600.0, exit.pow(0.8)), glide(open, 1_600.0, clash * p.pow(0.55))),
                    outHigh = bass(handover),
                    inHigh = maxOf(bass(1 - handover), entry(1.0, glide(900.0, 2_400.0, clash), 0.38 + 0.4 * clash)),
                )
            } else {
                val sweep = plan.filterSweep.coerceIn(0.0, 1.0)
                if (sweep == 0.0) apply() else apply(
                    outLow = glide(glide(open, 7_000.0, sweep), glide(open, 300.0, sweep), p.pow(0.75)),
                    outHigh = bass(handover),
                    inHigh = maxOf(bass(1 - handover), entry(sweep, glide(1_200.0, 2_400.0, clash), 0.6 + 0.18 * clash)),
                )
            }
        }
    }
}
