package com.music.bitchord.reworked

import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.TrackAnalysisState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.sampled.AudioFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Executes the actual mobile C++/Signalsmith JNI renderer, not a renderer mock. */
class MobileLoopTest {
    @Test fun preparedNativeLoopHandsTransportToTheExistingIncomingPeer() = runBlocking {
        val file = stereoTone()
        val lines = CopyOnWriteArrayList<MobileCrossfadeTest.VirtualLine>()
        val analysis = TrackAnalysis(status = "ready", duration = 90.0, bpm = 120.0,
            beatInterval = 0.5, beatConfidence = 1.0, downbeats = (0..45).map { it * 2.0 },
            contentEndTime = 90.0,
            vocalWindows = listOf(CurveWindow(0.0, 10.0, FloatArray(900))),
            levelWindows = listOf(CurveWindow(0.0, 10.0, FloatArray(900) { 0.8f })))
        try {
            DesktopPlayback(analysisFor = { analysis }, analysisStateFor = { TrackAnalysisState.ANALYSED },
                outputFactory = { rate, channels, bits, _ ->
                    MobileCrossfadeTest.VirtualLine(AudioFormat(rate.toFloat(), bits, channels, true, false), 8).also(lines::add).line
                }).use { player ->
                player.setAutomixEnabled(true); player.setAdvancedAutomixEnabled(true)
                player.playSongs(listOf("loop-out", "loop-in").map { Song(it, it, "Fixture", null, "1:30", localPath = file.toString()) })
                player.seek(60_000)
                withTimeout(30_000) { player.state.first { it.transition == "Advanced loop mix" } }
                assertEquals(2, lines.size)
                val next = withTimeout(30_000) { player.state.first { it.song?.videoId == "loop-in" && it.transition == null } }
                assertNull(next.error)
                assertTrue(next.positionMs >= 8_000, "The incoming timeline must continue where its native deck released it")
                assertTrue(next.isPlaying)
                assertTrue(lines.first().closed.get())
                assertFalse(lines.last().closed.get())
                assertEquals(2, lines.size, "Native release must keep the prepared incoming output")
            }
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun nativeDecksFollowTheMobileClockAndReleaseIntoTheIncomingSong() {
        assertTrue(LoopMixRenderer.available, "The packaged mobile loop renderer must load")
        val rate = 22_050
        val plan = LoopMix.Plan(
            LoopMix.Pick(10.0, 12.0, 1, 0.5, 120.0, 0.0, LoopMix.Check.MASK),
            LoopMix.Pick(2.0, 4.0, 1, 0.5, 120.0, 0.0, LoopMix.Check.MASK),
            fold = 1.0, enterBars = 1, rampBars = 2, exitBars = 1, totalBars = 4,
        )
        val session = assertNotNull(LoopMixSession.build(plan, "out", "in", rate, 1f) { side, from, to ->
            val count = ((to - from) * rate).toInt() + 1
            val frequency = if (side == LoopMix.Side.OUT) 440.0 else 660.0
            val pcm = FloatArray(count) { (0.12 * sin(2 * PI * frequency * (from + it.toDouble() / rate))).toFloat() }
            LoopRegion(pcm, pcm.copyOf(), from)
        })
        try {
            val out = LoopMixPort()
            val incoming = LoopMixPort()
            session.attach(out, incoming)
            assertTrue(out.outArm != null)
            var frame = session.renderer.firstFrame()
            assertTrue(frame < 0)
            var energy = 0.0
            var peak = 0f
            while (frame < (session.clock.elementAt + 0.1) * rate) {
                val pcm = FloatArray(1_024 * 2)
                session.renderer.render(frame, pcm, 0, 1_024)
                assertTrue(pcm.all(Float::isFinite))
                for (sample in pcm) { energy += sample * sample; peak = maxOf(peak, abs(sample)) }
                frame += 1_024
            }
            assertTrue(energy > rate * 0.001, "Both native decks must produce real audio")
            assertTrue(peak < 2f, "The native mix must remain bounded before the output limiter")
            assertTrue(session.renderer.heard(LoopMixRenderer.IN) >= session.handoverTrack - 0.1)
        } finally { session.close() }
    }

    @Test fun incomingAlignmentInsertsAndDropsFramesWithoutLeakingMutedAudio() {
        val format = AudioFormat(48_000f, 32, 2, true, false)
        val port = LoopMixPort()
        val boundary = MobileLoopPcm(format, port)
        port.armIncoming(LoopMixPort.InArm(4_000_000))
        boundary.refresh()
        port.requestShift(-2_048)
        val bytes = encodePcm(FloatArray(1_024 * 2) { 0.25f }, format)
        var reads = 0
        val inserted = assertNotNull(boundary.next(0.0) { reads++; bytes })
        assertEquals(-2_048L, inserted.timelineJumpFrames)
        assertEquals(0, reads)
        assertTrue(checkNotNull(inserted.bytes).all { it == 0.toByte() })
        val second = assertNotNull(boundary.next(-21.333) { reads++; bytes })
        assertEquals(0L, second.timelineJumpFrames)
        assertEquals(0, reads)
        port.requestShift(500)
        val dropped = assertNotNull(boundary.next(0.0) { reads++; bytes })
        assertEquals(500L, dropped.timelineJumpFrames)
        assertEquals(524, dropped.frames)
        assertTrue(checkNotNull(dropped.floats).all { it == 0f }, "The hidden peer must stay silent until handover")
        val released = assertNotNull(boundary.next(4_030.0) { bytes })
        assertTrue(decodePcm(checkNotNull(released.bytes), format).all { abs(it - 0.25f) < 0.0001f })
        port.disarm()
        val normal = assertNotNull(boundary.next(0.0) { bytes })
        assertTrue(checkNotNull(normal.bytes).contentEquals(bytes))
    }

    @Test fun deviceClockAppliesAlignmentOnlyWhenTheSpliceIsHeard() {
        val clock = AudioDeviceClock(48_000.0)
        clock.reset(0, 0.0)
        clock.mark(48_000, 1_500.0, 1.0)
        assertEquals(500.0, clock.position(24_000), 0.001)
        assertEquals(1_500.0, clock.position(48_000), 0.001)
        clock.mark(96_000, 1_000.0, 1.0)
        assertEquals(2_000.0, clock.position(72_000), 0.001)
        assertEquals(1_000.0, clock.position(96_000), 0.001)
    }

    private fun stereoTone(): java.nio.file.Path {
        val rate = 22_050
        val frames = rate * 90
        val size = frames * 4
        val data = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(size)
        repeat(frames) { frame ->
            val sample = (sin(2 * PI * 440 * frame / rate) * 8_000).toInt().toShort()
            data.putShort(sample).putShort(sample)
        }
        return Files.createTempFile("reworked-native-loop-", ".wav").also { Files.write(it, data.array()) }
    }
}
