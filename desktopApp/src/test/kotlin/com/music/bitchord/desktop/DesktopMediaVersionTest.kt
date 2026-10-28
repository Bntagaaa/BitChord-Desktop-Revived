package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.QueueTier
import kotlin.test.*

class DesktopMediaVersionTest {
    @Test fun switchingRetainsQueueIdentityAndNeighbors() {
        val audio = Song("audio", "Song", "Artist", null, queueEntryId = "entry", queueTier = QueueTier.USER_QUEUE, playbackSource = "Playlist")
        val before = Song("before", "Before", "Artist", null)
        val after = Song("after", "After", "Artist", null)
        val queue = DesktopQueue(listOf(before, audio, after), 1)
        val video = Song("video", "Video", "Artist", null, isVideo = true)
        val switched = assertNotNull(queue.withMediaVersion(audio, video))
        assertEquals(1, switched.index)
        assertSame(before, switched.songs[0]); assertSame(after, switched.songs[2])
        val current = assertNotNull(switched.current)
        assertTrue(current.isVideo); assertTrue(current.isVideoOrigin)
        assertEquals("entry", current.queueEntryId)
        assertEquals(QueueTier.USER_QUEUE, current.queueTier)
        assertEquals("Playlist", current.playbackSource)
        val reverted = assertNotNull(switched.withMediaVersion(current, audio))
        assertFalse(assertNotNull(reverted.current).isVideo)
        assertTrue(assertNotNull(reverted.current).isVideoOrigin)
    }

    @Test fun aPreviouslyMisclassifiedVideoCanBeRepairedWithoutChangingId() {
        val wrong = Song("video-id", "Video", "Artist", null)
        val repaired = wrong.copy(isVideo = true, isVideoOrigin = true)
        val queue = DesktopQueue(listOf(wrong))
        val changed = assertNotNull(queue.withMediaVersion(wrong, repaired))
        assertTrue(assertNotNull(changed.current).isVideo)
        assertEquals("video-id", changed.current?.videoId)
    }

    @Test fun staleOrUnchangedLookupCannotReplaceQueue() {
        val audio = Song("audio", "Song", "Artist", null)
        val video = audio.copy(videoId = "video", isVideo = true)
        val queue = DesktopQueue(listOf(audio))
        assertNull(queue.withMediaVersion(audio.copy(), video))
        assertNull(queue.withMediaVersion(audio, audio))
        assertNull(queue.withMediaVersion(audio, audio.copy(videoId = "other-audio")))
    }
}
