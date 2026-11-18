package com.music.bitchord.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guardrails for intermittent fullscreen-player cover failures. */
class FullscreenArtworkRecoveryTest {
    @Test
    fun youtubeFallbackOnlyAcceptsValidVideoIdentifiers() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            youtubeThumbnailRecoveryUrl("dQw4w9WgXcQ"),
        )
        assertNull(youtubeThumbnailRecoveryUrl("local:///music/song.flac"))
        assertNull(youtubeThumbnailRecoveryUrl(""))
        assertNull(youtubeThumbnailRecoveryUrl("too-short"))
    }

    @Test
    fun repeatedArtworkFailuresSlowDownInsteadOfHotLooping() {
        assertEquals(ART_RETRY_DELAY_MS, playerArtworkRetryDelayMs(0))
        assertEquals(ART_RETRY_DELAY_MS, playerArtworkRetryDelayMs(ART_RETRIES - 1))
        assertEquals(5_000L, playerArtworkRetryDelayMs(ART_RETRIES))
        assertEquals(15_000L, playerArtworkRetryDelayMs(ART_RETRIES + 1))
        assertEquals(60_000L, playerArtworkRetryDelayMs(ART_RETRIES + 2))
        assertTrue(playerArtworkRetryDelayMs(ART_RETRIES + 10) > 0)
    }
}
