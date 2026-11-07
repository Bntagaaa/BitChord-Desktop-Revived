package com.music.bitchord

import com.music.bitchord.ui.player.landscapeHeaderAlignmentPx
import com.music.bitchord.ui.player.landscapeLikeVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeHeaderBehaviorTest {
    @Test fun lyricsHeaderAlignsToActualCoverTop() {
        assertEquals(42, landscapeHeaderAlignmentPx(164, 122, compact = false))
    }

    @Test fun compactAndNegativeOffsetsDoNotPushHeaderDown() {
        assertEquals(0, landscapeHeaderAlignmentPx(90, 122, compact = false))
        assertEquals(0, landscapeHeaderAlignmentPx(164, 122, compact = true))
        assertEquals(0, landscapeHeaderAlignmentPx(Int.MIN_VALUE, 122, compact = false))
    }

    @Test fun signedOutDesktopCanStillLikeSongsLocally() {
        assertTrue(landscapeLikeVisible(signedIn = false, localSong = false, desktop = true))
    }

    @Test fun phoneOnlyShowsLikeWhenAccountOrLocalTrackExists() {
        assertTrue(landscapeLikeVisible(signedIn = true, localSong = false, desktop = false))
        assertTrue(landscapeLikeVisible(signedIn = false, localSong = true, desktop = false))
        assertFalse(landscapeLikeVisible(signedIn = false, localSong = false, desktop = false))
    }
}
