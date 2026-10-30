package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Optional generated fixtures; see docs/performance/instant-playback.md for the exact command. */
class DesktopPlaybackCodecMatrixTest {
    @Test fun `real codecs and manifests open decode and seek without reduced probing`() {
        val directory = System.getenv("BITCHORD_PLAYBACK_CODEC_FIXTURES")?.let(Path::of) ?: return
        for ((file, transport) in listOf("tone.wav" to null, "tone.flac" to null, "tone.opus" to null,
            "tone.mp3" to null, "tone.m4a" to null, "tone.m3u8" to "hls", "tone.mpd" to "dash")) {
            val path = directory.resolve(file)
            assertTrue(Files.isRegularFile(path), "missing $path")
            val decoder = DesktopAudioDecoder()
            try {
                decoder.open(path.toString(), requested = DesktopPcmFormat(48000, 2, 4, true), transport = transport).getOrThrow()
                assertNotNull(decoder.readSamples(), file)
                assertTrue(decoder.sampleCount > 0, file)
                assertTrue(decoder.seek(2_000_000), file)
                assertNotNull(decoder.readSamples(), "seek $file")
                assertTrue(decoder.sampleCount > 0, file)
                println("CODEC_MATRIX PASS $file codec=${decoder.measuredFormat?.codec} rate=${decoder.outputFormat.sampleRate}")
            } finally { decoder.close() }
        }
    }
}
