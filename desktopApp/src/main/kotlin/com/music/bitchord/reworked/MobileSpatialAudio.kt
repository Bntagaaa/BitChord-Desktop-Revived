// Ported from upstream v1.9.1 MOBILE SpatialAudioProcessor; Media3 boundary removed.
package com.music.bitchord.reworked

import kotlin.math.roundToInt

/**
 * Cheap stand-in for "spatial audio": widens the mid/side image and mixes in
 * a short, low-passed cross-feed between channels — the same trick most
 * consumer virtual-surround plugins use. O(1) per sample, no FFT or
 * convolution, so it costs nothing worth measuring on a phone CPU.
 *
 * Exists because the platform [android.media.audiofx.Virtualizer] produced no
 * audible difference on the reference device — likely swallowed by the OEM's
 * own audio effect chain — so this runs inside ExoPlayer's own audio
 * processor pipeline instead, where nothing else can intercept it.
 */
internal class MobileSpatialAudio {

    @Volatile
    var enabled: Boolean = false

    /** How much wider the stereo image gets. 1.0 = untouched. */
    private val widthGain = 2.5f

    /** Makeup attenuation after widening, so the wider side energy doesn't clip. */
    private val outputGain = 0.82f

    /** How much of the delayed, low-passed opposite channel gets mixed back in. */
    private val crossfeedGain = 0.2f

    /** One-pole lowpass factor applied to the cross-fed signal — dulls it, like a far ear would. */
    private val lowpassCoeff = 0.3f

    private var sampleRate: Int = 0
    private var channelCount: Int = 0

    private var delayLeft = FloatArray(0)
    private var delayRight = FloatArray(0)
    private var delayIndex = 0
    private var lowpassLeft = 0f
    private var lowpassRight = 0f

    /**
     * Configures the Float32 DSP engine for [sampleRate] and [channelCount].
     */
    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        if (channelCount != 2 || sampleRate <= 0) {
            delayLeft = FloatArray(0)
            delayRight = FloatArray(0)
            delayIndex = 0
            lowpassLeft = 0f
            lowpassRight = 0f
            return
        }
        val delaySamples = (sampleRate * DELAY_MS / 1000f)
            .roundToInt()
            .coerceAtLeast(1)
        if (delayLeft.size != delaySamples) {
            delayLeft = FloatArray(delaySamples)
            delayRight = FloatArray(delaySamples)
        }
        flush()
    }

    /**
     * Processes interleaved Float32 audio samples in [block] in-place.
     * Preserves dynamic headroom without clamping to [-1.0f, +1.0f].
     */
    fun process(block: MobileAudioBlock) {
        if (!enabled || block.frameCount == 0) return
        if (block.channelCount != 2) return // Spatial widening only applies to stereo

        val delaySize = delayLeft.size
        if (delaySize == 0) return

        val totalSamples = block.frameCount * 2
        var idx = 0
        var dIdx = delayIndex
        var lpL = lowpassLeft
        var lpR = lowpassRight

        while (idx < totalSamples) {
            val left = block.samples[idx]
            val right = block.samples[idx + 1]

            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f * widthGain
            var widenedLeft = mid + side
            var widenedRight = mid - side

            val delayedRight = delayRight[dIdx]
            val delayedLeft = delayLeft[dIdx]
            lpL += lowpassCoeff * (delayedRight - lpL)
            lpR += lowpassCoeff * (delayedLeft - lpR)
            widenedLeft += lpL * crossfeedGain
            widenedRight += lpR * crossfeedGain

            delayLeft[dIdx] = left
            delayRight[dIdx] = right
            dIdx = (dIdx + 1) % delaySize

            // Headroom is preserved: no clamping to [-1.0f, +1.0f]
            block.samples[idx] = widenedLeft * outputGain
            block.samples[idx + 1] = widenedRight * outputGain
            idx += 2
        }

        delayIndex = dIdx
        lowpassLeft = lpL
        lowpassRight = lpR
    }

    fun flush() {
        delayLeft.fill(0f)
        delayRight.fill(0f)
        delayIndex = 0
        lowpassLeft = 0f
        lowpassRight = 0f
    }

    private companion object {
        const val BYTES_PER_FRAME = 4 // stereo, 16-bit
        const val DELAY_MS = 15
    }
}
