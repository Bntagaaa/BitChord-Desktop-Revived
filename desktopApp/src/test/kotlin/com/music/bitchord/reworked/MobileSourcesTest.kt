package com.music.bitchord.reworked

import com.music.bitchord.data.sources.addon.*
import com.music.bitchord.data.jiosaavn.selectBestSaavnStream
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.DownloadQuality
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class MobileSourcesTest {
    @Test fun jioSaavnOnlyAdvertisesAnAvailableRenditionAndKeepsUrlTokens() {
        val low = assertNotNull(selectBestSaavnStream("https://cdn.example/song_96.mp4?token=keep", supports320 = false))
        assertEquals(96, low.kbps)
        assertEquals("https://cdn.example/song_96.mp4?token=keep", low.url)
        val high = assertNotNull(selectBestSaavnStream(low.url, supports320 = true))
        assertEquals("https://cdn.example/song_320.mp4?token=keep", high.url)
        assertEquals(320, high.kbps)
        assertNull(selectBestSaavnStream("https://cdn.example/unknown", supports320 = true)?.kbps)
    }
    @Test fun mobileManifestPoliciesAndQualityOptionsArePreserved() {
        val manifest = Json.decodeFromString<AddonManifest>("""{
          "id":"fixture","resources":["search","stream"],"allowDownloads":"false","bitchordAuth":true,
          "settings":[{"key":"quality","default":"cd","options":[{"value":"cd","label":"CD quality"},{"value":"mp3","label":"MP3"}]}]
        }""")
        assertFalse(manifest.downloadsAllowed)
        assertTrue(manifest.requiresBitChordAuth)
        assertEquals(listOf("cd", "mp3"), manifest.qualityOptions.map { it.value })
        assertTrue(SourceFormats.detect("""{"id":"test","resources":["search"]}""", "https://example.com/token/manifest.json") is DetectedFormat.Addon)
    }

    @Test fun streamHeadersAreForwardedButEncryptedAndInvalidTransportsAreRejected() {
        val stream = playableAddonStream(AddonStream(url = "https://media.example/audio", headers = mapOf("Authorization" to "Bearer fixture")))
        assertEquals("Bearer fixture", stream.headers["Authorization"])
        assertFails { playableAddonStream(AddonStream(url = "file:///private/audio")) }
        assertFails { playableAddonStream(AddonStream(url = "https://example.com/audio", encrypted = JsonPrimitive("widevine"))) }
        assertFails { playableAddonStream(AddonStream(url = "https://example.com/audio", headers = mapOf("X-Test" to "ok\r\nX-Injected: yes"))) }
        assertFails { playableAddonStream(AddonStream(error = "Track unavailable")) }
    }

    @Test fun mobileClientEncodesTrackIdsAndForwardsConfiguredQuality() = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths += exchange.requestURI.toASCIIString()
            val body = when {
                exchange.requestURI.path.endsWith("manifest.json") -> """{"id":"fixture","resources":["search","stream"],"settings":[{"key":"quality","options":[{"value":"hires"},{"value":"cd"},{"value":"320"}]}]}"""
                exchange.requestURI.path.contains("/search") -> """{"tracks":[]}"""
                else -> """{"url":"https://media.example/audio"}"""
            }.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = AddonClient("http://127.0.0.1:${server.address.port}/secret/manifest.json", "cd")
            client.search("a&b ?", AddonClient.TIER_LOSSLESS).getOrThrow()
            client.stream("id:/?x", AddonClient.TIER_HIGH, stereoOnly = true).getOrThrow()
            assertTrue(paths.any { it.contains("/secret/search?") && it.contains("quality=cd") && it.contains("a%26b") })
            assertTrue(paths.any { it.contains("/secret/stream/id:%2F%3Fx") && it.contains("quality=320") })
            assertEquals("http://127.0.0.1/***", AddonClient.redact(client.baseUrl))
        } finally { server.stop(0) }
    }

    @Test fun androidAttestationIsExplicitlyUnavailableOnDesktop() = runBlocking {
        val client = AddonClient("https://example.com")
        val failure = client.checkAuth().exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure.message.orEmpty().contains("Android device verification"))
    }

    @Test fun downloadTierDoesNotReadTheStreamingCeiling() = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths += exchange.requestURI.toASCIIString()
            val body = when {
                exchange.requestURI.path.endsWith("manifest.json") -> """{"id":"download-quality-fixture","resources":["search","stream"],"settings":[{"key":"quality","default":"flac","options":[{"value":"flac","label":"Lossless"},{"value":"320","label":"High"},{"value":"128","label":"Low"}]}]}"""
                exchange.requestURI.path.contains("search") -> """{"tracks":[{"id":"song","title":"Fixture","artist":"Artist"}]}"""
                else -> """{"url":"https://media.example/audio.flac","codec":"flac"}"""
            }.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val previous = MobilePlayerSettings.audioQualityWifi.value
        var sourceId: String? = null
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            MobileSources.add("$base/manifest.json").getOrThrow()
            val source = MobileSources.sources.value.first { it.baseUrl.startsWith(base) }
            sourceId = source.id
            MobilePlayerSettings.setQuality(AudioQuality.LOW)
            val song = Song(videoId = "src:${source.id}::song", title = "Fixture", artist = "Artist", thumbnailUrl = null)
            MobileSources.resolveForDownload(song, DownloadQuality.LOSSLESS, export = false)
            assertTrue(paths.any { it.contains("/stream/song?") && it.contains("quality=flac") }, paths.joinToString())
            assertEquals(AudioQuality.LOW, MobilePlayerSettings.audioQualityWifi.value)
            assertFailsWith<IllegalStateException> { MobileSources.resolve(song) }
            assertFalse(paths.any { it.contains("/stream/song?") && it.contains("quality=128") }, paths.joinToString())
            assertEquals(64, com.music.bitchord.data.innertube.StreamResolver.maxKbps())
        } finally {
            sourceId?.let(MobileSources::remove)
            MobilePlayerSettings.setQuality(previous)
            server.stop(0)
        }
    }

    @Test fun mobileStreamingEligibilityDoesNotMutateSourceSwitches() {
        listOf("addon", "module").forEach { kind ->
            assertFalse(permitsMobileSourcePlayback(kind, AudioQuality.LOW))
            assertFalse(permitsMobileSourcePlayback(kind, AudioQuality.MEDIUM))
            assertFalse(permitsMobileSourcePlayback(kind, AudioQuality.HIGH))
            assertTrue(permitsMobileSourcePlayback(kind, AudioQuality.LOSSLESS))
        }
        assertFalse(permitsMobileSourcePlayback("jiosaavn", AudioQuality.MEDIUM))
        assertTrue(permitsMobileSourcePlayback("jiosaavn", AudioQuality.HIGH))
        AudioQuality.entries.forEach { assertTrue(permitsMobileSourcePlayback("youtube", it)) }
    }
}
