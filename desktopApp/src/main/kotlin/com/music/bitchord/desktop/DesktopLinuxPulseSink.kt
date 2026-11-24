package com.music.bitchord.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import java.nio.ByteBuffer

/**
 * An actual PulseAudio playback stream, also supported by PipeWire's pipewire-pulse server.
 *
 * Java Sound's ALSA `default` mixer is not guaranteed to be a PulseAudio sink-input (and may
 * open a raw ALSA card). Consequently pactl move-sink-input cannot reliably route that stream.
 * Opening libpulse-simple directly on the selected sink makes the selection authoritative.
 *
 * Native methods are only loaded on Linux and only when a stream is opened; hosts without
 * libpulse can continue using Java Sound for System default and explicit ALSA mixers.
 */
internal class DesktopLinuxPulseSink private constructor(
    private val api: PulseSimple,
    private val stream: Pointer,
    val outputName: String,
    val format: DesktopPcmFormat,
) {
    private var submittedFrames = 0L
    private var playedFrames = 0L
    val bufferBytes: Int get() = format.byteRate / 5 // Requested stream target, server may adjust.

    /** Only the desktop playback thread may access this connection. */
    private val error = IntByReference()

    fun write(buffer: ByteArray, count: Int): Int =
        if (count <= 0) 0 else recordWrite(api.pa_simple_write(stream, buffer, NativeLong(count.toLong()), error), count)

    fun writeDirect(buffer: ByteBuffer, count: Int): Int =
        if (count <= 0) 0 else recordWrite(api.pa_simple_write(stream, buffer, NativeLong(count.toLong()), error), count)

    private fun recordWrite(result: Int, count: Int): Int {
        check(result >= 0) { "PulseAudio write failed (code ${error.value}, sink=$outputName)" }
        submittedFrames += count / format.frameBytes
        return count
    }

    fun framesPlayed(): Long {
        val latencyMicros = api.pa_simple_get_latency(stream, error)
        if (latencyMicros >= 0) {
            val queuedFrames = (latencyMicros.toDouble() * format.sampleRate / 1_000_000.0).toLong()
            playedFrames = maxOf(playedFrames, (submittedFrames - queuedFrames).coerceAtLeast(0L))
        }
        return playedFrames
    }

    fun flush() {
        val heard = framesPlayed()
        check(api.pa_simple_flush(stream, error) >= 0) {
            "PulseAudio flush failed (code ${error.value})"
        }
        // Frames discarded from the server are no longer pending in our clock.
        submittedFrames = heard
        playedFrames = heard
    }

    fun drain() {
        check(api.pa_simple_drain(stream, error) >= 0) {
            "PulseAudio drain failed (code ${error.value})"
        }
        playedFrames = submittedFrames
    }

    fun close() = api.pa_simple_free(stream)

    internal interface PulseSimple : Library {
        fun pa_simple_new(
            server: String?,
            name: String,
            direction: Int,
            device: String?,
            streamName: String,
            sampleSpec: SampleSpec,
            channelMap: Pointer?,
            bufferAttr: BufferAttr?,
            error: IntByReference,
        ): Pointer?

        fun pa_simple_write(stream: Pointer, data: ByteArray, bytes: NativeLong, error: IntByReference): Int
        fun pa_simple_write(stream: Pointer, data: ByteBuffer, bytes: NativeLong, error: IntByReference): Int
        fun pa_simple_get_latency(stream: Pointer, error: IntByReference): Long
        fun pa_simple_flush(stream: Pointer, error: IntByReference): Int
        fun pa_simple_drain(stream: Pointer, error: IntByReference): Int
        fun pa_simple_free(stream: Pointer)
    }

    /** C pa_sample_spec is int format, uint32_t rate, uint8_t channels (then ABI padding). */
    class SampleSpec : Structure() {
        @JvmField var format: Int = PA_SAMPLE_S16LE
        @JvmField var rate: Int = 44_100
        @JvmField var channels: Byte = 2
        override fun getFieldOrder(): List<String> = listOf("format", "rate", "channels")
    }

    /** 200ms target avoids PulseAudio's potentially multi-second default buffer. */
    class BufferAttr : Structure() {
        @JvmField var maxlength: Int = -1
        @JvmField var tlength: Int = 0
        @JvmField var prebuf: Int = -1
        @JvmField var minreq: Int = -1
        @JvmField var fragsize: Int = -1
        override fun getFieldOrder(): List<String> =
            listOf("maxlength", "tlength", "prebuf", "minreq", "fragsize")
    }

    companion object {
        private const val PA_STREAM_PLAYBACK = 1
        private const val PA_SAMPLE_S16LE = 3
        private const val PA_SAMPLE_FLOAT32LE = 5
        private const val PA_SAMPLE_S32LE = 7

        private val pulse: PulseSimple? by lazy {
            runCatching { Native.load("pulse-simple", PulseSimple::class.java) }.getOrNull()
        }

        internal fun targetName(selection: String): String? =
            selection.takeIf { it.startsWith(DesktopLinuxPulseAudio.PREFIX) }
                ?.removePrefix(DesktopLinuxPulseAudio.PREFIX)
                ?.takeIf(String::isNotBlank)

        internal fun sampleFormatFor(format: DesktopPcmFormat): Int? = when {
            format.isFloat && format.bytesPerSample == 4 -> PA_SAMPLE_FLOAT32LE
            !format.isFloat && format.bytesPerSample == 4 -> PA_SAMPLE_S32LE
            !format.isFloat && format.bytesPerSample == 2 -> PA_SAMPLE_S16LE
            else -> null
        }

        fun open(selection: String, format: DesktopPcmFormat): Result<DesktopLinuxPulseSink> = runCatching {
            require(selection.isEmpty() || targetName(selection) != null) {
                "Not a PulseAudio sink: $selection"
            }
            val sampleFormat = requireNotNull(sampleFormatFor(format)) {
                "PulseAudio does not support this sample format"
            }
            require(format.channels in 1..32 && format.sampleRate in 1..384_000) {
                "Unsupported PulseAudio PCM rate/channels"
            }
            val native = requireNotNull(pulse) {
                "libpulse-simple is unavailable (install libpulse)"
            }
            val spec = SampleSpec().apply {
                this.format = sampleFormat
                this.rate = format.sampleRate
                this.channels = format.channels.toByte()
                write()
            }
            val destination = targetName(selection)
            val attr = BufferAttr().apply {
                tlength = maxOf(format.frameBytes, format.byteRate / 5)
                write()
            }
            val error = IntByReference()
            val handle = native.pa_simple_new(
                null, "BitChord", PA_STREAM_PLAYBACK, destination, "Music",
                spec, null, attr, error,
            ) ?: error("Could not open PulseAudio sink ${destination ?: "default"} (code ${error.value})")
            DesktopLinuxPulseSink(native, handle, destination ?: "System default (PulseAudio)", format)
        }
    }
}
