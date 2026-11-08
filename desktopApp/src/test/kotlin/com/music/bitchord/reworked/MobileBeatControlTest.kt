package com.music.bitchord.reworked

import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileBeatControlTest {
    private val analysis = TrackAnalysis(bpm = 120.0, beatInterval = 0.5, firstBeat = 0.0,
        downbeats = listOf(0.0, 2.0, 4.0, 6.0, 8.0))

    @Test fun advancedFadersHandOffOnTheMeasuredBassSwapBeat() {
        val plan = TransitionPlan(fadeSeconds = 8.0, beatSeconds = 0.5, transitionStyle = TransitionStyle.DJ_BLEND,
            bassSwap = true, bassSwapFraction = 0.7)
        val advanced = MobileMixRender(plan, true)
        assertEquals(11f / 16f, advanced.handoffAt)
        assertEquals(1f, advanced.rise(advanced.handoffAt), 0.0001f)
        assertEquals(0.5f, MobileMixRender(plan, false).handoffAt)
        val colliding = MobileMixRender(plan.copy(vocalOverlap = 1.0), true)
        assertTrue(colliding.rise(0.3f) < advanced.rise(0.3f), "A colliding vocal must enter later than an instrumental blend")
    }

    @Test fun phaseLockSlowsAnAheadBeatThenSettlesBeforeMeasuringAgain() {
        val plan = TransitionPlan(incomingPlaybackRate = 1.0, outgoingPlaybackRate = 1.0, phaseLock = true)
        val phase = MobilePhaseLock(analysis, analysis, plan)
        var rate = 1f
        repeat(5) { rate = phase.update(2_000, 2_050, 0.2f, 1f, 1_000_000_000L + it * 40_000_000L) }
        assertEquals(0.97f, rate, 0.00001f)
        assertEquals(1f, phase.update(3_000, 3_000, 0.4f, 1f, 3_000_000_000L))
        val disagreeing = MobilePhaseLock(analysis, analysis, plan)
        repeat(10) { assertEquals(1f, disagreeing.update(2_000, 2_240, 0.3f, 1f, 4_000_000_000L + it * 40_000_000L)) }
    }

    @Test fun tempoEaseStaysOnMediaBeatsAndRestoresNormalSpeedAfterASeek() {
        val ease = MobileTempoEase(1.09f, 1_000, analysis)
        assertEquals(1.09f, ease.rate(1_000))
        assertTrue(ease.rate(1_500) in 1.0824f..1.0832f)
        assertEquals(ease.rate(2_000), ease.rate(2_000), "A paused playhead must not advance the ease")
        assertEquals(1f, ease.rate(8_000), 0.0001f)
        assertEquals(1f, ease.rate(0), 0.0001f)
    }
}
