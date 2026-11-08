package com.music.bitchord.reworked

import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.sortedForLibrary
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.data.sources.addon.AddonStream
import com.music.bitchord.download.DownloadedCollection
import com.music.bitchord.download.SavedCollection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MobileLocalLibraryTest {
    private fun song(id: String, title: String = id, artist: String = "Artist", album: String? = null, track: Int? = null) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, albumName = album, trackNumber = track)

    @Test fun downloadQualityUsesItsOwnMobileTiers() {
        val standard = MobileDownloadRequest(DownloadQuality.STANDARD, false)
        assertEquals(128, standard.quality.maxKbps)
        assertEquals("LOW", standard.tier)
        assertFalse(standard.considersMatchedSources)
        assertEquals("HIGH", MobileDownloadRequest(DownloadQuality.HIGH, true).tier)
        assertEquals("LOSSLESS", MobileDownloadRequest(DownloadQuality.LOSSLESS, false).tier)
        assertTrue(MobileDownloadRequest(DownloadQuality.LOSSLESS, true).considersMatchedSources)
    }

    @Test fun codecEvidenceWinsOverTheRequestedTier() {
        val answer = AddonStream(url = "https://example.org/track.flac", codec = "aac", bitrate = 128000.0, quality = "lossless")
        val format = addonFormat(answer, "LOSSLESS")
        assertEquals("aac", format.codec)
        assertEquals(false, format.isLossless)
        assertEquals(128, format.kbps)
        assertEquals(true, addonFormat(AddonStream(format = "flac"), "HIGH").isLossless)
        assertNull(addonFormat(AddonStream(), "LOSSLESS").isLossless)
    }

    @Test fun syncOnlyAddsUnknownTracksAndDoesNotRecreateDeletedDownloads() {
        val record = SavedCollection("VLfixture", "Playlist", playlist = true, videoIds = listOf("kept", "deleted-file", "removed-online"))
        val listing = listOf(song("kept"), song("deleted-file"), song("new"), song("failed"))
        assertEquals(listOf("new", "failed"), MobilePlaylistRules.newTracks(listing, record).map { it.videoId })
        assertEquals(listOf("kept", "deleted-file", "new"), MobilePlaylistRules.listingToRecord(listing, record, setOf("new")))
    }

    @Test fun onlyOnlineYoutubePlaylistsAreEligibleForSync() {
        fun record(id: String, playlist: Boolean = true) = SavedCollection(id, "Fixture", playlist = playlist)
        assertEquals("VLPLfixture", MobilePlaylistRules.syncableBrowseId(record("PLfixture")))
        assertEquals("VLPLfixture", MobilePlaylistRules.syncableBrowseId(record("VLPLfixture")))
        assertNull(MobilePlaylistRules.syncableBrowseId(record("MPalbum")))
        assertNull(MobilePlaylistRules.syncableBrowseId(record("local:imported")))
        assertNull(MobilePlaylistRules.syncableBrowseId(record("spotify:playlist")))
        assertNull(MobilePlaylistRules.syncableBrowseId(record("album", false)))
    }

    @Test fun incompletePlaylistPagesRetainValidRowsButNeverClaimACompleteCheck() = runBlocking {
        val listing = MobilePlaylistRules.readWholePlaylist(
            first = { Result.success(YtMusicRepository.SongPage(listOf(song("first")), "next", suggested = listOf(song("suggested")))) },
            more = { Result.failure(IllegalStateException("Connection lost")) }, pause = {},
        )
        assertFalse(listing.complete)
        assertEquals(listOf("first"), listing.songs.map { it.videoId })
    }

    @Test fun accountCancellationNeverTurnsIntoAPartialPlaylistForDownload() = runBlocking {
        var continuations = 0
        assertFailsWith<CancellationException> {
            MobilePlaylistRules.readWholePlaylist(
                first = { Result.failure(CancellationException("The listener changed before the first page")) },
                more = { continuations++; Result.success(YtMusicRepository.SongPage(emptyList(), null)) }, pause = {},
            )
        }
        assertEquals(0, continuations)
        assertFailsWith<CancellationException> {
            MobilePlaylistRules.readWholePlaylist(
                first = { Result.success(YtMusicRepository.SongPage(listOf(song("private-row")), "continuation")) },
                more = { continuations++; Result.failure(CancellationException("The listener changed between pages")) }, pause = {},
            )
        }
        assertEquals(1, continuations)
    }

    @Test fun playlistPagingPreservesOrderAndStopsRepeatedTokens() = runBlocking {
        var requested = 0
        val listing = MobilePlaylistRules.readWholePlaylist(
            first = { Result.success(YtMusicRepository.SongPage(listOf(song("a")), "repeat")) },
            more = { requested++; Result.success(YtMusicRepository.SongPage(listOf(song("b"), song("a")), "repeat")) }, pause = {},
        )
        assertTrue(listing.complete)
        assertEquals(1, requested)
        assertEquals(listOf("a", "b"), listing.songs.map { it.videoId })
    }

    @Test fun recordedCollectionsLeadAlbumTagsAndTaggedTracksKeepReleaseOrder() {
        val second = song("second", album = "Album", track = 2)
        val first = song("first", album = "Album", track = 1)
        val plain = song("other", album = "Other")
        val groups = LocalGroups.albumsOf("local:downloads", listOf(second, first, plain),
            listOf(DownloadedCollection("VLset", "Album", "", null, true, listOf(second, first))))
        assertEquals(2, groups.size)
        assertEquals("VLset", groups.first().downloadId)
        assertEquals(listOf("second", "first"), groups.first().songs.map { it.videoId })
        val tagged = LocalGroups.albumsOf("local:device", listOf(second, first), emptyList())
        assertEquals(listOf("first", "second"), tagged.single().songs.map { it.videoId })
    }

    @Test fun folderAndSortRulesKeepDeviceIdentityAndMissingDatesStable() {
        val old = song("old", "Zebra").copy(localPath = "C:\\Music\\Rock\\old.flac", localDateAddedSeconds = 1)
        val newer = song("new", "Alpha").copy(localPath = "/mnt/Music/Rock/new.flac", localDateAddedSeconds = 2)
        val unknown = song("unknown", "Beta")
        assertEquals(listOf("new", "old", "unknown"), listOf(unknown, old, newer).sortedForLibrary(LocalMusicSort.DATE_ADDED).map { it.videoId })
        assertEquals(listOf("new", "unknown", "old"), listOf(old, newer, unknown).sortedForLibrary(LocalMusicSort.TITLE_ASC).map { it.videoId })
        assertEquals(2, LocalGroups.foldersOf("local:device", listOf(old, newer)).size)
    }
}
