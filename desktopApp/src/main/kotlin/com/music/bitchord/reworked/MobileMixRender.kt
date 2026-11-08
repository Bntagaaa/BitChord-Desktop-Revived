package com.music.bitchord.reworked

import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.FILTER_ECHO_AT
import com.music.bitchord.playback.smart.echoTailSeconds
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.round

/** Pure faders, beat-snapped filters and echo from MOBILE CrossfadeController (v1.9.1). */
internal class MobileMixRender(plan: TransitionPlan, val advanced: Boolean) {
    private val render = Render(plan.transitionStyle, plan.bassSwap, plan.bassSwapFraction,
        plan.filterSweep, plan.filterVariant, plan.vocalOverlap, advanced,
        if (plan.beatSeconds > 0) plan.fadeSeconds / plan.beatSeconds else 0.0,
        plan.phaseLock, plan.echoSeconds)
    val handoffAt: Float get() = render.handoffAt
    val phaseLock: Boolean get() = render.phaseLock
    val hasEcho: Boolean get() = render.echo
    fun rise(progress: Float): Float = mixRise(progress.coerceIn(0f, 1f))
    fun fall(progress: Float): Float = mixFall(progress.coerceIn(0f, 1f))
    fun level(progress: Float, spanMs: Float): Float = outgoingLevel(progress, spanMs)
    fun tailDone(progress: Float, spanMs: Float): Boolean = !render.echo || progress >= render.echoAt + render.echoTailMs / spanMs
    fun ride(progress: Float, outgoing: MobileTransitionFilter?, incoming: MobileTransitionFilter?) {
        tracks.out = outgoing; tracks.into = incoming
        rideStyle(progress.coerceIn(0f, 1f))
        if (render.echo && progress > 1f) rideEchoOut(progress)
        tracks.out = null; tracks.into = null
    }
    private val tracks = object {
        var out: MobileTransitionFilter? = null
        var into: MobileTransitionFilter? = null
        fun outgoing(low: Float, high: Float) { out?.setCutoffs(low, high) }
        fun incoming(low: Float, high: Float) { into?.setCutoffs(low, high) }
        fun outgoingEcho(delay: Float, send: Float, dry: Float) { out?.setEcho(delay, send, dry) }
    }
    private val filters = object {
        fun open() { tracks.out?.open(); tracks.into?.open() }
    }
    private class Render(
        val style: TransitionStyle = TransitionStyle.EQUAL_POWER,
        val bassSwap: Boolean = false,
        bassSwapFraction: Double = 0.7,
        val filterSweep: Double = 0.0,
        val filterVariant: FilterTransitionVariant = FilterTransitionVariant.SWEEP,
        val vocalOverlap: Double = 0.0,

        val advanced: Boolean = false,

        beats: Double = 0.0,
        lockPhase: Boolean = false,

        echoSeconds: Double = 0.0,
    ) {
        private val grid = if (advanced && beats >= 2.0) beats else 0.0
        val clash = vocalOverlap.coerceIn(0.0, 1.0)

        val echo = grid > 0 && echoSeconds > 0.0
        val echoSecondsF = echoSeconds.toFloat()

        val echoTailMs = (echoTailSeconds(echoSeconds) * 1000).toFloat()

        val phaseLock = advanced && lockPhase

        val swapAt = onBeat(bassSwapFraction.coerceIn(0.05, 0.95))

        val swapWidth = if (grid > 0) SWAP_BEATS / grid else 2 * BASS_SWAP_WIDTH
        val swapFrom = if (grid > 0) swapAt - swapWidth else swapAt - BASS_SWAP_WIDTH
        val entryOpenBy = onBar(
            when (filterVariant) {
                FilterTransitionVariant.SWEEP -> ENTRY_OPEN_BY
                FilterTransitionVariant.BASS_HANDOFF -> 0.38
                FilterTransitionVariant.ECHO_RIDE -> 0.52
            },
        )
        val vocalEntryOpenBy = onBar(entryOpenBy + (VOCAL_SAFE_ENTRY_OPEN_BY - entryOpenBy) * clash)
        val blendEntryOpenBy = onBar(BLEND_ENTRY_OPEN_BY + (BLEND_ENTRY_CLASH_OPEN_BY - BLEND_ENTRY_OPEN_BY) * clash)
        val blendExitFrom = onBar(BLEND_EXIT_FROM + (BLEND_EXIT_CLASH_FROM - BLEND_EXIT_FROM) * clash)
        val filterSwapAt = onBar(
            when (filterVariant) {
                FilterTransitionVariant.SWEEP -> FILTER_BASS_SWAP_AT
                FilterTransitionVariant.BASS_HANDOFF -> 0.43
                FilterTransitionVariant.ECHO_RIDE -> 0.58
            },
        )
        val filterSwapAtF = filterSwapAt.toFloat()

        val echoAt = onBar(FILTER_ECHO_AT)
        val echoFrom = if (grid > 0) echoAt - 1.0 / grid else echoAt
        val echoKill = if (grid > 0) ECHO_KILL_BEATS / grid else 0.0

        val holdFaders = (1.0 - clash).toFloat()
        val swapAtF = swapAt.toFloat()

        val fallFrom = minOf(swapAtF, 0.5f, if (grid > 0) (1.0 - 4.0 / grid).toFloat() else 0.5f)
            .coerceAtLeast(0f)

        val handoffAt = when {
            !advanced -> HANDOFF_AT
            style == TransitionStyle.DJ_BLEND && bassSwap -> swapAtF
            style == TransitionStyle.DJ_FILTER && filterVariant == FilterTransitionVariant.BASS_HANDOFF -> filterSwapAtF
            else -> HANDOFF_AT
        }

        private fun onBeat(fraction: Double): Double =
            if (grid > 0) (round(fraction * grid) / grid).coerceIn(1.0 / grid, 1.0 - 1.0 / grid) else fraction

        private fun onBar(fraction: Double): Double =
            if (grid >= 8.0) {
                (round(fraction * grid / 4.0) * 4.0 / grid).coerceIn(4.0 / grid, 1.0 - 4.0 / grid)
            } else {
                onBeat(fraction)
            }
    }

