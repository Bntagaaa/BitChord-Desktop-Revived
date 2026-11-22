package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPlayerMetadataTest {
    private val current = Song("AbCdEf123_-", "Track", "Artist", null, artistId = "artist", albumId = "album")
    private val watch = current.copy(thumbnailUrl = "https://example.test/cover.jpg", artistId = "other")

    @Test fun `watch metadata fills a missing cover even when credits are already known`() {
        val result = current.withDesktopPlayerMetadata(watch)
        assertEquals(watch.thumbnailUrl, result.thumbnailUrl)
        assertEquals(current.artistId, result.artistId)
        assertEquals(watch.thumbnailUrl, current.copy(thumbnailUrl = "").withDesktopPlayerMetadata(watch).thumbnailUrl)
    }

    @Test fun `keep the original cover and ignore stale metadata for a different track`() {
        val existing = current.copy(thumbnailUrl = "https://example.test/original.jpg")
        assertEquals(existing.thumbnailUrl, existing.withDesktopPlayerMetadata(watch).thumbnailUrl)
        assertNull(current.withDesktopPlayerMetadata(watch.copy(videoId = "old")).thumbnailUrl)
    }

    @Test fun `watch lookups only use YouTube IDs`() {
        assertTrue(current.canFetchDesktopPlayerMetadata())
        assertFalse(current.copy(videoId = "local:/music/song.flac").canFetchDesktopPlayerMetadata())
        assertFalse(current.copy(localPath = "/music/song.flac").canFetchDesktopPlayerMetadata())
    }
}
