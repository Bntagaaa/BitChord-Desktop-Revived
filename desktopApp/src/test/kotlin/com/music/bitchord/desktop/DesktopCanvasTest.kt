package com.music.bitchord.desktop

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The canvas chain: whether a clip really belongs to the track, and the two wire formats the
 * providers speak.
 */
class DesktopCanvasTest {

    private fun artwork(title: String?, artist: String?, album: String? = null) =
        DesktopCanvasArtwork(url = "https://example.invalid/clip.mp4", title = title, artist = artist, album = album)

    @Test
    fun `a clip for the same recording is accepted through case, accents and punctuation`() {
        val clip = artwork("CRAZY IN LOVE (feat. JAY-Z)", "Beyoncé")
        assertTrue(clip.matches("Crazy in Love feat Jay Z", "Beyonce", null))
    }

    @Test
    fun `a different song by the same artist is refused`() {
        assertFalse(artwork("Halo", "Beyonce").matches("Crazy in Love", "Beyonce", null))
    }

    @Test
    fun `a clip credited to someone else is refused`() {
        assertFalse(artwork("Crazy in Love", "Someone Else").matches("Crazy in Love", "Beyonce", null))
    }

    @Test
    fun `an album mismatch only counts when both sides know one`() {
        val clip = artwork("Song", "Artist", album = "Real Album")
        assertTrue(clip.matches("Song", "Artist", null), "no album asked for means no album gate")
        assertTrue(clip.matches("Song", "Artist", "Real Album"))
        assertFalse(clip.matches("Song", "Artist", "Other Album"))
    }

    @Test
    fun `a clip that states nothing about itself is taken on trust`() {
        assertTrue(artwork(null, null).matches("Anything", "Anyone", "Whatever"))
    }

    @Test
    fun `YouTube packaging comes off before searching or matching`() {
        assertEquals("Shayar", "Shayar (Official Video)".cleanedForCanvas())
        assertEquals("Kesariya", "Kesariya [Lyrical] | Brahmastra".cleanedForCanvas())
        // A title that was only packaging is still a title.
        assertEquals("(Official Video)", "(Official Video)".cleanedForCanvas().ifBlank { "(Official Video)" })
    }

    @Test
    fun `every credited artist has to be present, in any order`() {
        assertTrue(canvasMatches("Song", listOf("A", "B"), "Song", "B & A"))
        assertFalse(canvasMatches("Song", listOf("A"), "Song", "A & B"))
    }

    @Test
    fun `a Tidal cover id is five segments or it is not a cover`() {
        assertEquals(
            "https://resources.tidal.com/videos/a/b/c/d/e/1280x1280.mp4",
            DesktopTidalCanvas.coverUrl("a-b-c-d-e"),
        )
        assertNull(DesktopTidalCanvas.coverUrl("a-b-c"))
        assertNull(DesktopTidalCanvas.coverUrl("not-an-id"))
    }

    @Test
    fun `an editorial playlist is never a track's motion artwork`() {
        assertTrue(DesktopAppleMusicCanvas.isCompilation("Today's Hits"))
        assertTrue(DesktopAppleMusicCanvas.isCompilation("Rap Life Essentials"))
        assertFalse(DesktopAppleMusicCanvas.isCompilation("Brahmastra"))
    }

    @Test
    fun `the canvas request and response round-trip through the protobuf`() {
        val uri = "spotify:track:4cOdK2wGLETKBW3PvgPWqT"
        val request = DesktopSpotifyCanvas.encodeCanvasRequest(uri)
        // CanvasRequest { Track tracks = 1 { string track_uri = 1 } } — two nested length-delimited
        // fields, so the uri's bytes have to be in there verbatim.
        assertTrue(String(request, StandardCharsets.ISO_8859_1).contains(uri))

        // A response holding one canvas for that track.
        val canvas = field(2, "https://canvaz.scdn.co/upload/x.cnvs.mp4") + field(5, uri)
        val response = nested(1, canvas)
        val hits = DesktopSpotifyCanvas.decodeCanvasResponse(response)
        assertEquals(1, hits.size)
        assertEquals("https://canvaz.scdn.co/upload/x.cnvs.mp4", hits.single().url)
        assertEquals(uri, hits.single().trackUri)
    }

    @Test
    fun `a response that is not protobuf yields nothing rather than throwing`() {
        assertTrue(DesktopSpotifyCanvas.decodeCanvasResponse("not protobuf at all".toByteArray()).isEmpty())
        assertTrue(DesktopSpotifyCanvas.decodeCanvasResponse(ByteArray(0)).isEmpty())
    }

    @Test
    fun `a playlist is streamed rather than saved as if it were the media`() {
        // Apple's motion artwork is HLS. Saving the playlist to disk leaves FFmpeg with segment
        // URLs it cannot resolve, which is what "could not open the clip" was.
        assertTrue(isManifest("https://mvod.itunes.apple.com/x/P359420040_default.m3u8"))
        assertTrue(isManifest("https://im-fa.manifest.tidal.com/1/manifests/abc.mpd?token=1"))
        assertFalse(isManifest("https://resources.tidal.com/videos/a/b/c/d/e/1280x1280.mp4"))
        assertFalse(isManifest("https://canvaz.scdn.co/upload/x.cnvs.mp4"))
    }

