package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The record's own arithmetic, apart from the store that keeps it: what a
 * re-download merges, what a delta asks for, what may be deleted when a
 * record goes, and how far along a record is. The transfer itself is not
 * exercised — see [DesktopDownloadRecordTest] for the file-side contract
 * these records ride on.
 */
class DesktopPlaylistDownloadsTest {

    private fun song(
        videoId: String,
        artist: String = "Test Artist",
        localUri: String? = null,
        localPath: String? = null,
    ) = Song(
        videoId = videoId,
        title = "Track $videoId",
        artist = artist,
        thumbnailUrl = null,
        durationText = "3:00",
        localUri = localUri,
        localPath = localPath,
    )

    private fun record(
        id: String,
        songs: List<Song>,
        playlist: Boolean = true,
    ) = DesktopPlaylistDownload(
        id = id,
        title = "Collection $id",
        subtitle = "12 songs",
        thumbnailUrl = null,
        playlist = playlist,
        songs = songs,
        createdAt = 100L,
        updatedAt = 200L,
    )

    @Test
    fun `the fraction a tile's bar reads is the share of the whole the download has reached`() {
        val snapshot = listOf(song("a"), song("b"), song("c"), song("d"))
        val progress = offlineProgress(
            snapshot,
            downloadedIds = setOf("a"),
            activeIds = setOf("b"),
        )
        assertEquals(0.5f, progress.fraction)
        assertTrue(progress.downloading)
    }

    @Test
    fun `a finished record sits at the whole bar and an empty one at zero`() {
        val finished = offlineProgress(
            listOf(song("a"), song("b")),
            downloadedIds = setOf("a", "b"),
            activeIds = emptySet(),
        )
        assertEquals(1f, finished.fraction)
        assertTrue(finished.complete)
        assertEquals(0f, offlineProgress(emptyList(), emptySet(), emptySet()).fraction)
    }

    @Test
    fun `a fresh ask leads and the old snapshot's stragglers follow`() {
        val existing = listOf(song("a"), song("b"), song("gone"))
        val fresh = listOf(song("c"), song("a"))

        // "gone" was downloaded under the old snapshot and stays until the record is thrown away.
        assertEquals(listOf("c", "a", "b", "gone"), mergedSnapshot(existing, fresh).map { it.videoId })
    }

    @Test
    fun `a re-download of an unchanged page keeps the order the page listed`() {
        val existing = listOf(song("a"), song("b"))
        val fresh = listOf(song("a"), song("b"))

        assertEquals(listOf("a", "b"), mergedSnapshot(existing, fresh).map { it.videoId })
    }

    @Test
    fun `the delta asks for what is neither on disk nor in the queue`() {
        val snapshot = listOf(
            song("saved", localUri = "file:///x"),
            song("queued"),
            song("recorded-only"),
            song("missing"),
        )

        val missing = tracksMissingOffline(
            snapshot,
            downloadedIds = setOf("recorded-only"),
            activeIds = setOf("queued"),
        )

        assertEquals(listOf("missing"), missing.map { it.videoId })
    }

    @Test
    fun `a saved copy on the record itself counts as on disk`() {
        val snapshot = listOf(song("a", localPath = "/home/x/Track a.flac"))

        assertTrue(tracksMissingOffline(snapshot, emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun `a song two records share is not deleted when one record goes`() {
        val shared = song("shared")
        val record = record("one", listOf(song("only"), shared))
        val others = listOf(record("two", listOf(shared)))

        val deletable = songsUnclaimedBy(record.songs, others)

        assertEquals(listOf("only"), deletable.map { it.videoId })
    }

    @Test
    fun `every song of the last record holding them is deletable`() {
        val record = record("last", listOf(song("a"), song("b")))

        assertEquals(setOf("a", "b"), songsUnclaimedBy(record.songs, emptyList()).map { it.videoId }.toSet())
    }

    @Test
    fun `progress counts the copies in hand and the ones in flight`() {
        val snapshot = listOf(
            song("a", localUri = "file:///a"),
            song("b", localPath = "/b"),
            song("c"),
            song("d"),
            song("e"),
        )

        val progress = offlineProgress(snapshot, downloadedIds = setOf("c"), activeIds = setOf("d", "e"))

        assertEquals(5, progress.total)
        assertEquals(3, progress.done)
        assertEquals(2, progress.active)
        assertFalse(progress.complete)
        assertTrue(progress.downloading)
    }

    @Test
    fun `a record with every song in hand is complete and still`() {
        val snapshot = listOf(song("a", localUri = "file:///a"), song("b", localUri = "file:///b"))

        val progress = offlineProgress(snapshot, emptySet(), emptySet())

        assertTrue(progress.complete)
        assertFalse(progress.downloading)
    }

    @Test
    fun `a song downloaded by the library shows in the record's arithmetic`() {
        val snapshot = listOf(song("a"))

        val progress = offlineProgress(snapshot, downloadedIds = setOf("a"), activeIds = emptySet())

        assertTrue(progress.complete)
    }

    @Test
    fun `album billing is carried as asked`() {
        assertTrue(record("v", emptyList()).playlist)
        assertFalse(record("m", emptyList(), playlist = false).playlist)
    }

    @Test
    fun `record line round-trips through the store's encoding`() {
        val record = DesktopPlaylistDownload(
            id = "VLabc123",
            title = "Road & the sky #1|mix",
            subtitle = "Kushagra Singh · 12 songs",
            thumbnailUrl = "https://i.ytimg.com/cover.jpg",
            playlist = true,
            songs = listOf(
                song("a", artist = "A & B|feat. C", localUri = "file:///music/BitChord/A & B.flac"),
                song("b", localPath = "/home/x/Music/BitChord/Track b.m4a"),
            ),
            createdAt = 1700000000000,
            updatedAt = 1700000100000,
        )

        val decoded = decodeRecord(record.toPreferenceLine())

        assertEquals(record, decoded)
    }

    @Test
    fun `a partial line does not decode`() {
        assertNull(decodeRecord("only|three|fields"))
        assertNull(decodeRecord(""))
    }
}
