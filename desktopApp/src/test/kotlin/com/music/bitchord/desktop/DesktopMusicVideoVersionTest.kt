package com.music.bitchord.desktop

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopMusicVideoVersionTest {
    private fun song(id: String, video: Boolean = false) =
        Song(id, "Same Song", "Artist", null, isVideo = video)

    @Test
    fun catalogueTrackCanSwitchToMusicVideoWithoutLosingItsQueueMetadata() {
        val original = song("audio-track").copy(
            queueTier = QueueTier.USER_QUEUE,
            queueEntryId = "queue-entry-42",
        )
        val candidate = song("video-track", video = true).copy(
            durationText = "4:12",
            thumbnailUrl = "https://example.com/video.jpg",
        )
        val switched = assertNotNull(original.withMusicVersion(candidate, toVideo = true))

        assertEquals("video-track", switched.videoId)
        assertTrue(switched.isVideo)
        assertFalse(switched.isVideoOrigin)
        assertEquals("4:12", switched.durationText)
        assertEquals("https://example.com/video.jpg", switched.thumbnailUrl)
        assertEquals(original.queueTier, switched.queueTier)
        assertEquals(original.queueEntryId, switched.queueEntryId)
    }

    @Test
    fun videoCanSwitchBackToCatalogueAudio() {
        val original = song("video-track", video = true)
        val alternate = song("audio-track")
        val switched = assertNotNull(original.withMusicVersion(alternate, toVideo = false))

        assertEquals("audio-track", switched.videoId)
        assertFalse(switched.isVideo)
    }

    @Test
    fun sameOrMissingVideoIdentityIsNotSwapped() {
        val original = song("same")
        assertNull(original.withMusicVersion(song("same"), toVideo = true))
        assertNull(original.withMusicVersion(song(""), toVideo = true))
    }

    @Test
    fun replacingCurrentVersionKeepsTheQueueIndexAndDuplicateRows() {
        val repeated = song("repeat").copy(queueEntryId = "first-entry")
        val other = song("middle")
        val duplicate = repeated.copy(queueEntryId = "second-entry")
        val queue = DesktopQueue(listOf(repeated, other, duplicate), index = 0)

        val changed = queue.replaceCurrentVersion(
            "repeat", song("video-version", video = true),
        )

        assertEquals(0, changed.index)
        assertEquals(listOf("video-version", "middle", "repeat"), changed.songs.map { it.videoId })
        assertEquals("first-entry", changed.current?.queueEntryId)
        assertEquals("second-entry", changed.songs[2].queueEntryId)
        assertTrue(changed.current?.isVideo == true)
    }

    @Test
    fun lateVersionLookupCannotChangeTheNextSong() {
        val queue = DesktopQueue(listOf(song("old"), song("next")), index = 1)
        val ignored = queue.replaceCurrentVersion("old", song("video"))

        assertSame(queue, ignored)
        assertEquals("next", ignored.current?.videoId)
    }
}