    private fun rideStyle(progress: Float) {
        when (render.style) {
            TransitionStyle.DJ_FILTER -> when (render.filterVariant) {
                FilterTransitionVariant.BASS_HANDOFF -> rideFallbackBassHandoff(progress)
                FilterTransitionVariant.SWEEP,
                FilterTransitionVariant.ECHO_RIDE,
                -> rideFilterSweep(progress)
            }
            TransitionStyle.DJ_BLEND ->
                if (render.bassSwap) rideBassSwap(progress) else rideVocalSeparation(progress)
            TransitionStyle.GAPLESS -> filters.open()
            TransitionStyle.EQUAL_POWER -> rideVocalSeparation(progress)
        }
        if (render.echo) rideEchoOut(progress)
    }

    private fun rideEchoOut(progress: Float) {
        val p = progress.toDouble()
        val handover = ((p - (render.echoAt - render.swapWidth)) / render.swapWidth).coerceIn(0.0, 1.0)
        tracks.outgoing(MobileTransitionFilter.OPEN_HZ, bassCutoff(handover))
        if (p < render.echoFrom) return
        val send = if (p >= render.echoAt) 0.0 else (p - render.echoFrom) / (render.echoAt - render.echoFrom)
        tracks.outgoingEcho(render.echoSecondsF, send.toFloat(), echoDry(progress))
    }

    private fun echoDry(progress: Float): Float =
        (1.0 - ((progress - render.echoAt) / render.echoKill).coerceIn(0.0, 1.0)).toFloat()

    private fun outgoingLevel(progress: Float, wallSpan: Float): Float {
        if (!render.echo) return mixFall(progress)
        val sinceEcho = (progress - render.echoAt.toFloat()) * wallSpan
        val repeats = if (sinceEcho > 0f) sinceEcho / (render.echoSecondsF * 1000f) else 0f
        val wet = if (sinceEcho > 0f) ECHO_LEVEL * ECHO_DECAY.pow(repeats) else 0f
        return maxOf(echoDry(progress), wet)
    }

