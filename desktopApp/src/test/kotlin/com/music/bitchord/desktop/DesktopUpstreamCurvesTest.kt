package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/** Golden points from b690dc14 CrossfadeController.Render/mixRise/mixFall. */
class DesktopUpstreamCurvesTest {
    @Test fun outgoingBlendFallsFromTheMidpointInsteadOfHoldingUntilTheBassSwap() {
        val plan = TransitionPlan(transitionStyle = TransitionStyle.DJ_BLEND, bassSwap = true,
            bassSwapFraction = 0.7, fadeSeconds = 12.0, beatSeconds = 0.5, transitionBeats = 16)
        val gains = FloatArray(2)
        DesktopTransitionRide.gains(plan, 0.6f, gains)
        assertEquals(0.9510565f, gains[0], 0.000001f)
        assertEquals(0.9712810f, gains[1], 0.000001f)
        assertEquals(17f / 24, DesktopTransitionRide.handoffAt(plan), 0.000001f)
    }

    @Test fun filterGesturesUseTheSameUpstreamFaders() {
        val gains = FloatArray(2)
        for (variant in FilterTransitionVariant.entries) {
            DesktopTransitionRide.gains(TransitionPlan(transitionStyle = TransitionStyle.DJ_FILTER,
                filterVariant = variant), 0.6f, gains)
            assertEquals(0.7688956f, gains[0], 0.000001f)
            assertEquals(0.8643501f, gains[1], 0.000001f)
        }
    }

    @Test fun filterBassHandoffUsesTheActualOverlapGrid() {
        val plan = TransitionPlan(transitionStyle = TransitionStyle.DJ_FILTER,
            filterVariant = FilterTransitionVariant.BASS_HANDOFF,
            fadeSeconds = 10.0, beatSeconds = 0.5, transitionBeats = 32)
        assertEquals(0.4f, DesktopTransitionRide.handoffAt(plan), 0.000001f)
    }
}
