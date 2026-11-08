package com.music.bitchord.reworked

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MobilePlaylistEditorTest {
    private fun song(video: String, entry: String? = null) = Song(video, video, "Fixture", null, setVideoId = entry)

    @Test fun localDuplicatesKeepTheirOriginalOccurrenceWhenOneCopyIsRemoved() {
        val first = song("duplicate")
        val second = first.copy()
        val last = song("last")
        val entries = playlistEditEntries(listOf(first, second, last), remote = false)
        assertEquals(3, entries.map { it.key }.distinct().size)
        val updated = editedPlaylistSongs(entries, listOf(entries[2], entries[1]))
        assertEquals(listOf("last", "duplicate"), updated.map { it.videoId })
        assertSame(second, updated.last(), "Removing the first copy must retain the selected second occurrence")
    }

    @Test fun exactDuplicateRemovalPrecedesReorderingTheSurvivingServerEntries() = runBlocking {
        val songs = listOf(song("duplicate", "copy-1"), song("duplicate", "copy-2"), song("b", "entry-b"), song("c", "entry-c"))
        val original = playlistEditEntries(songs, remote = true)
        val target = listOf(original[3], original[1], original[2])
        val events = mutableListOf<String>()
        val saved = saveRemotePlaylistEdits(original, target,
            fresh = { events += "fresh"; songs },
            remove = { entries -> events += "remove"; assertEquals(listOf("copy-1" to "duplicate"), entries) },
            reorder = { current, desired ->
                events += "reorder"
                assertEquals(listOf("copy-2", "entry-b", "entry-c"), current)
                assertEquals(listOf("entry-c", "copy-2", "entry-b"), desired)
            })
        assertEquals(listOf("fresh", "remove", "reorder"), events)
        assertEquals(listOf("entry-c", "copy-2", "entry-b"), saved.map { it.setVideoId })
    }

    @Test fun aChangedServerSnapshotCannotSendAnyEdits() = runBlocking {
        val songs = listOf(song("duplicate", "copy-1"), song("duplicate", "copy-2"), song("b", "entry-b"))
        val original = playlistEditEntries(songs, remote = true)
        var writes = 0
        assertFailsWith<IllegalStateException> {
            saveRemotePlaylistEdits(original, original.reversed(), fresh = { songs.drop(1) },
                remove = { writes++ }, reorder = { _, _ -> writes++ })
        }
        assertEquals(0, writes)
    }

    @Test fun serverOrderChangesAreConflictsEvenWhenTheSameTracksRemain() = runBlocking {
        val songs = listOf(song("a", "entry-a"), song("b", "entry-b"), song("c", "entry-c"))
        val original = playlistEditEntries(songs, remote = true)
        var writes = 0
        assertFailsWith<IllegalStateException> {
            saveRemotePlaylistEdits(original, listOf(original[2], original[0]), fresh = { songs.reversed() },
                remove = { writes++ }, reorder = { _, _ -> writes++ })
        }
        assertEquals(0, writes)
    }

    @Test fun anUnidentifiedOrRepeatedServerEntryCannotEnterTheEditor() {
        assertFailsWith<IllegalArgumentException> { playlistEditEntries(listOf(song("missing")), remote = true) }
        assertFailsWith<IllegalArgumentException> {
            playlistEditEntries(listOf(song("a", "same-entry"), song("b", "same-entry")), remote = true)
        }
    }

    @Test fun aDraftCannotIntroduceOrRepeatAnOccurrence() {
        val original = playlistEditEntries(listOf(song("a"), song("b")), remote = false)
        assertFailsWith<IllegalArgumentException> { editedPlaylistSongs(original, listOf(original[0], original[0])) }
        assertFailsWith<IllegalArgumentException> { editedPlaylistSongs(original, listOf(PlaylistEditEntry("local:100", song("other")))) }
        assertFailsWith<IllegalArgumentException> { editedPlaylistSongs(original, listOf(original[0].copy(song = song("changed")))) }
    }

    @Test fun aFailedRemovalNeverStartsMovesBasedOnAnAssumedSurvivorList() = runBlocking {
        val songs = listOf(song("a", "entry-a"), song("b", "entry-b"), song("c", "entry-c"))
        val original = playlistEditEntries(songs, remote = true)
        var reordered = false
        assertFailsWith<IllegalStateException> {
            saveRemotePlaylistEdits(original, listOf(original[2], original[1]), fresh = { songs },
                remove = { error("The remove request did not complete") }, reorder = { _, _ -> reordered = true })
        }
        assertFalse(reordered)
    }

    @Test fun removingEveryOccurrenceDoesNotSendAnEmptyReorderRequest() = runBlocking {
        val songs = listOf(song("duplicate", "copy-1"), song("duplicate", "copy-2"))
        var removed = emptyList<Pair<String, String>>()
        var reordered = false
        val saved = saveRemotePlaylistEdits(playlistEditEntries(songs, true), emptyList(), fresh = { songs },
            remove = { removed = it }, reorder = { _, _ -> reordered = true })
        assertEquals(listOf("copy-1" to "duplicate", "copy-2" to "duplicate"), removed)
        assertTrue(saved.isEmpty())
        assertFalse(reordered)
    }
}
