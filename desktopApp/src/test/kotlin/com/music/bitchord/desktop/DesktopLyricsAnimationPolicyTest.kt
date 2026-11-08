package com.music.bitchord.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLyricsAnimationPolicyTest {
    @Test fun focusedWindowRunsLyricsWithDefaultSetting() {
        assertTrue(shouldAnimateDesktopLyrics(
            windowVisible = true, windowMinimized = false,
            windowFocused = true, animateWhenUnfocused = false,
        ))
    }

    @Test fun unfocusedWindowKeepsExistingPowerSavingByDefault() {
        assertFalse(shouldAnimateDesktopLyrics(
            windowVisible = true, windowMinimized = false,
            windowFocused = false, animateWhenUnfocused = false,
        ))
    }

    @Test fun optInKeepsLyricsAnimatingOnAnUnfocusedSecondMonitor() {
        assertTrue(shouldAnimateDesktopLyrics(
            windowVisible = true, windowMinimized = false,
            windowFocused = false, animateWhenUnfocused = true,
        ))
    }

    @Test fun minimizedOrHiddenWindowNeverRunsLyricsEvenWithOptIn() {
        for (focused in listOf(false, true)) {
            assertFalse(shouldAnimateDesktopLyrics(
                windowVisible = true, windowMinimized = true,
                windowFocused = focused, animateWhenUnfocused = true,
            ))
            assertFalse(shouldAnimateDesktopLyrics(
                windowVisible = false, windowMinimized = false,
                windowFocused = focused, animateWhenUnfocused = true,
            ))
        }
    }
}
