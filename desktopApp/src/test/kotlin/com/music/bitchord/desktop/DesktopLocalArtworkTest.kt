package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopLocalArtworkTest {
    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x02, 0xFF.toByte(), 0xD9.toByte(),
    )
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x00,
    )

    @Test
    fun addedFolderCoverReplacesPreviousMissWithoutClearingCache() = withWorkspace { music, cache ->
        val song = Files.write(music.resolve("track.ogg"), byteArrayOf(1, 2, 3))
        assertNull(DesktopLocalArtwork.artworkUri(song, cache))

        val cover = Files.write(music.resolve("Cover.PNG"), png)
        val uri = assertNotNull(DesktopLocalArtwork.artworkUri(song, cache))
        assertTrue(uri.endsWith(".png"))
        assertTrue(Files.readAllBytes(Path.of(java.net.URI(uri))).contentEquals(png))
        assertEquals(cover.toFile().length(), png.size.toLong())
        assertTrue(DesktopLocalArtwork.isFolderCoverFilename("COVER.PNG"))
    }

    @Test
    fun changedAudioMetadataInvalidatesCachedNegativeResult() = withWorkspace { music, cache ->
        val song = Files.write(music.resolve("song.mp3"), byteArrayOf(1, 2, 3))
        assertNull(DesktopLocalArtwork.artworkUri(song, cache))

        Files.write(song, mp3WithCover(jpeg))
        val uri = assertNotNull(DesktopLocalArtwork.artworkUri(song, cache))
        assertTrue(uri.endsWith(".jpg"))
        assertTrue(Files.readAllBytes(Path.of(java.net.URI(uri))).contentEquals(jpeg))
    }

    @Test
    fun changingFolderCoverGivesComposeANewUri() = withWorkspace { music, cache ->
        val song = Files.write(music.resolve("song.wav"), byteArrayOf(4))
        val cover = Files.write(music.resolve("folder.png"), png)
        val first = assertNotNull(DesktopLocalArtwork.artworkUri(song, cache))
        Files.write(cover, png + byteArrayOf(0x42))
        val second = assertNotNull(DesktopLocalArtwork.artworkUri(song, cache))
        assertNotEquals(first, second)
    }

    @Test
    fun extractsM4aCoverFromMetadataInsteadOfReadingMdat() = withWorkspace { music, cache ->
        val data = atom("data", byteArrayOf(0, 0, 0, 13, 0, 0, 0, 0) + jpeg)
        val songBytes = atom("ftyp", byteArrayOf(0, 0, 0, 0)) +
            atom("mdat", ByteArray(24)) +
            atom("moov", atom("udta", atom("meta",
                byteArrayOf(0, 0, 0, 0) + atom("ilst", atom("covr", data)))))
        val song = Files.write(music.resolve("song.m4a"), songBytes)
        val uri = assertNotNull(DesktopLocalArtwork.artworkUri(song, cache))
        assertTrue(Files.readAllBytes(Path.of(java.net.URI(uri))).contentEquals(jpeg))
    }

    @Test
    fun rejectsInvalidFolderImages() = withWorkspace { music, cache ->
        val song = Files.write(music.resolve("song.ogg"), byteArrayOf(1))
        Files.write(music.resolve("cover.jpg"), "not actually a picture".toByteArray())
        assertNull(DesktopLocalArtwork.artworkUri(song, cache))
    }

    private fun mp3WithCover(image: ByteArray): ByteArray {
        val apic = byteArrayOf(0) + "image/jpeg".toByteArray() +
            byteArrayOf(0, 3, 0) + image
        val frame = "APIC".toByteArray() + be32(apic.size) + byteArrayOf(0, 0) + apic
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + synchsafe(frame.size) + frame
    }

    private fun atom(type: String, content: ByteArray): ByteArray =
        be32(content.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + content

    private fun be32(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(),
        (value ushr 8).toByte(), value.toByte(),
    )

    private fun synchsafe(value: Int) = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(), ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(), (value and 0x7F).toByte(),
    )

    private inline fun withWorkspace(block: (Path, Path) -> Unit) {
        val root = createTempDirectory("bitchord-artwork-test")
        try {
            val music = Files.createDirectory(root.resolve("music"))
            val cache = Files.createDirectory(root.resolve("cache"))
            block(music, cache)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
