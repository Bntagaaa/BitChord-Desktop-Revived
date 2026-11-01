package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopNativeTrimThrottleTest {

    @Test
    fun firstAttemptAllowedAndSubsequentAttemptsAreCooledDown() {
        val throttle = DesktopNativeTrimThrottle(cooldownNanos = 1_000)
        assertTrue(throttle.allow(10_000))
        assertFalse(throttle.allow(10_999))
        assertTrue(throttle.allow(11_000))
        assertFalse(throttle.allow(11_500))
    }

    @Test
    fun monotonicClockResetDoesNotBlockTrimmingForever() {
        val throttle = DesktopNativeTrimThrottle(cooldownNanos = 1_000)
        assertTrue(throttle.allow(10_000))
        assertTrue(throttle.allow(500))
        assertFalse(throttle.allow(501))
    }
}
