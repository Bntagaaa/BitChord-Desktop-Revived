package com.music.bitchord.desktop

import org.junit.Test
import java.io.IOException
import kotlin.test.*

class DesktopYtDlpResolverTest {
    private fun fixture(
        protocol: String = "https", url: String = "https://r1.googlevideo.com/videoplayback?expire=2000000000",
        bitrate: String = "160.4", videoCodec: String = "none", extra: String = "",
    ): String = """{
        "id":"abcdefghijk", "url":"$url", "protocol":"$protocol",
        "vcodec":"$videoCodec", "acodec":"opus", "ext":"webm", "abr":$bitrate,
        "duration":123.4,
        "http_headers":{"User-Agent":"Test/1", "Referer":"https://music.youtube.com/",
        "Cookie":"must-not-leak", "Authorization":"must-not-leak", "Range":"bytes=0-"}
        $extra
    }"""

    @Test fun readsDirectAudioAndSafeHeaders() {
        val stream = DesktopYtDlpResolver.parse(fixture(), "abcdefghijk", Int.MAX_VALUE)
        assertEquals("youtube", stream.sourceId)
        assertEquals("webm", stream.format.codec)
        assertEquals(160, stream.format.kbps)
        assertEquals(123, stream.durationSec)
        assertTrue(stream.windowedReads)
        assertEquals("Test/1", stream.headers["User-Agent"])
        assertEquals(setOf("User-Agent", "Referer"), stream.headers.keys)
    }

    @Test fun acceptsLowQualityButRejectsHigherOrUnknownBitrate() {
        DesktopYtDlpResolver.parse(fixture(bitrate = "48.5"), "abcdefghijk", 64)
        for (bitrate in listOf("65", "null")) assertFailsWith<IOException> {
            DesktopYtDlpResolver.parse(fixture(bitrate = bitrate), "abcdefghijk", 64)
        }
    }

    @Test fun refusesMuxedAndFragmentBasedTransports() {
        for (input in listOf(
            fixture(protocol = "m3u8_native"), fixture(protocol = "http_dash_segments"),
            fixture(videoCodec = "avc1"), fixture(extra = ",\"fragments\":[{}]"),
            fixture(extra = ",\"requested_formats\":[{}]"), fixture(extra = ",\"has_drm\":true"),
        )) assertFailsWith<IOException> { DesktopYtDlpResolver.parse(input, "abcdefghijk", Int.MAX_VALUE) }
    }

    @Test fun refusesUnexpectedIdsUrlsAndMalformedJson() {
        for (url in listOf("file:///etc/passwd", "http://r1.googlevideo.com/a", "https://googlevideo.com.evil.test/a", "https://user@r1.googlevideo.com/a")) {
            assertFailsWith<IOException> { DesktopYtDlpResolver.parse(fixture(url = url), "abcdefghijk", Int.MAX_VALUE) }
        }
        assertFailsWith<IOException> { DesktopYtDlpResolver.parse(fixture(), "differentID", Int.MAX_VALUE) }
        assertFailsWith<IOException> { DesktopYtDlpResolver.parse("not json secret-cookie", "abcdefghijk", Int.MAX_VALUE) }
    }
}