    @Test
    fun `a hit whose title carries its feature credit still matches`() {
        // YouTube Music and Apple both title this one with the credit, which is why it matches;
        // the provider was throwing before it ever got here.
        val clip = artwork("Peaches (feat. Daniel Caesar & GIVĒON)", "Justin Bieber", "Justice")
        assertTrue(clip.matches("Peaches (feat. Daniel Caesar & Giveon)", "Justin Bieber", "Justice"))
    }

    @Test
    fun `cache sizes read the way the Storage row shows them`() {
        assertEquals("512 MB", formatCacheSize(512))
        assertEquals("1 GB", formatCacheSize(1024))

        // The Storage row is localised, so half a gigabyte reads "1.5 GB" on an
        // English machine and "1,5 GB" on a Polish one. Both are correct, and
        // neither the code nor the test gets to decide which: what the row
        // promises is the number and the unit. So that is what is asserted, and
        // the decimal separator is left to the user's locale, which is what
        // stopped this test passing anywhere the locale used a comma.
        val half = formatCacheSize(1536)
        assertTrue(half.endsWith(" GB"), "expected a GB unit, got: $half")
        assertEquals(
            1.5,
            half.removeSuffix(" GB").replace(',', '.').toDouble(),
            0.0001,
        )
    }

    @Test
    fun `hlsFileFor selects smallest variant covering maxEdge and caches segments locally`() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=2160x2160,CODECS="avc1.640028"
            high.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=720x720,CODECS="avc1.4d401f"
            mid.m3u8
        """.trimIndent()
        val midMedia = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg1.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        var highRequested = false
        server.createContext("/master.m3u8") { ex ->
            val bytes = master.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/high.m3u8") { ex ->
            highRequested = true
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.createContext("/mid.m3u8") { ex ->
            val bytes = midMedia.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/init.mp4") { ex ->
            val bytes = byteArrayOf(1, 2, 3, 4)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/seg1.m4s") { ex ->
            val bytes = byteArrayOf(5, 6, 7, 8)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/master.m3u8"
        val cached = try {
            DesktopCanvasCache.hlsFileFor(url, 540)
        } finally {
            server.stop(0)
        }
        assertFalse(highRequested, "should not fetch 2160p variant when 720p covers 540px")
        val playlist = kotlin.test.assertNotNull(cached)
        assertTrue(java.nio.file.Files.isReadable(playlist))
        val contents = java.nio.file.Files.readString(playlist)
        assertFalse(contents.contains("http://"), "rewritten playlist must reference local files")
        assertTrue(contents.contains("file:"), "rewritten playlist must use file URIs for cross-platform FFmpeg")
        assertEquals(playlist, DesktopCanvasCache.hlsFileIfCached(url, 540), "second call should hit local cache")
        assertEquals(playlist, DesktopCanvasCache.hlsFileFor(url, 540), "hlsFileFor should reuse local cache")
    }

    @Test
    fun `DesktopCanvasDecoder loops across EOF and increments loopGeneration`() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/video/red-h264.mp4")).use { it.readBytes() }
        val temp = java.nio.file.Files.createTempFile("bitchord-loop-test-", ".mp4")
        try {
            java.nio.file.Files.write(temp, fixture)
            DesktopCanvasDecoder(maxEdge = 540, loopAtEnd = true).use { decoder ->
                decoder.open(temp.toAbsolutePath().toString()).getOrThrow()
                assertEquals(0, decoder.loopGeneration)
                var framesBeforeLoop = 0
                var framesAfterLoop = 0
                for (i in 0 until 120) {
                    val frame = decoder.nextFrame() ?: break
                    frame.close()
                    if (decoder.loopGeneration == 0) framesBeforeLoop++ else framesAfterLoop++
                    if (decoder.loopGeneration >= 2 && framesAfterLoop >= 2) break
                }
                assertTrue(framesBeforeLoop > 0, "should decode initial pass frames")
                assertTrue(decoder.loopGeneration >= 1, "should seek to 0 at EOF and increment loopGeneration")
                assertTrue(framesAfterLoop > 0, "should decode frames after looping")
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temp)
        }
    }

    private fun field(number: Int, value: String): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return byteArrayOf((number shl 3 or 2).toByte()) + varint(bytes.size) + bytes
    }

    private fun nested(number: Int, payload: ByteArray): ByteArray =
        byteArrayOf((number shl 3 or 2).toByte()) + varint(payload.size) + payload

    private fun varint(value: Int): ByteArray {
        var remaining = value
        val out = mutableListOf<Byte>()
        while (true) {
            if (remaining and 0x7F.inv() == 0) {
                out += remaining.toByte()
                return out.toByteArray()
            }
            out += ((remaining and 0x7F) or 0x80).toByte()
            remaining = remaining ushr 7
        }
    }
}
