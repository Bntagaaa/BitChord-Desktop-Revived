package com.music.bitchord.reworked

import com.music.bitchord.data.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class SongPersistenceTest {
    private val song = Song("recording", "A song", "Two artists", "file:///music/cover.png", "4:15",
        artistId = "UC1", artists = listOf(ArtistRef("One", "UC1"), ArtistRef("Two", "UC2")),
        albumId = "MPRE1", albumName = "An album", isVideo = false, isVideoOrigin = true,
        setVideoId = "playlist-slot", queueTier = QueueTier.USER_QUEUE, queueEntryId = "queue-slot",
        radioName = "A mix", localUri = "file:///music/song.flac", downloadFormat = "FLAC",
        localPath = "/music/song.flac", localDateAddedSeconds = 1700, localDateModifiedSeconds = 1900,
        trackNumber = 7, sourceQuality = "LOSSLESS", sourceHiRes = true, isExplicit = false,
        playbackSource = "Collection", playbackSourceType = PlaybackSourceType.BROWSE, playbackSourceId = "VL1", feedbackToken = "feedback")

    @Test fun queueRoundTripKeepsPlaylistSlotAndSourceIdentity() {
        val original = SavedQueue(listOf(song, song.copy(queueEntryId = "second-slot", setVideoId = "second-playlist-slot")), 1, 81234, 0.4f, 1.25f, true, 2)
        assertEquals(original, Json.decodeFromString<SavedQueue>(Json.encodeToString(original)))
    }

    @Test fun localPlaylistRoundTripPreservesAllMobileSongMetadata() {
        val original = LocalPlaylist("local:1", "My music", listOf(song))
        assertEquals(original, Json.decodeFromString<LocalPlaylist>(Json.encodeToString(original)))
    }

    @Test fun emptyQueueRemainsEmpty() {
        val original = SavedQueue(emptyList(), -1, 0)
        assertEquals(original, Json.decodeFromString<SavedQueue>(Json.encodeToString(original)))
    }
}
