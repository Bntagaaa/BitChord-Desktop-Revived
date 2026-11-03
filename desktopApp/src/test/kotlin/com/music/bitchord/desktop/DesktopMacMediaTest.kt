package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMacMediaTest {
    private val song = Song("track", "Track", "Artist", null)
    private val playing = DesktopPlaybackState(song = song, isPlaying = true, positionMs = 1_000, durationMs = 60_000)
    private val previous = DesktopMacMediaPublication(song, true, 1_000, 60_000, 1.0, 1_000_000_000)

    @Test fun ordinaryProgressUsesTheNativeClockWithoutRepeatedMetadataWrites() {
        assertFalse(needsMacMediaPublication(previous, playing.copy(positionMs = 2_000), 1.0, 2_000_000_000))
    }
    @Test fun seeksInEitherDirectionResynchronizeElapsedTime() {
        assertTrue(needsMacMediaPublication(previous, playing.copy(positionMs = 20_000), 1.0, 2_000_000_000))
        assertTrue(needsMacMediaPublication(previous, playing.copy(positionMs = 0), 1.0, 5_000_000_000))
    }
    @Test fun pauseTrackAndSpeedChangesPublishEvenWithNoUiRecomposition() {
        assertTrue(needsMacMediaPublication(previous, playing.copy(isPlaying = false), 0.0, 2_000_000_000))
        assertTrue(needsMacMediaPublication(previous, playing.copy(song = song.copy(videoId = "next")), 1.0, 2_000_000_000))
        assertTrue(needsMacMediaPublication(previous, playing, 1.25, 2_000_000_000))
    }
    @Test fun bufferingAndFirstPublicationAreExplicit() {
        assertTrue(needsMacMediaPublication(null, playing, 1.0, 1_000_000_000))
        assertTrue(needsMacMediaPublication(previous, playing.copy(awaitingAudio = true), 0.0, 2_000_000_000))
    }
}
