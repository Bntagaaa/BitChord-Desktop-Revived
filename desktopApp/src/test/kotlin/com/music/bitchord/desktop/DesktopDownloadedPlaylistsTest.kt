package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A downloaded playlist is an ordered view of real local files, not a second audio copy. */
class DesktopDownloadedPlaylistsTest {
    private val scratch = Files.createTempDirectory("bitchord-playlist-test")

    @AfterTest
    fun cleanUp() {
        scratch.toFile().deleteRecursively()
    }

    private fun song(id: String, path: String? = null) =
        Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null, localPath = path)

    private fun saved(id: String): Song {
        val path = scratch.resolve("$id.flac")
        Files.write(path, ByteArray(128))
        return song(id, path.toString())
    }

    @Test
    fun `playlist keeps original ordering and only verified downloads`() {
        val playlist = DesktopPlaylist(id = "VLabc", title = "Favorites",
            songs = listOf(song("first"), song("second"), song("third")))
        val second = saved("second")
        val first = saved("first")
        assertEquals(listOf("first", "second"),
            playlist.availableDownloads(listOf(second, first)).map(Song::videoId))
    }

    @Test
    fun `deleted and partial files disappear from offline playlist without deleting metadata`() {
        val first = saved("first")
        val missing = song("missing", scratch.resolve("missing.flac").toString())
        val playlist = DesktopPlaylist(id = "VLabc", title = "Favorites", songs = listOf(first, missing))
        assertEquals(listOf("first"), playlist.availableDownloads(listOf(first, missing)).map(Song::videoId))
        Files.delete(scratch.resolve("first.flac"))
        assertTrue(playlist.availableDownloads(listOf(first, missing)).isEmpty())
        assertEquals("Favorites", playlist.title)
        assertEquals(2, playlist.songs.size)
    }

    @Test
    fun `duplicate references show one playable track`() {
        val first = saved("first")
        val playlist = DesktopPlaylist(id = "VLabc", title = "Favorites",
            songs = listOf(song("first"), song("first")))
        assertEquals(listOf("first"), playlist.availableDownloads(listOf(first, first)).map(Song::videoId))
    }
}
