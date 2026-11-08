package com.music.bitchord.reworked

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.NativeAnalysisLibrary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileAnalyzerTest {
    @Test fun windowsCoverHeadsAndTailsWithBoundedOverlaps() {
        val windows = modelWindows(44.6, 133.6, 200.0, 22.8)
        assertEquals(5, windows.size)
        assertEquals(0.0, windows.first().first)
        assertEquals(200.0, windows.last().second)
        assertTrue(windows.all { (start, end) -> start.isFinite() && end > start && end - start <= 22.80001 })
        assertTrue(modelWindows(22.800001, 0.0, 22.800001, 22.8).all { it.first.isFinite() })
    }

    @Test fun packagedMobileAnalyzerMeasuresARealDecodedFixture() = runBlocking {
        val rate = 44_100
        val seconds = 20
        val count = rate * seconds
        val wav = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(count * 2)
        repeat(count) { sample ->
            val t = sample.toDouble() / rate
            val beat = t % 0.5
            val kick = exp(-beat * 25) * sin(2 * PI * 80 * t)
            val chord = 0.08 * sin(2 * PI * 220 * t) + 0.06 * sin(2 * PI * 330 * t)
            wav.putShort(((kick * 0.7 + chord) * 30_000).toInt().coerceIn(-32_767, 32_767).toShort())
        }
        val path = Files.createTempFile("reworked-model-fixture-", ".wav")
        try {
            Files.write(path, wav.array())
            MobileTrackAnalyzer().use { analyzer ->
                assertTrue(NativeAnalysisLibrary.available, "The mobile JNI analyzer must ship with the desktop package")
                val song = Song("fixture-${UUID.randomUUID()}", "Synthetic beat fixture", "Test", null, "0:20", localPath = path.toString())
                analyzer.request(song)
                val result = withTimeout(120_000) { analyzer.status.first { status ->
                    status[song.videoId]?.let { it.startsWith("Analyzed:") || it.startsWith("Crossfade fallback:") } == true
                } }.getValue(song.videoId)
                assertTrue(result.startsWith("Analyzed:"), result)
                val analysis = analyzer.analysisFor(song)
                assertTrue(analysis.duration in 19.9..20.1)
                assertTrue(analysis.energyCurve.isNotEmpty())
                assertTrue(analysis.levelWindows.isNotEmpty())
                assertTrue(analysis.vocalWindows.isNotEmpty(), "The packaged open-unmix model must produce measured vocal windows")
            }
        } finally { Files.deleteIfExists(path) }
    }
}
