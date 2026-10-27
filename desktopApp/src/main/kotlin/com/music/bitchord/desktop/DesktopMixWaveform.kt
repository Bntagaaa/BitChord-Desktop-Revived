package com.music.bitchord.desktop

import com.music.bitchord.data.settings.MixDeck
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.*

/** Stream only the transition region into peak envelopes; never retain decoded track PCM. */
internal suspend fun desktopMixWaveform(stream: DesktopStream, deck: MixDeck): MixDeck {
    val decoder = DesktopAudioDecoder()
    try {
        decoder.open(stream.url, stream.headers,
            requested = DesktopPcmFormat(24000, 2, 4, isFloat = true),
            windowed = stream.windowedReads, transport = stream.transport).getOrThrow()
        decoder.seek((deck.waveStart * 1_000_000).toLong())
        val bins = 512
        val low = FloatArray(bins); val mid = FloatArray(bins); val high = FloatArray(bins)
        val rate = decoder.outputFormat.sampleRate
        val channels = decoder.outputFormat.channels
        val a = (1 - exp(-2 * PI * 200 / rate)).toFloat()
        val b = (1 - exp(-2 * PI * 2500 / rate)).toFloat()
        var l1 = 0f; var l2 = 0f; var h1 = 0f; var h2 = 0f
        while (true) {
            currentCoroutineContext().ensureActive()
            val start = decoder.positionUs / 1_000_000.0
            if (start >= deck.waveEnd) break
            val samples = decoder.readSamples() ?: break
            for (i in 0 until decoder.sampleCount step channels) {
                val time = start + i.toDouble() / channels / rate
                val bin = ((time - deck.waveStart) / (deck.waveEnd - deck.waveStart) * bins).toInt()
                if (bin !in 0 until bins) continue
                var value = 0f
                for (channel in 0 until channels) value += samples[i + channel] / channels
                l1 += a * (value - l1); l2 += a * (l1 - l2)
                h1 += b * (value - h1); h2 += b * (h1 - h2)
                low[bin] = max(low[bin], abs(l2))
                mid[bin] = max(mid[bin], abs(h2 - l2))
                high[bin] = max(high[bin], abs(value - h2))
            }
        }
        return deck.withWave(low, mid, high, deck.waveStart, deck.waveEnd)
    } finally { decoder.close() }
}
