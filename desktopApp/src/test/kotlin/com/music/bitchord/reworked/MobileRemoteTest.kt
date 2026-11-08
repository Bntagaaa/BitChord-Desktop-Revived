package com.music.bitchord.reworked

import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.webdav.WebDavClient
import java.nio.file.Files
import kotlin.test.*

class MobileRemoteTest {
    @Test fun byteRangesHonorBoundsAndSuffixes() {
        assertEquals(0L..99L, remoteByteRange(null, 100))
        assertEquals(10L..99L, remoteByteRange("bytes=10-", 100))
        assertEquals(90L..99L, remoteByteRange("bytes=-10", 100))
        assertEquals(10L..99L, remoteByteRange("bytes=10-999", 100))
        assertNull(remoteByteRange("bytes=100-", 100))
        assertNull(remoteByteRange("bytes=10-9", 100))
        assertNull(remoteByteRange("bytes=0-9,20-29", 100))
        assertNull(remoteByteRange("bytes=0-", 0))
        assertEquals(LongRange.EMPTY, remoteByteRange(null, 0))
    }

    @Test fun remoteCredentialsCannotEscapeTheirConfiguredOriginAndFolder() {
        val root = "https://music.example:8443/private/music"
        assertTrue(davWithinRoot("$root/Artist/song.flac", root))
        assertFalse(davWithinRoot("https://music.example/private/music/song.flac", root))
        assertFalse(davWithinRoot("http://music.example:8443/private/music/song.flac", root))
        assertFalse(davWithinRoot("https://other.example:8443/private/music/song.flac", root))
        assertFalse(davWithinRoot("$root/../other/song.flac", root))
        assertFalse(davWithinRoot("${root}alike/song.flac", root))
        assertFalse(smbWithinRoot("Music/../Private/file.mp3", "Music"))
        assertFalse(smbWithinRoot("Musical/file.mp3", "Music"))
        assertTrue(smbWithinRoot("music/Album/file.mp3", "Music"))
        assertFails { safeSmbPath("folder/../private") }
    }

    @Test fun webDavXmlDoesNotResolveExternalEntities() {
        val result = WebDavClient.parseMultistatus("""<!DOCTYPE response [<!ENTITY xxe SYSTEM "file:///missing-private-file">]>
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/music/track.flac</d:href><d:propstat><d:prop><d:displayname>&xxe;</d:displayname></d:prop></d:propstat></d:response></d:multistatus>
        """, "https://example.com/music/")
        assertTrue(result.isEmpty())
        val ordinary = WebDavClient.parseMultistatus("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/music/track.flac</d:href><d:propstat><d:prop><d:displayname>Song</d:displayname></d:prop></d:propstat></d:response></d:multistatus>""", "https://example.com/music/")
        assertEquals("Song", ordinary.single().displayName)
    }

    @Test fun enhancedAndPlainSidecarLyricsRemainAvailableOffline() {
        val directory = Files.createTempDirectory("reworked-lyrics-test-")
        try {
            val media = directory.resolve("Track.mka")
            Files.writeString(directory.resolve("Track.lrc"), "[00:01.00]<00:01.00>Hello <00:01.50>world<00:02.00>")
            val raw = assertNotNull(EmbeddedLyrics.sidecar(media.toString()))
            val lines = assertNotNull(EmbeddedLyrics.parse(raw))
            assertEquals("Hello world", lines.last().text)
            assertTrue(lines.last().isWordSynced)
            val unsynced = assertNotNull(EmbeddedLyrics.parse("First line\nSecond line"))
            assertEquals(listOf("First line", "Second line"), unsynced.map { it.text })
            assertTrue(unsynced.all { it.timeMs == 0L })
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
