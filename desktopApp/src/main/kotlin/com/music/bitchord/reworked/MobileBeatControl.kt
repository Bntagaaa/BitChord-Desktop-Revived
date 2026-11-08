package com.music.bitchord.reworked

import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Mobile CrossfadeController's local-bar beat measurement and bounded jog-wheel pulses. */
internal class MobilePhaseLock(private val out: TrackAnalysis, private val incoming: TrackAnalysis, private val plan: TransitionPlan) {
    private val errors = ArrayList<Double>(5)
    private var nudgeUntil = 0L
    private var quietUntil = 0L
    private var lastRead = 0L
    private var factor = 1f

    fun update(outMs: Long, inMs: Long, progress: Float, userSpeed: Float, now: Long): Float {
        val base = plan.incomingPlaybackRate.toFloat()
        if (nudgeUntil != 0L) {
            if (now < nudgeUntil) return base * factor
            nudgeUntil = 0L; factor = 1f; quietUntil = now + 400_000_000; errors.clear()
            return base
        }
        if (progress >= 0.9f || now < quietUntil || now - lastRead < 30_000_000) return base
        lastRead = now
        val outgoingBeat = mobileBeatAt(out, outMs / 1_000.0) ?: return base
        val incomingBeat = mobileBeatAt(incoming, inMs / 1_000.0) ?: return base
        val ratio = plan.incomingPlaybackRate / plan.outgoingPlaybackRate
        val period = minOf(outgoingBeat.second, incomingBeat.second / ratio)
        var diff = (incomingBeat.first / ratio - outgoingBeat.first) % period
        if (diff > period / 2) diff -= period else if (diff < -period / 2) diff += period
        if (abs(diff) > period * 0.3) return base
        errors += diff * ratio
        if (errors.size < 5) return base
        val median = errors.sorted()[2]
        errors.clear()
        if (abs(median) < 0.008) { quietUntil = now + 500_000_000; return base }
        factor = if (median > 0) 0.97f else minOf(1.03f, 1.12f / base)
        if (factor <= 1 && median < 0) return base
        val duration = (abs(median) / (base * userSpeed * abs(factor - 1f)) * 1_000).roundToLong().coerceIn(1, 1_500)
        nudgeUntil = now + duration * 1_000_000
        return base * factor
    }
}

/** Seconds since the last locally measured beat, and its length (same mobile bar policy). */
internal fun mobileBeatAt(analysis: TrackAnalysis, positionSec: Double): Pair<Double, Double>? {
    val fromTempo = if (analysis.bpm > 0.0) 60.0 / analysis.bpm else 0.0
    val interval = analysis.beatInterval
    val nominal = when {
        interval > 0 && (fromTempo <= 0 || abs(interval / fromTempo - 1) < 0.25) -> interval
        fromTempo > 0 -> fromTempo
        else -> return null
    }
    val bars = analysis.downbeats
    val next = bars.binarySearch(positionSec).let { if (it >= 0) it + 1 else -it - 1 }
    val origin: Double
    var beat = nominal
    if (next in 1 until bars.size) {
        origin = bars[next - 1]
        val bar = bars[next] - origin
        val beats = (bar / nominal).roundToInt()
        if (beats in 1..8) beat = bar / beats
    } else origin = if (next >= 1) bars[next - 1] else analysis.firstBeat
    if (beat <= 0 || !beat.isFinite()) return null
    return ((positionSec - origin) % beat + beat) % beat to beat
}

/** Return the incoming deck to 1x in <=0.75% steps on its own beats, held during pause. */
internal class MobileTempoEase(private val initial: Float, private val fromMs: Long, analysis: TrackAnalysis) {
    private val steps = ceil(abs(initial - 1f) / 0.0075).toInt().coerceAtLeast(1)
    private val beatMs = ((mobileBeatAt(analysis, fromMs / 1_000.0)?.second ?: 0.5) * 1_000).coerceAtLeast(1.0)
    fun rate(positionMs: Long): Float {
        if (positionMs < fromMs) return 1f
        val step = ((positionMs - fromMs) / beatMs).toInt().coerceIn(0, steps)
        return initial + (1f - initial) * step / steps
    }
}

internal fun mobileOutgoingRamp(plan: TransitionPlan, positionMs: Long): Float {
    val stretch = plan.outgoingPlaybackRate - 1.0
    if (abs(stretch) < 0.0001) return 1f
    val steps = ceil(abs(stretch) / 0.0075).toInt().coerceAtLeast(1)
    val beatMs = (plan.beatSeconds.takeIf { it > 0 } ?: 0.5) * 1_000
    val starts = plan.transitionStart * 1_000 - steps * beatMs
    val due = ((positionMs - starts) / beatMs + 1).toInt().coerceIn(0, steps)
    return (1.0 + stretch * due / steps).toFloat()
}
