package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopLocalMusicArtworkFallbackTest {
    private fun local(file: Path, artwork: String? = null) = Song(
        videoId = "local:" + file,
        title = "Same name",
        artist = "Artist",
        thumbnailUrl = artwork,
        localPath = file.toString(),
        localUri = file.toUri().toString(),
    )

    private fun download(file: Path, artwork: String?) = Song(
        videoId = "yt-original-id",
        title = "Same name",
        artist = "Artist",
        thumbnailUrl = artwork,
        localPath = file.toString(),
        localUri = file.toUri().toString(),
    )

    @Test
    fun recoversOriginalDownloadCoverForSamePhysicalFile() {
        val songPath = Path.of("Music", "BitChord", "Artist - Song.m4a").toAbsolutePath()
        val localSong = local(songPath)
        val saved = download(songPath, "https://example.com/cover.jpg")
        val result = DesktopLocalMusic.withDownloadedArtwork(listOf(localSong), listOf(saved))
        assertEquals(saved.thumbnailUrl, result.single().thumbnailUrl)
    }

    @Test
    fun doesNotReplaceEmbeddedOrFolderCover() {
        val path = Path.of("Music", "BitChord", "track.flac").toAbsolutePath()
        val embedded = local(path, "file:///local-artwork/front.png")
        val saved = download(path, "https://example.com/remote.jpg")
        val result = DesktopLocalMusic.withDownloadedArtwork(listOf(embedded), listOf(saved))
        assertEquals(embedded.thumbnailUrl, result.single().thumbnailUrl)
    }

    @Test
    fun neverMatchesByTitleAlone() {
        val one = Path.of("Music", "one.mp3").toAbsolutePath()
        val two = Path.of("Music", "two.mp3").toAbsolutePath()
        val result = DesktopLocalMusic.withDownloadedArtwork(
            listOf(local(one)),
            listOf(download(two, "https://example.com/wrong.jpg")),
        )
        assertNull(result.single().thumbnailUrl)
    }

    @Test
    fun matchesNormalizedPathsWithUriOnlyDownloadRecord() {
        val path = Path.of("Music", "BitChord", "track with spaces.mp3").toAbsolutePath()
        val record = download(path, "https://example.com/art.jpg")
            .copy(localPath = null)
        val result = DesktopLocalMusic.withDownloadedArtwork(listOf(local(path)), listOf(record))
        assertEquals(record.thumbnailUrl, result.single().thumbnailUrl)
    }

    @Test
    fun ignoresDownloadsWithoutArtwork() {
        val path = Path.of("Music", "track.mp3").toAbsolutePath()
        val result = DesktopLocalMusic.withDownloadedArtwork(
            listOf(local(path)), listOf(download(path, null)),
        )
        assertNull(result.single().thumbnailUrl)
    }
}
