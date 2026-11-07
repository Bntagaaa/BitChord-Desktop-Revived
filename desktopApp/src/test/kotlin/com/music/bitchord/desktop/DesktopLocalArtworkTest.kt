package com.music.bitchord.desktop

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.imageio.ImageIO
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
    // Real decodable images: header-only fixtures did not test decoder behavior.
    private val jpeg by lazy { makeImage("jpeg") }
    private val png by lazy { makeImage("png") }

    private fun makeImage(format: String, width: Int = 4): ByteArray =
        ByteArrayOutputStream().use { output ->
            val image = BufferedImage(width, 4, BufferedImage.TYPE_INT_RGB)
            check(ImageIO.write(image, format, output))
            output.toByteArray()
        }

    private fun assertRealImage(uri: String) {
        assertNotNull(ImageIO.read(Path.of(URI(uri)).toFile()),
            "The artwork URI must refer to a decodable image on this OS")
    }

    @Test
    fun addedFolderCoverReplacesPreviousMissWithoutClearingCache() = withWorkspace { music, cache ->
        val song = Files.write(music.resolve("track.ogg"), byteArrayOf(1, 2, 3))
        assertNull(DesktopLocalArtwork.artworkUri(song, cache))

        val cover = Files.write(music.resolve("Cover.PNG"), png)
        val uri = assertNotNull(DesktopLocalArtwork.artworkUri(song, cache))
        assertTrue(uri.endsWith(".png"))
        assertTrue(Files.readAllBytes(Path.of(URI(uri))).contentEquals(png))
        assertRealImage(uri)
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
        assertTrue(Files.readAllBytes(Path.of(URI(uri))).contentEquals(jpeg))
        assertRealImage(uri)
    }

    @Test
    fun changingFolderCoverGivesComposeANewUri() = withWorkspace { music, cache ->
        val song = Files.write(music.resolve("song.wav"), byteArrayOf(4))
        val cover = Files.write(music.resolve("folder.png"), png)
        val first = assertNotNull(DesktopLocalArtwork.artworkUri(song, cache))
        Files.write(cover, makeImage("png", width = 8))
        val second = assertNotNull(DesktopLocalArtwork.artworkUri(song, cache))
        assertNotEquals(first, second)
        assertRealImage(second)
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
        assertTrue(Files.readAllBytes(Path.of(URI(uri))).contentEquals(jpeg))
        assertRealImage(uri)
    }

    @Test
    fun mp3Utf16DescriptionDoesNotLeaveStrayNulBeforeCover() = withWorkspace { music, cache ->
        // UTF-16LE 'AB' + terminator. Old byte-wise parser stopped one byte early.
        val description = byteArrayOf(
            0xFF.toByte(), 0xFE.toByte(), 0x41, 0x00, 0x42, 0x00, 0x00, 0x00,
        )
        val apic = byteArrayOf(1) + "image/png".toByteArray() +
            byteArrayOf(0, 3) + description + png
        val frame = "APIC".toByteArray() + be32(apic.size) + byteArrayOf(0, 0) + apic
        val song = Files.write(
            music.resolve("local #track.mp3"),
            "ID3".toByteArray() + byteArrayOf(3, 0, 0) + synchsafe(frame.size) + frame,
        )
        val uri = assertNotNull(DesktopLocalArtwork.artworkUri(song, cache))
        assertTrue(Files.readAllBytes(Path.of(URI(uri))).contentEquals(png))
        assertRealImage(uri)
    }

    @Test
    fun flacSkipsUnsupportedPictureAndFindsJpeg() = withWorkspace { music, cache ->
        val streamInfo = byteArrayOf(0, 0, 0, 34) + ByteArray(34)
        val bad = flacPicture("not a JPEG".toByteArray(), isLast = false)
        val good = flacPicture(jpeg, isLast = true)
        val song = Files.write(music.resolve("song.flac"), "fLaC".toByteArray() +
            streamInfo + bad + good)
        val uri = assertNotNull(DesktopLocalArtwork.artworkUri(song, cache))
        assertTrue(Files.readAllBytes(Path.of(URI(uri))).contentEquals(jpeg))
        assertRealImage(uri)
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

    private fun flacPicture(image: ByteArray, isLast: Boolean): ByteArray {
        val mime = "image/jpeg".toByteArray()
        val payload = be32(3) + be32(mime.size) + mime + be32(0) +
            be32(4) + be32(4) + be32(24) + be32(0) + be32(image.size) + image
        val typeAndLast = if (isLast) 0x86 else 0x06
        return byteArrayOf(typeAndLast.toByte(),
            (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(),
            payload.size.toByte()) + payload
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
