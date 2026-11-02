package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Make the planner's beatmatch promise executable by a PCM engine. A tempo match alone
 * does not put the two bar accents in phase. Move the incoming cue by at most one bar,
 * retaining the planner's musical exit, duration, and bounded incoming skip.
 */
fun alignTransitionDownbeats(
    plan: TransitionPlan,
    outgoing: TrackAnalysis,
    incoming: TrackAnalysis,
    incomingDuration: Double,
): TransitionPlan {
    if (plan.transitionStyle != TransitionStyle.DJ_BLEND || plan.blocked) return plan
    if (outgoing.beatConfidence < MIN_BEATMATCH_CONFIDENCE || incoming.beatConfidence < MIN_BEATMATCH_CONFIDENCE) {
        return unaligned(plan)
    }
    val rate = plan.incomingPlaybackRate
    val beat = incoming.beatInterval.takeIf { it.isFinite() && it > 0 }
        ?: incoming.bpm.takeIf { it.isFinite() && it > 0 }?.let { 60.0 / it }
        ?: return unaligned(plan)
    val outBeat = outgoing.downbeats.filter { it.isFinite() && it >= plan.transitionStart && it <= plan.transitionEnd }
        .minOrNull() ?: return unaligned(plan)
    if (!rate.isFinite() || rate <= 0) return unaligned(plan)
    val elapsed = (outBeat - plan.transitionStart) * rate
    val latest = minOf(latestIncomingCue(incomingDuration), incomingDuration - plan.fadeSeconds * rate - 1.0)
    val originalClash = simultaneousVocalFraction(outgoing, incoming, plan.transitionStart,
        plan.transitionEnd, plan.incomingCueTime, rate)
    val cue = incoming.downbeats.asSequence()
        .filter { it.isFinite() && it >= 0 }
        .map { it - elapsed }
        .filter { it >= incomingAudibleStart(incoming) && it <= latest }
        .filter { abs(it - plan.incomingCueTime) <= 4 * beat }
        .filter { cue ->
            val clash = simultaneousVocalFraction(outgoing, incoming, plan.transitionStart,
                plan.transitionEnd, cue, rate)
            originalClash == null || clash == null || clash <= originalClash + VOCAL_CLASH_TOLERANCE
        }
        .minByOrNull { abs(it - plan.incomingCueTime) } ?: return unaligned(plan)
    return plan.copy(
        incomingCueTime = cue,
        incomingHandoffTime = cue + plan.fadeSeconds * rate,
        downbeatAligned = true,
    )
}

/** Missing bar evidence gets the same explicit, unstretched fallback on every desktop. */
private fun unaligned(plan: TransitionPlan): TransitionPlan = plan.copy(
    transitionStyle = TransitionStyle.EQUAL_POWER,
    incomingPlaybackRate = 1.0,
    outgoingPlaybackRate = 1.0,
    phaseLock = false,
    downbeatAligned = false,
    echoSeconds = 0.0,
    bassSwap = false,
    filterSweep = 0.0,
    policyReasons = plan.policyReasons + "downbeat-alignment-unavailable",
)

/**
 * Frames of this block before the scheduled seam, or null if it is beyond this block.
 * Call with the source position of the next frame to WRITE, never the device's heard
 * clock: WASAPI and Pulse/ALSA can have different amounts of audio queued ahead.
 */
fun transitionPrefixFrames(
    plan: TransitionPlan,
    renderPositionUs: Long,
    blockFrames: Int,
    sampleRate: Int,
    sourceRate: Double = 1.0,
): Int? {
    if (plan.blocked || plan.fadeSeconds <= 0 || !plan.transitionStart.isFinite() ||
        sampleRate <= 0 || blockFrames <= 0 || !sourceRate.isFinite() || sourceRate <= 0) return null
    val until = (plan.transitionStart * 1_000_000 - renderPositionUs).coerceAtLeast(0.0)
    val frames = ceil(until * sampleRate / (1_000_000 * sourceRate)).toLong()
    return if (frames < blockFrames) frames.toInt() else null
}

/** A late/unready decoder cannot claim a beat lock. Keep a bounded fade at the real exit. */
fun lateTransitionFallback(plan: TransitionPlan, renderSeconds: Double): TransitionPlan {
    if (!renderSeconds.isFinite() || renderSeconds <= plan.transitionStart + 0.002) return plan
    val remaining = (plan.transitionEnd - renderSeconds).coerceAtLeast(0.0)
    return unaligned(plan).copy(
        transitionStart = renderSeconds,
        fadeSeconds = minOf(plan.fadeSeconds, remaining),
        incomingHandoffTime = plan.incomingCueTime + minOf(plan.fadeSeconds, remaining),
        policyReasons = plan.policyReasons + "late-decoder-crossfade",
    )
}

/** Bound correlated full-scale masters without a hard limiter clipping their overlap. */
fun overlapHeadroom(outgoingGain: Float, incomingGain: Float): Float =
    1f / (abs(outgoingGain) + abs(incomingGain)).coerceAtLeast(1f)
