package com.music.bitchord.reworked

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MobileVersionAlignerTest {
    @Test fun nativeDecodeAndMobileFingerprintFindAnExtraIntroAndCacheTheReverse() = runBlocking {
        val rate = 11_025
        val random = Random(42)
        val levels = FloatArray(100) { random.nextFloat() * 0.6f + 0.1f }
        val samples = ShortArray(rate * 10) { index ->
            (sin(2 * PI * 440 * index / rate) * levels[(index * 10 / rate).coerceAtMost(99)] * 30_000).toInt().toShort()
        }
        val delayed = ShortArray(rate * 2) + samples
        val first = wav(samples, rate)
        val second = wav(delayed, rate)
        try {
            val source = Song(UUID.randomUUID().toString(), "Album", "Fixture", null, "0:10", localPath = first.toString())
            val target = Song(UUID.randomUUID().toString(), "Video intro", "Fixture", null, "0:12", localPath = second.toString())
            val offset = assertNotNull(withTimeout(20_000) { MobileVersionAligner.findOffsetMs(source, target) })
            assertTrue(abs(offset - 2_000L) <= 25, "The target's two-second intro must be measured with the correct sign: $offset")
            assertEquals(-offset, MobileVersionAligner.getCachedOffsetMs(target.videoId, source.videoId))
            assertEquals(MobileVersionAligner.CutPhase.ALIGNED, MobileVersionAligner.status.value?.phase)
        } finally { Files.deleteIfExists(first); Files.deleteIfExists(second) }
    }

    @Test fun constantSilenceDoesNotBecomeAConfidentZeroOffset() {
        val result = MobileVersionAligner.crossCorrelate(FloatArray(1_000), FloatArray(1_000))
        assertTrue(result.confidence < 0.4)
    }

    private fun wav(samples: ShortArray, rate: Int): java.nio.file.Path {
        val size = samples.size * 2
        val bytes = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size)
        samples.forEach(bytes::putShort)
        return Files.createTempFile("reworked-version-", ".wav").also { Files.write(it, bytes.array()) }
    }
}
