package com.music.bitchord.desktop

import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.MelSpectrogram
import java.util.concurrent.CancellationException

/** Independent region decoder: never seeks or closes the engine's live decoder. */
internal fun desktopLoopRegion(
    stream: DesktopStream, from: Double, to: Double, rate: Int, abort: () -> Boolean,
): LoopRegion? {
    if (to <= from || rate <= 0) return null
    if (abort()) throw CancellationException("Loop region preparation cancelled")
    val decoder = DesktopAudioDecoder()
    try {
        decoder.open(stream.url, stream.headers, requested = DesktopPcmFormat(rate, 2, 4, isFloat = true),
            windowed = stream.windowedReads, transport = stream.transport).getOrThrow()
        val start = from.coerceAtLeast(0.0)
        if (start > 0 && !decoder.seek((start * 1_000_000).toLong())) return null
        val actualRate = decoder.outputFormat.sampleRate
        val channels = decoder.outputFormat.channels
        val wanted = ((to - start) * actualRate).toInt()
        if (wanted <= 0 || wanted > actualRate * 180) return null
        val left = FloatArray(wanted); val right = FloatArray(wanted)
        var written = 0
        while (written < wanted) {
            if (abort()) throw CancellationException("Loop region preparation cancelled")
            val block = decoder.readSamples() ?: break
            val frames = minOf(decoder.sampleCount / channels, wanted - written)
            for (i in 0 until frames) {
                left[written + i] = block[i * channels]
                right[written + i] = block[i * channels + if (channels > 1) 1 else 0]
            }
            written += frames
        }
        if (written == 0) return null
        val l = if (written == wanted) left else left.copyOf(written)
        val r = if (written == wanted) right else right.copyOf(written)
        if (actualRate == rate) return LoopRegion(l, r, start)
        return LoopRegion(MelSpectrogram.resample(l, actualRate.toDouble(), rate.toDouble()) ?: return null,
            MelSpectrogram.resample(r, actualRate.toDouble(), rate.toDouble()) ?: return null, start)
    } finally { decoder.close() }
}