    private fun rideVocalSeparation(progress: Float) {
        val amount = render.vocalOverlap.coerceIn(0.0, 1.0)
        if (amount <= 0.0) {
            filters.open()
            return
        }
        val open = MobileTransitionFilter.OPEN_HZ.toDouble()
        val floor = glide(open, VOCAL_SEPARATION_FLOOR_HZ, amount)
        tracks.outgoing(
            glide(open, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE)).toFloat(),
            MobileTransitionFilter.OFF_HZ,
        )
        tracks.incoming(
            MobileTransitionFilter.OPEN_HZ,
            entryHighPass(progress, amount, VOCAL_SEPARATION_HIGH_PASS_HZ, ENTRY_OPEN_BY),
        )
    }

    private fun rideFilterSweep(progress: Float) {
        val sweep = render.filterSweep.coerceIn(0.0, 1.0)
        if (sweep <= 0.0) {
            filters.open()
            return
        }
        val open = MobileTransitionFilter.OPEN_HZ.toDouble()
        val entry = glide(open, FILTER_ENTRY_HZ, sweep)
        val floor = glide(open, FILTER_FLOOR_HZ, sweep)
        val cutoff = glide(entry, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE))
        val entryCorner = entryHighPass(
            progress,
            sweep,
            glide(ENTRY_HIGH_PASS_HZ, VOCAL_SAFE_ENTRY_HIGH_PASS_HZ, render.clash),
            render.vocalEntryOpenBy,
        )
        val handover = ((progress - (render.filterSwapAt - render.swapWidth)) / render.swapWidth).coerceIn(0.0, 1.0)
        tracks.outgoing(cutoff.toFloat(), bassCutoff(handover))
        tracks.incoming(MobileTransitionFilter.OPEN_HZ, maxOf(entryCorner, bassCutoff(1.0 - handover)))
    }

    private fun rideFallbackBassHandoff(progress: Float) {
        val p = progress.toDouble()
        val handover = ((p - (render.filterSwapAt - render.swapWidth)) / render.swapWidth).coerceIn(0.0, 1.0)
        val exitAmount = ((p - render.filterSwapAt) / (1.0 - render.filterSwapAt)).coerceIn(0.0, 1.0)
        val handoffTone = glide(
            MobileTransitionFilter.OPEN_HZ.toDouble(),
            FALLBACK_HANDOFF_FLOOR_HZ,
            exitAmount.pow(FALLBACK_HANDOFF_SHAPE),
        )
        val vocalTone = glide(
            MobileTransitionFilter.OPEN_HZ.toDouble(),
            VOCAL_SEPARATION_FLOOR_HZ,
            render.clash * p.pow(VOCAL_SAFE_EXIT_SHAPE),
        )
        val outgoingTone = minOf(handoffTone, vocalTone).toFloat()
        val incomingEntry = entryHighPass(
            progress,
            1.0,
            glide(FALLBACK_HANDOFF_ENTRY_HZ, VOCAL_SAFE_ENTRY_HIGH_PASS_HZ, render.clash),
            render.vocalEntryOpenBy,
        )
        tracks.outgoing(outgoingTone, bassCutoff(handover))
        tracks.incoming(MobileTransitionFilter.OPEN_HZ, maxOf(incomingEntry, bassCutoff(1.0 - handover)))
    }

    private fun entryHighPass(progress: Float, amount: Double, topHz: Double, openBy: Double): Float {
        val remaining = (1.0 - progress / openBy).coerceIn(0.0, 1.0)
        return glide(MobileTransitionFilter.OFF_HZ.toDouble(), topHz, amount * remaining.pow(ENTRY_SHAPE))
            .toFloat()
    }

    private fun glide(from: Double, to: Double, amount: Double): Double =
        from * (to / from).pow(amount.coerceIn(0.0, 1.0))

    private fun rideBassSwap(progress: Float) {
        val handover = ((progress - render.swapFrom) / render.swapWidth).coerceIn(0.0, 1.0)
        val clash = render.vocalOverlap.coerceIn(0.0, 1.0)
        val entry = maxOf(
            bassCutoff(1.0 - handover),
            entryHighPass(
                progress,
                1.0,
                glide(BLEND_ENTRY_HIGH_PASS_HZ, BLEND_ENTRY_CLASH_HIGH_PASS_HZ, clash),
                render.blendEntryOpenBy,
            ),
        )
        tracks.incoming(MobileTransitionFilter.OPEN_HZ, entry)
        tracks.outgoing(blendExitLowPass(progress, clash), bassCutoff(handover))
    }

    private fun blendExitLowPass(progress: Float, clash: Double): Float {
        val from = render.blendExitFrom
        val amount = ((progress - from) / (1.0 - from)).coerceIn(0.0, 1.0)
        val floor = glide(BLEND_EXIT_LOW_PASS_HZ, BLEND_EXIT_CLASH_LOW_PASS_HZ, clash)
        return glide(MobileTransitionFilter.OPEN_HZ.toDouble(), floor, amount).toFloat()
    }

    private fun bassCutoff(amount: Double): Float =
        glide(MobileTransitionFilter.OFF_HZ.toDouble(), BASS_SWAP_HZ, amount).toFloat()



    private fun riseGain(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    private fun fallGain(progress: Float): Float =
        cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    private fun mixRise(progress: Float): Float {
        if (!render.advanced) return riseGain(progress)
        val styled = when (render.style) {
            TransitionStyle.DJ_BLEND ->
                if (render.bassSwap) held(riseGain(progress), riseGain(progress / render.swapAtF)) else riseGain(progress)
            TransitionStyle.DJ_FILTER -> riseGain(progress.pow(FILTER_RISE_SHAPE))
            else -> riseGain(progress)
        }
        val vocalSafe = riseGain(progress.pow(VOCAL_SAFE_RISE_SHAPE))
        return styled + (vocalSafe - styled) * render.clash.toFloat()
    }

    private fun mixFall(progress: Float): Float {
        if (!render.advanced) return fallGain(progress)
        if (render.echo) return 1f
        val styled = when (render.style) {
            TransitionStyle.DJ_BLEND -> {
                if (!render.bassSwap) {
                    fallGain(progress)
                } else {
                    val from = render.fallFrom
                    held(fallGain(progress), if (progress <= from) 1f else fallGain((progress - from) / (1f - from)))
                }
            }
            TransitionStyle.DJ_FILTER -> fallGain(progress.pow(FILTER_FALL_SHAPE))
            else -> fallGain(progress)
        }
        val vocalSafe = fallGain(progress.pow(VOCAL_SAFE_FALL_SHAPE))
        return styled + (vocalSafe - styled) * render.clash.toFloat()
    }

    private fun held(plain: Float, dj: Float): Float = plain + (dj - plain) * render.holdFaders


    private companion object {
        const val VOCAL_SAFE_FALL_SHAPE = 0.65f
        const val VOCAL_SAFE_RISE_SHAPE = 1.65f
        const val FILTER_FALL_SHAPE = 1.6f
        const val FILTER_RISE_SHAPE = 0.8f
        const val BASS_SWAP_HZ = 200.0
        const val BASS_SWAP_WIDTH = 0.10
        const val BLEND_ENTRY_CLASH_HIGH_PASS_HZ = 950.0
        const val BLEND_ENTRY_CLASH_OPEN_BY = 0.7
        const val BLEND_ENTRY_HIGH_PASS_HZ = 520.0
        const val BLEND_ENTRY_OPEN_BY = 0.45
        const val BLEND_EXIT_CLASH_FROM = 0.12
        const val BLEND_EXIT_CLASH_LOW_PASS_HZ = 1_100.0
        const val BLEND_EXIT_FROM = 0.3
        const val BLEND_EXIT_LOW_PASS_HZ = 2_200.0
        const val ECHO_DECAY = 0.4f
        const val ECHO_KILL_BEATS = 4.0
        const val ECHO_LEVEL = 0.5f
        const val ENTRY_HIGH_PASS_HZ = 1_200.0
        const val ENTRY_OPEN_BY = 0.6
        const val ENTRY_SHAPE = 0.35
        const val FALLBACK_HANDOFF_ENTRY_HZ = 900.0
        const val FALLBACK_HANDOFF_FLOOR_HZ = 1_600.0
        const val FALLBACK_HANDOFF_SHAPE = 0.8
        const val FILTER_BASS_SWAP_AT = 0.5
        const val FILTER_ENTRY_HZ = 7_000.0
        const val FILTER_FLOOR_HZ = 300.0
        const val FILTER_SWEEP_SHAPE = 0.75
        const val HANDOFF_AT = 0.5f
        const val SWAP_BEATS = 0.5
        const val VOCAL_SAFE_ENTRY_HIGH_PASS_HZ = 2_400.0
        const val VOCAL_SAFE_ENTRY_OPEN_BY = 0.78
        const val VOCAL_SAFE_EXIT_SHAPE = 0.55
        const val VOCAL_SEPARATION_FLOOR_HZ = 1_600.0
        const val VOCAL_SEPARATION_HIGH_PASS_HZ = 700.0
    }
}
