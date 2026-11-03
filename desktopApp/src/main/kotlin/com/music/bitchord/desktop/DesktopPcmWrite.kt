package com.music.bitchord.desktop

/**
 * Java Sound can return a short write when a line is stopped, and WASAPI can do so
 * when an endpoint changes. Only accepted whole frames advance the render clock.
 */
internal fun writePcmFully(
    samples: FloatArray,
    count: Int,
    channels: Int,
    bytesPerSample: Int,
    write: (FloatArray, Int) -> Int,
    accepted: (Int) -> Unit,
): Int {
    require(channels > 0 && bytesPerSample > 0 && count in 0..samples.size && count % channels == 0)
    var offset = 0
    while (offset < count) {
        val pending = if (offset == 0) samples else samples.copyOfRange(offset, count)
        val bytes = write(pending, count - offset)
        check(bytes > 0 && bytes % (channels * bytesPerSample) == 0 && bytes <= (count - offset) * bytesPerSample) {
            "Audio output accepted an invalid PCM write: $bytes bytes"
        }
        val taken = bytes / bytesPerSample
        offset += taken
        accepted(taken / channels)
    }
    return offset * bytesPerSample
}
