package com.music.bitchord.desktop

import com.music.bitchord.playback.loopmix.*
import com.music.bitchord.playback.smart.LoopMix
import kotlin.math.*
import kotlin.test.*

class DesktopLoopRendererTest {
    @Test fun regionDecodingUsesRequestedRateAndHonorsCancellation() {
        val file = java.nio.file.Files.createTempFile("loop-region", ".wav").toFile()
        try {
            val rate = 48000
            val bytes = java.nio.ByteBuffer.allocate(rate * 3 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(rate * 3) { i ->
                val sample = (sin(2 * PI * 440 * i / rate) * 10000).toInt().toShort()
                bytes.putShort(sample); bytes.putShort(sample)
            }
            javax.sound.sampled.AudioInputStream(java.io.ByteArrayInputStream(bytes.array()),
                javax.sound.sampled.AudioFormat(48000f, 16, 2, true, false), (rate * 3).toLong()).use {
                javax.sound.sampled.AudioSystem.write(it, javax.sound.sampled.AudioFileFormat.Type.WAVE, file)
            }
            DesktopAnalysisRuntime.ensureStarted()
            val region = assertNotNull(desktopLoopRegion(DesktopStream(file.absolutePath), 1.25, 2.25, 44100) { false })
            assertEquals(1.25, region.start)
            assertTrue(abs(region.left.size - 44100) <= 2)
            assertContentEquals(region.left, region.right)
            assertFailsWith<java.util.concurrent.CancellationException> {
                desktopLoopRegion(DesktopStream(file.absolutePath), 0.0, 1.0, 44100) { true }
            }
            assertNotNull(desktopLoopRegion(DesktopStream(file.absolutePath), 0.0, .2, 44100) { false })
        } finally { file.delete() }
    }

    @Test fun vocalModelReturnsRawWindowMeasurements() {
        DesktopAnalysisRuntime.ensureStarted()
        val tracker = com.music.bitchord.playback.smart.VocalTracker(
            { DesktopAnalysisRuntime.modelPath(com.music.bitchord.playback.smart.VocalTracker.MODEL_ASSET) }, { 1 })
        try {
            val samples = FloatArray(44100 * 2) { (.1 * sin(2 * PI * 440 * it / 44100)).toFloat() }
            val result = assertNotNull(tracker.track(samples, samples, 44100.0), "Vocal model could not run")
            assertTrue(result.isNotEmpty())
            assertTrue(result.all { it.isFinite() && it in 0f..1f })
        } finally { tracker.release() }
    }

    @Test fun incompleteRegionsNeverCreateAPlayableSession() {
        val pick = LoopMix.Pick(1.0, 3.0, 1, .5, 120.0, 0.0, LoopMix.Check.MASK)
        val plan = LoopMix.Plan(pick, pick, 1.0, 1, 1, 1, 3)
        assertNull(LoopMixSession.build(plan, "a", "b", 44100, 1f) { _, _, _ ->
            LoopRegion(FloatArray(100), FloatArray(100), 0.0)
        })
        assertNull(LoopMixSession.build(plan, "a", "b", 44100, 1f) { _, _, _ ->
            LoopRegion(FloatArray(441000), FloatArray(1), 0.0)
        })
    }

    @Test fun nativeRendererProducesFiniteAudioAndClosesRepeatedly() {
        assertTrue(LoopMixRenderer.available, "Native loop renderer is missing")
        val out = LoopMix.Pick(1.0, 3.0, 1, .5, 120.0, 0.0, LoopMix.Check.MASK)
        val inn = LoopMix.Pick(1.0, 3.0, 1, .5, 120.0, 0.0, LoopMix.Check.MASK)
        val plan = LoopMix.Plan(out, inn, 1.0, 1, 1, 1, 3)
        repeat(3) {
            val session = assertNotNull(LoopMixSession.build(plan, "a", "b", 44100, 1f) { _, from, to ->
                val start = from.coerceAtLeast(0.0)
                val samples = FloatArray(((to - start) * 44100).toInt()) { i -> (.2 * sin(2 * PI * 440 * (start + i / 44100.0))).toFloat() }
                LoopRegion(samples, samples.copyOf(), start)
            })
            try {
                assertFailsWith<IllegalArgumentException> { session.renderer.render(0, FloatArray(2), 0, 2) }
                assertFailsWith<IllegalArgumentException> { session.renderer.render(0, FloatArray(2), -1, 1) }
                val block = FloatArray(2048)
                var peak = 0f
                var frame = session.renderer.firstFrame()
                while (frame < session.clock.elementAt * 44100) {
                    // Include the exact final handover and deliberately vary block boundaries.
                    val count = minOf(if (frame < 0) 1024 else 513,
                        ceil(session.clock.elementAt * 44100 - frame).toInt())
                    session.renderer.render(frame, block, 0, count)
                    assertTrue(block.take(count * 2).all { it.isFinite() })
                    peak = max(peak, block.take(count * 2).maxOf { abs(it) })
                    frame += count
                }
                assertEquals(session.clock.elementTrackTime, session.renderer.heard(LoopMixRenderer.IN), .002)
                assertTrue(peak > .01f && peak < 1f, "Unexpected rendered peak $peak")
            } finally { session.close(); session.close() }
            assertFalse(session.renderer.isValid)
            assertFailsWith<IllegalStateException> { session.renderer.render(0, FloatArray(2), 0, 1) }
        }
    }
}
