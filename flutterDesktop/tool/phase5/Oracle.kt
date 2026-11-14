// Runs the actual sources extracted from Git 348b613a; no rewritten DSP oracle.
package com.music.bitchord.desktop

import com.music.bitchord.playback.manualCurve
import com.music.bitchord.playback.toneCurve
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun input(frames: Int, channels: Int): FloatArray {
    var seed = 17L
    return FloatArray(frames * channels) {
        seed = (seed * 1664525L + 1013904223L) and 0xffffffffL
        ((seed shr 16).toInt() - 32768) / 65536f
    }
}
private fun save(root: File, name: String, data: FloatArray) {
    val buffer = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    data.forEach { buffer.putFloat(it) }
    File(root, "$name.f32").writeBytes(buffer.array())
}
fun main(args: Array<String>) {
    val root = File(args.single()).apply { mkdirs() }
    for (rate in listOf(8000, 44100, 48000, 96000, 192000)) {
        for (channels in listOf(1, 2, 6)) {
            val eq = DesktopEqualizer()
            eq.setTuning(true, manualCurve(listOf(12f, -6f, 3f, -9f, 7f, -2f, 10f)), .35f)
            eq.configure(channels, rate)
            val data = input(2048, channels)
            eq.process(data, 1024 * channels)
            eq.setTuning(true, toneCurve(-4, 3, true), -.25f)
            val tail = data.copyOfRange(1024 * channels, data.size)
            eq.process(tail, tail.size)
            tail.copyInto(data, 1024 * channels)
            save(root, "eq-$rate-$channels", data)
        }
        val spatial = DesktopSpatialAudio(2, rate).apply { enabled = true }
        val data = input(4096, 2)
        spatial.process(data, data.size)
        save(root, "spatial-$rate", data)
    }
    for (rate in listOf(8000, 48000)) {
        val skipper = DesktopSilenceSkipper(2, rate).apply { enabled = true }
        val data = FloatArray((rate * 2 + 128) * 2) { if (it < 128 || it >= rate * 4) .25f else .015625f }
        val output = ArrayList<Float>()
        var at = 0
        while (at < data.size) {
            val block = data.copyOfRange(at, minOf(at + 512 * 2, data.size))
            val result = skipper.process(block, block.size)
            for (i in 0 until skipper.outputCount) output.add(result[i])
            at += block.size
        }
        save(root, "silence-$rate", output.toFloatArray())
        File(root, "silence-$rate.skipped").writeText(skipper.skippedFrames.toString() + "\n")
    }
    for (speed in listOf(.25f, .75f, 1f, 1.25f, 3f)) {
        val processor = DesktopAudioSpeed(2, 8000).apply { this.speed = speed }
        val data = input(8192, 2)
        val output = ArrayList<Float>()
        for (at in data.indices step 1024) {
            if (at == 8192) processor.speed = 1f
            val block = data.copyOfRange(at, minOf(at + 1024, data.size))
            val result = processor.process(block, block.size)
            for (i in 0 until processor.outputCount) output.add(result[i])
        }
        val rest = processor.drain()
        for (i in 0 until processor.outputCount) output.add(rest[i])
        save(root, "speed-${(speed * 100).toInt()}", output.toFloatArray())
    }
    val echo = DesktopTransitionEcho(8000, 2).apply { configure(.125) }
    val impulse = FloatArray(8000) { if (it == 0) 1f else 0f }
    for (i in impulse.indices) impulse[i] = echo.process(impulse[i], 1f, .5f)
    save(root, "echo", impulse)
    // Exercise the real reference renderProcessed -> stretch -> playhead
    // composition, not a replacement clock implementation.
    for (rate in listOf(8000, 48000)) for (tempo in listOf(.25f, .75f, 1f, 1.25f, 3f)) {
        val silence = DesktopSilenceSkipper(2, rate).apply { enabled = true }
        val speed = DesktopAudioSpeed(2, rate).apply { this.speed = tempo }
        val clock = DesktopPlayhead().apply { reset(0, 0, 1_000_000.0 / rate * tempo) }
        val data = FloatArray((rate * 2 + 128) * 2) { if (it < 128 || it >= rate * 4) .25f else .015625f }
        val output = ArrayList<Float>()
        val times = ArrayList<Long>()
        var written = 0L
        fun collect(pcm: FloatArray, count: Int, skipped: Long) {
            clock.change(written, 1_000_000.0 / rate * tempo, skipped * 1_000_000.0 / rate)
            for (i in 0 until count) output.add(pcm[i])
            for (f in 0 until count / 2) times.add(clock.peek(written + f))
            written += count / 2
        }
        for (at in data.indices step 1024) {
            val block = data.copyOfRange(at, minOf(at + 1024, data.size))
            val before = silence.skippedFrames
            val quiet = silence.process(block, block.size)
            val stretched = speed.process(quiet, silence.outputCount)
            collect(stretched, speed.outputCount, silence.skippedFrames - before)
        }
        val rest = speed.drain()
        collect(rest, speed.outputCount, 0)
        val name = "pipeline-$rate-${(tempo * 100).toInt()}"
        save(root, name, output.toFloatArray())
        val bytes = ByteBuffer.allocate(times.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        times.forEach { bytes.putLong(it) }
        File(root, "$name.clock").writeBytes(bytes.array())
    }
}
