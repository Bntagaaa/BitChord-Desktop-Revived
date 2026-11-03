package com.music.bitchord.playback.smart

import kotlin.math.ceil

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
    return plan.copy(
        transitionStyle = TransitionStyle.EQUAL_POWER,
        incomingPlaybackRate = 1.0,
        outgoingPlaybackRate = 1.0,
        phaseLock = false,
        echoSeconds = 0.0,
        bassSwap = false,
        filterSweep = 0.0,
        transitionStart = renderSeconds,
        fadeSeconds = minOf(plan.fadeSeconds, remaining),
        incomingHandoffTime = plan.incomingCueTime + minOf(plan.fadeSeconds, remaining),
        policyReasons = plan.policyReasons + "late-decoder-crossfade",
    )
}

/** Upstream CrossfadeController.headroomFor: preserve the same equal-power headroom. */
fun overlapHeadroom(outgoingGain: Float, incomingGain: Float): Float =
    1f / kotlin.math.sqrt((outgoingGain + incomingGain).coerceAtLeast(1f))
