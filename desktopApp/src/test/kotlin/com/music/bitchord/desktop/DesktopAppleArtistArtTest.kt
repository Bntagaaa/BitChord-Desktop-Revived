package com.music.bitchord.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Regression tests for metadata parsing in the original Apple Music Motion branch. */
class DesktopAppleArtistArtTest {
    private fun extractMotion(json: String): JsonObject? {
        val method = DesktopAppleArtistArtRepository::class.java.getDeclaredMethod(
            "findMotionArtist", JsonElement::class.java,
        )
        method.isAccessible = true
        return method.invoke(DesktopAppleArtistArtRepository, Json.parseToJsonElement(json)) as JsonObject?
    }

    @Test
    fun `motion URL is found inside an Apple artist header`() {
        val motion = assertNotNull(extractMotion(
            """{"artistLogo":null,"videoArtwork":{"dictionary":{"motionArtistSquare1x1":{"video":"https://example.com/artist.m3u8"}}}}"""
        ))
        assertEquals("https://example.com/artist.m3u8", motion["video"]?.jsonPrimitive?.content)
    }

    @Test
    fun `motion URL is found outside the selected hero header`() {
        val motion = assertNotNull(extractMotion(
            """{"header":{"artistLogo":null},"metadata":{"deep":{"motionArtistSquare1x1":{"video":"https://example.com/deep.m3u8"}}}}"""
        ))
        assertEquals("https://example.com/deep.m3u8", motion["video"]?.jsonPrimitive?.content)
    }

    @Test
    fun `invalid motion metadata is ignored so the still can remain`() {
        assertNull(extractMotion("""{"motionArtistSquare1x1":{"video":"file:///invalid"}}"""))
        assertNull(extractMotion("""{"artistLogo":null,"artwork":{"dictionary":{"width":1600}}}"""))
    }

    @Test
    fun `source metadata model preserves still and motion artwork`() {
        val art = DesktopAppleArtistArt(
            heroUrl = "https://example.com/still.jpg",
            logoUrl = null,
            logoAspect = 1f,
            videoUrl = "https://example.com/artist.m3u8",
            backgroundColor = 0xFF202020.toInt(),
            textColor = 0xFFFFFFFF.toInt(),
            keyColor = null,
        )
        assertEquals("https://example.com/artist.m3u8", art.videoUrl)
        assertEquals("https://example.com/still.jpg", art.heroUrl)
    }
}
