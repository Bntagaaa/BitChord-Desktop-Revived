package com.music.bitchord.reworked

import com.music.bitchord.playback.loopmix.LoopMixPort
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import kotlin.math.roundToLong

/** Java Sound equivalent of the mobile PrecisionAudioSink's loop splice and alignment boundary. */
internal class MobileLoopPcm(private val format: AudioFormat, val port: LoopMixPort) {
    class Packet(val bytes: ByteArray?, val floats: FloatArray?, val frames: Int, val timelineJumpFrames: Long = 0)

    private var generation = -1
    private var generating = false
    private var rendering = false
    private var nextFrame = 0L
    private var padding = 0L
    private val rate = format.sampleRate.toInt()
    private val channels = format.channels

    init {
        port.sampleRate = rate
        port.channelCount = channels
        port.precision = true
    }

    fun refresh() {
        if (generation == port.generation) return
        generation = port.generation
        generating = false; rendering = false; padding = 0
        port.tookOver = false; port.interrupted = false
        port.resetSplice()
    }

    fun next(positionMs: Double, read: () -> ByteArray?): Packet? {
        refresh()
        val out = port.outArm
        if (out != null && generating) {
            val frames = 1_024
            val samples = FloatArray(frames * 2)
            out.renderer.render(nextFrame, samples, 0, frames)
            nextFrame += frames
            return Packet(null, samples, frames)
        }
        var jump = 0L
        val incoming = port.inArm
        if (incoming != null && positionMs * 1_000 < incoming.handoverUs - 300_000) {
            val requested = port.pendingShift.get()
            if (requested < 0 && padding == 0L && port.pendingShift.compareAndSet(requested, 0)) {
                padding = -requested
                jump = requested
            }
            if (padding > 0) {
                val frames = minOf(padding, 1_024).toInt()
                padding -= frames
                return Packet(ByteArray(frames * format.frameSize), null, frames, jump)
            }
        }
        var bytes: ByteArray
        while (true) {
            bytes = read() ?: return null
            val requested = if (incoming != null && (positionMs + jump * 1_000.0 / rate) * 1_000 < incoming.handoverUs - 300_000) port.pendingShift.get() else 0
            if (requested <= 0) break
            val drop = minOf(requested, (bytes.size / format.frameSize).toLong())
            port.pendingShift.addAndGet(-drop)
            jump += drop
            if (drop * format.frameSize < bytes.size) { bytes = bytes.copyOfRange((drop * format.frameSize).toInt(), bytes.size); break }
        }
        val frames = bytes.size / format.frameSize
        val startUs = (positionMs + jump * 1_000.0 / rate) * 1_000
        if (out == null && incoming == null) return Packet(bytes, null, frames, jump)
        if (incoming != null && startUs >= incoming.handoverUs + 25_000) return Packet(bytes, null, frames, jump)
        val samples = decodePcm(bytes, format)
        if (out != null && channels == 2) {
            val start = ((startUs - out.takeoverUs) * out.sampleRate / 1_000_000).roundToLong()
            val end = start + frames
            if (end > out.firstFrame) {
                if (!rendering && start > out.firstFrame + frames) {
                    port.interrupted = true
                    return Packet(bytes, null, frames, jump)
                }
                rendering = true
                val mixed = FloatArray(frames * 2)
                out.renderer.render(start, mixed, 0, frames)
                val handover = (0.025 * rate).toLong().coerceAtLeast(1)
                for (index in 0 until frames) {
                    val frame = start + index
                    if (frame >= -LoopMixPort.SPLICE_CAPTURE && frame < 0) {
                        val at = (frame + LoopMixPort.SPLICE_CAPTURE).toInt()
                        port.spliceSong[at] = (samples[index * 2] + samples[index * 2 + 1]) * 0.5f
                        port.spliceDeck[at] = (mixed[index * 2] + mixed[index * 2 + 1]) * 0.5f
                    }
                    if (frame < -handover) continue
                    val keep = if (frame < 0) -frame.toFloat() / handover else 0f
                    samples[index * 2] = samples[index * 2] * keep + mixed[index * 2]
                    samples[index * 2 + 1] = samples[index * 2 + 1] * keep + mixed[index * 2 + 1]
                }
                if (end >= 0) {
                    nextFrame = end; generating = true
                    port.tookOver = true; port.spliceCaptured = true
                }
            }
        } else if (incoming != null) {
            val past = (startUs - incoming.handoverUs) * rate / 1_000_000
            for (frame in 0 until frames) {
                val gain = ((past + frame) / (0.025 * rate)).coerceIn(0.0, 1.0).toFloat()
                if (gain >= 1) break
                repeat(channels) { samples[frame * channels + it] *= gain }
            }
            port.processedUs = (startUs + frames * 1_000_000.0 / rate).toLong()
        }
        return Packet(null, samples, frames, jump)
    }
}

internal fun decodePcm(bytes: ByteArray, format: AudioFormat): FloatArray {
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return if (format.sampleSizeInBits > 16) FloatArray(bytes.size / 4) { input.getInt(it * 4) / 2_147_483_648f }
    else FloatArray(bytes.size / 2) { input.getShort(it * 2) / 32_768f }
}

internal fun encodePcm(samples: FloatArray, format: AudioFormat): ByteArray {
    val width = format.sampleSizeInBits / 8
    val output = ByteBuffer.allocate(samples.size * width).order(ByteOrder.LITTLE_ENDIAN)
    if (width == 4) samples.forEach { output.putInt((it.toDouble() * 2_147_483_648.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()) }
    else samples.forEach { output.putShort((it * 32_768).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()) }
    return output.array()
}
