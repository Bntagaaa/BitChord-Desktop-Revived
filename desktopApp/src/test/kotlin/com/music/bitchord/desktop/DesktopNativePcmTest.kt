package com.music.bitchord.desktop

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import org.junit.Assume.assumeTrue

class DesktopNativePcmTest {
    private val formats = listOf(
        DesktopPcmFormat(48_000, 2, 2),
        DesktopPcmFormat(48_000, 2, 4),
        DesktopPcmFormat(48_000, 2, 4, isFloat = true),
    )

    @Test
    fun nativeSamplesMatchTheFallbackByteForByte() {
        val random = Random(132)
        val samples = floatArrayOf(0f, -0f, 1f, -1f, Float.NaN, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE) +
            FloatArray(8192) { random.nextFloat() * 3f - 1.5f }
        for (format in formats) {
            val native = assertNotNull(DesktopNativePcm.open(format))
            val expected = ByteArray(samples.size * format.bytesPerSample)
            for (gain in listOf(0f, .37f, 1f, 1.5f)) {
                packDesktopPcm(samples, samples.size, gain, format, expected)
                val result = assertNotNull(native.pack(samples, samples.size, gain))
                assertContentEquals(expected, ByteArray(expected.size).also { result.duplicate().get(it) })
                assertSame(result, native.pack(samples, samples.size / 2, gain))
            }
        }
    }

    @Test
    fun directPcmReachesAPulseAudioSink() {
        assumeTrue(System.getenv("BITCHORD_TEST_NATIVE_PULSE") == "1")
        val samples = FloatArray(4096) { (kotlin.math.sin(it * .1) * .2).toFloat() }
        for (format in formats) {
            val sink = DesktopLinuxPulseSink.open("", format).getOrThrow()
            try {
                val native = assertNotNull(DesktopNativePcm.open(format))
                val converted = assertNotNull(native.pack(samples, samples.size, 1f))
                val bytes = samples.size * format.bytesPerSample
                assertEquals(bytes, sink.writeDirect(converted, bytes))
                sink.drain()
                assertEquals((samples.size / format.channels).toLong(), sink.framesPlayed())
            } finally {
                sink.close()
            }
        }
    }

    @Test
    fun benchmarkNativePcm() {
        assumeTrue(System.getenv("BITCHORD_BENCHMARK_NATIVE_PCM") == "1")
        val samples = FloatArray(4096) { kotlin.math.sin(it * .1).toFloat() }
        for (format in formats) {
            val expected = ByteArray(samples.size * format.bytesPerSample)
            val native = assertNotNull(DesktopNativePcm.open(format))
            var checksum = 0
            fun measure(iterations: Int, action: (Int) -> Unit): Double {
                val start = System.nanoTime()
                repeat(iterations, action)
                return (System.nanoTime() - start).toDouble() / iterations
            }
            val jvmPack: (Int) -> Unit = {
                packDesktopPcm(samples, samples.size, .7f, format, expected)
                checksum += expected[it % expected.size]
            }
            val nativePack: (Int) -> Unit = {
                checksum += checkNotNull(native.pack(samples, samples.size, .7f)).get(it % expected.size)
            }
            measure(5000, jvmPack)
            measure(5000, nativePack)
            val timings = List(5) { measure(20_000, jvmPack) to measure(20_000, nativePack) }
            println("PCM_PACK bytes=${format.bytesPerSample} float=${format.isFloat} " +
                "jvm_ns=${timings.map { it.first }.sorted()[2]} " +
                "native_ns=${timings.map { it.second }.sorted()[2]} checksum=$checksum")
        }
    }
}
