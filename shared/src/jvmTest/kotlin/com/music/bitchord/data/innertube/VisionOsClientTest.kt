package com.music.bitchord.data.innertube

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisionOsClientTest {

    @AfterTest
    fun tearDown() {
        VisionOsClient.clearCache()
        VisionOsClient.playerEndpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        VisionOsClient.httpClientProvider = { OkHttpClient() }
    }

    @Test
    fun `parsePlayerResponse extracts audio, video, and loudnessDb`() {
        val futureExpire = (System.currentTimeMillis() / 1000L) + 3600L
        val payload = """
            {
              "playabilityStatus": { "status": "OK" },
              "videoDetails": { "isLiveContent": false },
              "playerConfig": { "audioConfig": { "loudnessDb": -4.2 } },
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "itag": 251,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=251&expire=$futureExpire",
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "averageBitrate": 130000
                  },
                  {
                    "itag": 140,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=140&expire=$futureExpire",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "averageBitrate": 129000
                  },
                  {
                    "itag": 137,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=137&expire=$futureExpire",
                    "mimeType": "video/mp4; codecs=\"avc1.640028\"",
                    "averageBitrate": 2400000,
                    "width": 1920,
                    "height": 1080
                  },
                  {
                    "itag": 247,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=247&expire=$futureExpire",
                    "mimeType": "video/webm; codecs=\"vp9\"",
                    "averageBitrate": 1200000,
                    "width": 1280,
                    "height": 720
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = assertNotNull(VisionOsClient.parsePlayerResponse("vid1", payload))
        assertEquals(-4.2, parsed.loudnessDb)
        assertEquals(4, parsed.formats.size)

        val opus = assertNotNull(VisionOsClient.pickAudioFormat(parsed.formats, Int.MAX_VALUE, requireM4a = false))
        assertEquals(251, opus.itag)

        val m4a = assertNotNull(VisionOsClient.pickAudioFormat(parsed.formats, Int.MAX_VALUE, requireM4a = true))
        assertEquals(140, m4a.itag)

        val video1080 = assertNotNull(VisionOsClient.pickVideoFormat(parsed.formats, 1080))
        assertEquals(137, video1080.itag)
        assertEquals(1080, video1080.height)
    }

    @Test
    fun `parsePlayerResponse rejects non-OK, live, and cipher-only responses`() {
        assertNull(VisionOsClient.parsePlayerResponse("v1", """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}"""))
        assertNull(
            VisionOsClient.parsePlayerResponse(
                "v2",
                """{"playabilityStatus":{"status":"OK"},"videoDetails":{"isLive":true},"streamingData":{"adaptiveFormats":[{"itag":251,"url":"https://x/y","mimeType":"audio/webm"}]}}""",
            ),
        )
        assertNull(
            VisionOsClient.parsePlayerResponse(
                "v3",
                """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":251,"signatureCipher":"s=abc","mimeType":"audio/webm"}]}}""",
            ),
        )
    }

    @Test
    fun `resolveAudio and resolveVideo share one player HTTP call`() = runBlocking {
        val futureExpire = (System.currentTimeMillis() / 1000L) + 3600L
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "playabilityStatus": { "status": "OK" },
                  "streamingData": {
                    "adaptiveFormats": [
                      {
                        "itag": 251,
                        "url": "https://rr1.googlevideo.com/videoplayback?itag=251&expire=$futureExpire",
                        "mimeType": "audio/webm; codecs=\"opus\"",
                        "averageBitrate": 131000
                      },
                      {
                        "itag": 137,
                        "url": "https://rr1.googlevideo.com/videoplayback?itag=137&expire=$futureExpire",
                        "mimeType": "video/mp4; codecs=\"avc1.640028\"",
                        "averageBitrate": 2500000,
                        "width": 1920,
                        "height": 1080
                      }
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )
        server.start()
        try {
            VisionOsClient.playerEndpoint = server.url("/youtubei/v1/player").toString()
            val testClient = OkHttpClient()
            VisionOsClient.httpClientProvider = { testClient }

            val audio = assertNotNull(
                VisionOsClient.resolveAudio("vidShared", Int.MAX_VALUE, false, "CgTestVisitor", "en", "US"),
            )
            assertEquals("VISIONOS", audio.clientName)
            assertEquals(131, audio.kbps)

            val video = assertNotNull(
                VisionOsClient.resolveVideo("vidShared", 1080, "CgTestVisitor", "en", "US"),
            )
            assertEquals(1080, video.height)
            assertEquals(1, server.requestCount)

            val recorded = server.takeRequest()
            assertEquals("101", recorded.getHeader("X-YouTube-Client-Name"))
            assertTrue(recorded.body.readUtf8().contains("\"VISIONOS\""))
        } finally {
            server.shutdown()
        }
    }
}
