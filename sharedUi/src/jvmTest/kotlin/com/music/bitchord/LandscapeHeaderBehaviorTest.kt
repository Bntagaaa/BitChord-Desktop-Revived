package com.music.bitchord

import com.music.bitchord.ui.player.landscapeColumnGeometry
import com.music.bitchord.ui.player.landscapeLikeVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeHeaderBehaviorTest {
    @Test fun lyricsHeaderAlignsToCoverInTheFirstMeasurePass() {
        val layout = landscapeColumnGeometry(500, 700, 80, 24)
        assertEquals(500, layout.artworkSize)
        assertEquals(48, layout.artworkTop)
        assertEquals(layout.artworkTop, layout.headerTop)
    }

    @Test fun compactLayoutLeavesRoomForItsRealActionHeight() {
        val layout = landscapeColumnGeometry(320, 280, 50, 12)
        assertEquals(218, layout.artworkSize)
        assertEquals(0, layout.headerTop)
        assertTrue(layout.artworkTop + layout.artworkSize + 12 + 50 <= 280)
    }

    @Test fun resizingAndScalingUseTheSameConstraintRelationship() {
        val normal = landscapeColumnGeometry(300, 800, 60, 24)
        val scaled = landscapeColumnGeometry(600, 1600, 120, 48)
        assertEquals(normal.headerTop * 2, scaled.headerTop)
        assertEquals(normal.artworkSize * 2, scaled.artworkSize)
        val tiny = landscapeColumnGeometry(0, 20, 100, 24)
        assertEquals(0, tiny.artworkSize)
        assertEquals(0, tiny.headerTop)
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
