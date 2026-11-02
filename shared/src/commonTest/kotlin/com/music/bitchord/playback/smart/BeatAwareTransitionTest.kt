package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeatAwareTransitionTest {
    private val plan = TransitionPlan(transitionStart = 180.25, transitionEnd = 188.25,
        fadeSeconds = 8.0, incomingCueTime = 2.1, incomingPlaybackRate = 1.0,
        transitionStyle = TransitionStyle.DJ_BLEND, phaseLock = true)
    private val outgoing = TrackAnalysis(bpm = 120.0, beatInterval = 0.5,
        beatConfidence = 0.9, downbeats = listOf(180.25, 182.25, 184.25, 186.25, 188.25))
    private val incoming = TrackAnalysis(bpm = 120.0, beatInterval = 0.5,
        beatConfidence = 0.9, downbeats = List(90) { 0.75 + 2 * it })

    @Test fun downbeatsCoincideWithAnOffsetIncomingGrid() {
        val aligned = alignTransitionDownbeats(plan, outgoing, incoming, 200.0)
        assertTrue(aligned.phaseLock)
        assertEquals(2.75, aligned.incomingCueTime, 1e-9)
        outgoing.downbeats.forEach { out ->
            val time = aligned.incomingCueTime + (out - aligned.transitionStart) * aligned.incomingPlaybackRate
            assertTrue(incoming.downbeats.any { abs(it - time) < 1e-9 })
        }
        assertEquals(plan.fadeSeconds, aligned.fadeSeconds)
    }

    @Test fun staticAlignmentDoesNotRequireLiveSpeedModulation() {
        val aligned = alignTransitionDownbeats(plan.copy(phaseLock = false), outgoing, incoming, 200.0)
        assertTrue(aligned.downbeatAligned)
        assertFalse(aligned.phaseLock, "bar alignment must not enable audible speed pulses")
        assertEquals(2.75, aligned.incomingCueTime, 1e-9)
    }

    @Test fun compatibleFasterIncomingTempoCanBlendWithoutRetimingTheAudibleDeck() {
        val out = outgoing.copy(duration = 200.0, contentEndTime = 198.0,
            downbeats = List(100) { 0.25 + 2 * it })
        val into = incoming.copy(duration = 200.0, bpm = 130.0, beatInterval = 60.0 / 130,
            mixInTime = 8.0, downbeats = List(100) { 0.75 + 240.0 / 130 * it })
        val planned = planTransition(out, into, TransitionTrackInfo("out", 200000),
            TransitionTrackInfo("in", 200000), duration = 200.0, mode = CrossfadeMode.SMART, advanced = true)
        assertEquals(TransitionStyle.DJ_BLEND, planned.transitionStyle)
        assertEquals(120.0 / 130, planned.incomingPlaybackRate, 0.0001)
        assertEquals(1.0, planned.outgoingPlaybackRate)
        val aligned = alignTransitionDownbeats(planned, out, into, 200.0)
        assertTrue(aligned.downbeatAligned)
        assertFalse(aligned.phaseLock)
    }

    @Test fun downbeatAlignmentAccountsForPitchPreservingTempoChange() {
        val faster = incoming.copy(bpm = 125.0, beatInterval = 0.48, downbeats = List(90) { 0.3 + 1.92 * it })
        val aligned = alignTransitionDownbeats(plan.copy(incomingPlaybackRate = 0.96), outgoing, faster, 200.0)
        assertTrue(aligned.phaseLock)
        outgoing.downbeats.forEach { out ->
            val time = aligned.incomingCueTime + (out - aligned.transitionStart) * aligned.incomingPlaybackRate
            assertTrue(faster.downbeats.any { abs(it - time) < 1e-8 })
        }
    }

    @Test fun missingOrUnusableDownbeatsFallBackWithoutStretching() {
        for (grid in listOf(emptyList(), listOf(Double.NaN, Double.POSITIVE_INFINITY), listOf(190.0))) {
            val fallback = alignTransitionDownbeats(plan, outgoing.copy(downbeats = grid), incoming, 200.0)
            assertFalse(fallback.phaseLock)
            assertEquals(TransitionStyle.EQUAL_POWER, fallback.transitionStyle)
            assertEquals(1.0, fallback.incomingPlaybackRate)
        }
    }

    @Test fun barAlignmentDoesNotMoveACleanCueIntoAVocalClash() {
        val curve = List(800) { EnergySample(it * 0.25, 1.0) }
        val sungOut = outgoing.copy(energyCurve = curve, vocalActivityMask = List(800) { 1.0 })
        val sungIn = incoming.copy(energyCurve = curve,
            vocalActivityMask = curve.map { if (it.time in 10.25..11.25) 1.0 else 0.0 })
        val aligned = alignTransitionDownbeats(plan, sungOut, sungIn, 200.0)
        assertTrue(aligned.downbeatAligned)
        assertEquals(0.75, aligned.incomingCueTime, 1e-9, "the nearer 2.75 s cue collides with a vocal")
    }

    @Test fun normalCrossfadeIsUnchangedEvenWithNoAnalysis() {
        val ordinary = planTransition(duration = 200.0, fadeSeconds = 6.0)
        assertEquals(ordinary, alignTransitionDownbeats(ordinary, TrackAnalysis(), TrackAnalysis(), 200.0))
        assertEquals(0.0, ordinary.incomingCueTime)
    }

    @Test fun dynamicDurationRemainsMusicalAcrossSlowAndFastPairs() {
        fun musicalPlan(bpm: Double): TransitionPlan {
            val beat = 60 / bpm
            val measured = TrackAnalysis(duration = 200.0, bpm = bpm, beatInterval = beat,
                beatConfidence = 0.95, audibleStartTime = 0.5, firstBeat = 0.5,
                mixInTime = 0.5 + 16 * beat, contentEndTime = 198.0,
                downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < 200 }.toList())
            return planTransition(analysis = measured, nextAnalysis = measured,
                currentTrack = TransitionTrackInfo("out", 200000), nextTrack = TransitionTrackInfo("in", 200000),
                duration = 200.0, mode = CrossfadeMode.SMART, advanced = true)
        }
        val slow = musicalPlan(90.0)
        val fast = musicalPlan(180.0)
        assertTrue(slow.fadeSeconds > fast.fadeSeconds, "duration must follow bars: slow=${slow.fadeSeconds}, fast=${fast.fadeSeconds}")
        for (result in listOf(slow, fast)) {
            assertTrue(result.fadeSeconds > 0 && result.fadeSeconds <= 16)
            assertTrue(result.transitionBeats > 0)
            assertEquals(result.transitionEnd, result.transitionStart + result.fadeSeconds, 1e-9)
        }
    }

    @Test fun seamIsSampleAccurateAtBothDesktopOutputRates() {
        for (sampleRate in listOf(44_100, 48_000, 96_000)) {
            val prefix = transitionPrefixFrames(plan, 180_240_000, 1024, sampleRate)!!
            assertTrue(abs(prefix.toDouble() / sampleRate - 0.01) <= 1.0 / sampleRate)
            // The amount queued to WASAPI, PulseAudio or ALSA is irrelevant to this API.
            assertEquals(0, transitionPrefixFrames(plan, 180_250_000, 1024, sampleRate))
            assertNull(transitionPrefixFrames(plan, 179_000_000, 1024, sampleRate))
        }
    }

    @Test fun anExactBlockBoundaryBelongsToTheNextBlock() {
        val at = plan.copy(transitionStart = 10.0)
        assertNull(transitionPrefixFrames(at, 9_000_000, 48000, 48000))
        assertEquals(0, transitionPrefixFrames(at, 10_000_000, 48000, 48000))
        assertEquals(240, transitionPrefixFrames(at, 9_990_000, 1024, 48000, 2.0))
    }

    @Test fun lateDecoderUsesRemainingDurationInsteadOfPretendingToBeBeatMatched() {
        val late = lateTransitionFallback(plan, 183.25)
        assertFalse(late.phaseLock)
        assertEquals(5.0, late.fadeSeconds)
        assertEquals(188.25, late.transitionStart + late.fadeSeconds)
        assertEquals(0.0, lateTransitionFallback(plan, 190.0).fadeSeconds)
    }

    @Test fun correlatedFullScaleMastersDoNotClipAtAnyPointOfTheEnvelope() {
        for (frame in 0..48000) {
            val into = sqrt(frame / 48000f)
            val out = sqrt(1 - frame / 48000f)
            assertTrue((out + into) * overlapHeadroom(out, into) <= 1.000001f)
        }
        assertEquals(1f, overlapHeadroom(1f, 0f))
        assertEquals(1f, overlapHeadroom(0f, 1f))
    }
}
