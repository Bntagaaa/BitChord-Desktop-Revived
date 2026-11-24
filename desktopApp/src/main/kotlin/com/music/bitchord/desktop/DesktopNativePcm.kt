package com.music.bitchord.desktop

import java.nio.ByteBuffer

/** Packs PCM into storage that libpulse can consume directly via JNA. */
internal class DesktopNativePcm private constructor(private val format: DesktopPcmFormat) {
    private var output = ByteBuffer.allocateDirect(0)
    private var enabled = true

    fun pack(samples: FloatArray, count: Int, gain: Float): ByteBuffer? {
        if (!enabled || count <= 0) return null
        val bytes = count * format.bytesPerSample
        if (output.capacity() < bytes) output = ByteBuffer.allocateDirect(bytes)
        return try {
            output.takeIf { nativePack(samples, count, gain, format.bytesPerSample, format.isFloat, it) }
        } catch (_: UnsatisfiedLinkError) {
            // A development build can still load a JNI library without the packer.
            enabled = false
            null
        }
    }

    companion object {
        fun open(format: DesktopPcmFormat): DesktopNativePcm? =
            if (DesktopAnalysisRuntime.available) DesktopNativePcm(format) else null

        @JvmStatic private external fun nativePack(
            samples: FloatArray, count: Int, gain: Float, bytesPerSample: Int,
            floating: Boolean, target: ByteBuffer,
        ): Boolean
    }
}

/** PCM conversion shared by the Java Sound and PulseAudio fallbacks. */
internal fun packDesktopPcm(samples: FloatArray, count: Int, volume: Float, format: DesktopPcmFormat, out: ByteArray) {
    when {
        format.isFloat -> for (index in 0 until count) {
            writeIntLe(out, index * 4, (samples[index] * volume).toRawBits())
        }
        format.bytesPerSample == 4 -> for (index in 0 until count) {
            val scaled = (samples[index] * volume).coerceIn(-1f, 1f)
            writeIntLe(out, index * 4, (scaled * Int.MAX_VALUE).toInt())
        }
        else -> for (index in 0 until count) {
            val scaled = (samples[index] * volume).coerceIn(-1f, 1f)
            val value = (scaled * 32_767f).toInt()
            val at = index * 2
            out[at] = value.toByte()
            out[at + 1] = (value shr 8).toByte()
        }
    }
}

private fun writeIntLe(out: ByteArray, at: Int, value: Int) {
    out[at] = value.toByte()
    out[at + 1] = (value shr 8).toByte()
    out[at + 2] = (value shr 16).toByte()
    out[at + 3] = (value shr 24).toByte()
}
