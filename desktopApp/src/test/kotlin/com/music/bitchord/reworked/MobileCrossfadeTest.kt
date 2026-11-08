package com.music.bitchord.reworked

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Native decoding and both transport peers, with a deterministic virtual audio device. */
class MobileCrossfadeTest {
    @Test fun queueBoundaryKeepsOneOutputOpenAndDoesNotDrainBetweenTracks() = runBlocking {
        val file = tone()
        val lines = CopyOnWriteArrayList<VirtualLine>()
        try {
            DesktopPlayback(outputFactory = { rate, channels, bits, _ ->
                VirtualLine(AudioFormat(rate.toFloat(), bits, channels, true, false)).also(lines::add).line
            }).use { player ->
                player.playSongs(listOf(song("gapless-a", file), song("gapless-b", file)))
                withTimeout(10_000) { player.state.first { it.song?.videoId == "gapless-b" && it.positionMs > 500 } }
                assertEquals(1, lines.size, "Gapless handoff must reuse the exact same device queue")
                assertEquals(0L, lines.single().drains.get(), "Draining the old track would leave the next decoder too late")
                assertFalse(lines.single().closed.get())
                withTimeout(10_000) { player.state.first { it.song?.videoId == "gapless-b" && !it.isPlaying } }
                assertNull(player.state.value.error)
                assertEquals(1L, lines.single().drains.get())
                assertEquals(44_100L * 8, lines.single().frames.get(), "All decoded frames must cross one uninterrupted output timeline")
            }
        } finally { Files.deleteIfExists(java.nio.file.Path.of(file)) }
    }

    @Test fun twoTracksOverlapAndPauseTogetherBeforeMidpointHandoff() = runBlocking {
        val file = tone()
        val lines = CopyOnWriteArrayList<VirtualLine>()
        try {
            DesktopPlayback(outputFactory = { rate, channels, bits, _ ->
                VirtualLine(AudioFormat(rate.toFloat(), bits, channels, true, false)).also(lines::add).line
            }).use { player ->
                player.setCrossfadeSeconds(2)
                player.playSongs(listOf(song("first", file), song("second", file)))
                withTimeout(10_000) { player.state.first { it.transitionProgress in 0.05f..0.35f } }
                assertEquals(2, lines.size)
                assertTrue(lines.all { it.frames.get() > 0 })
                player.pause()
                assertTrue(lines.all { !it.running.get() })
                val paused = player.state.value.positionMs
                delay(50)
                assertTrue(player.state.value.positionMs <= paused + 50)
                player.resume()
                val handed = withTimeout(5_000) { player.state.first { it.song?.videoId == "second" && it.transition != null } }
                assertEquals(1, handed.queueIndex)
                assertTrue(handed.positionMs > 0, "The second peer should already be audible at handoff")
                val ended = withTimeout(5_000) { player.state.first { it.song?.videoId == "second" && !it.isPlaying } }
                assertNull(ended.error)
                assertNull(ended.transition)
                assertEquals(2, lines.size, "Handoff must keep the existing decoder/output peer")
            }
        } finally { Files.deleteIfExists(java.nio.file.Path.of(file)) }
    }

    @Test fun clearDuringAnOverlapClosesBothPeersAndLeavesNoLateHandoff() = runBlocking {
        val file = tone()
        val lines = CopyOnWriteArrayList<VirtualLine>()
        try {
            DesktopPlayback(outputFactory = { rate, channels, bits, _ ->
                VirtualLine(AudioFormat(rate.toFloat(), bits, channels, true, false)).also(lines::add).line
            }).use { player ->
                player.setCrossfadeSeconds(2)
                player.playSongs(listOf(song("first", file), song("second", file)))
                withTimeout(10_000) { player.state.first { it.transitionProgress > 0.1f } }
                player.clearQueue()
                delay(100)
                assertNull(player.state.value.song)
                assertTrue(player.state.value.queue.isEmpty())
                assertFalse(player.state.value.isPlaying)
                assertTrue(lines.all { it.closed.get() })
            }
        } finally { Files.deleteIfExists(java.nio.file.Path.of(file)) }
    }

    private fun song(id: String, file: String) = Song(id, id, "Fixture", null, "0:04", localPath = file)

    private fun tone(): String {
        val count = 44_100 * 4
        val bytes = count * 2
        val wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(44_100).putInt(88_200)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes)
        repeat(count) { wav.putShort((sin(2 * PI * 440 * it / 44_100) * 10_000).toInt().toShort()) }
        return Files.createTempFile("reworked-crossfade-", ".wav").also { Files.write(it, wav.array()) }.toString()
    }

    internal class VirtualLine(private val format: AudioFormat, private val writeDelayMs: Long = 3) {
        val running = AtomicBoolean()
        val closed = AtomicBoolean()
        val frames = AtomicLong()
        val drains = AtomicLong()
        val line = Proxy.newProxyInstance(
            SourceDataLine::class.java.classLoader,
            arrayOf(SourceDataLine::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getFormat" -> format
                "getLongFramePosition" -> frames.get()
                "getFramePosition" -> frames.get().toInt()
                "getMicrosecondPosition" -> (frames.get() * 1_000_000 / format.sampleRate).toLong()
                "isRunning", "isActive" -> running.get()
                "isOpen" -> !closed.get()
                "start" -> { running.set(true); null }
                "stop" -> { running.set(false); null }
                "close" -> { closed.set(true); running.set(false); null }
                "drain" -> { drains.incrementAndGet(); null }
                "flush", "open", "addLineListener", "removeLineListener" -> null
                "write" -> {
                    while (!running.get() && !closed.get()) Thread.sleep(1)
                    if (closed.get()) 0 else {
                        Thread.sleep(writeDelayMs)
                        val bytes = minOf(arguments!![2] as Int, 1_024 * format.frameSize)
                        frames.addAndGet((bytes / format.frameSize).toLong())
                        bytes
                    }
                }
                "toString" -> "Virtual mobile audio output"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SourceDataLine
    }
}
