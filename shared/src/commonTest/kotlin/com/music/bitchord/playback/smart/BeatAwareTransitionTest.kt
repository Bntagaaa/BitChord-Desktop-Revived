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

    @Test fun headroomMatchesUpstreamEqualPowerCurve() {
        for (frame in 0..48000) {
            val into = sqrt(frame / 48000f)
            val out = sqrt(1 - frame / 48000f)
            assertEquals(1f / sqrt((out + into).coerceAtLeast(1f)), overlapHeadroom(out, into))
        }
        assertEquals(1f, overlapHeadroom(1f, 0f))
        assertEquals(1f, overlapHeadroom(0f, 1f))
    }
}
