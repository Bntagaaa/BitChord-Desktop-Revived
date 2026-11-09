package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song

data class DesktopPlaybackState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val volume: Float = 1.0f,
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    /**
     * When [positionMs] was read off the sink, on the [System.nanoTime] clock — taken on the audio
     * thread, so the lyrics can date it by when it was true rather than by when the UI got to it.
     * Set alongside [positionMs], always: a stale pair reads as a reading from long ago.
     */
    val positionSampledAtNanos: Long = 0L,
    /** Bumped on every deliberate jump of the playhead — a seek, a track opened or replaced. */
    val seeks: Int = 0,
    /**
     * A seek has landed but none of its audio has reached the speakers yet — the decoder is still
     * fetching it. [isPlaying] stays true (it is what Listen Together and the media keys follow),
     * so this is what tells the lyrics not to run on through the wait.
     */
    val awaitingAudio: Boolean = false,
    val durationMs: Long = 0L,
    val error: String? = null,
    val streamFormat: DesktopStreamFormat? = null,
    /** Which configured source is serving this — null before anything opened. */
    val streamSourceId: String? = null,
    /** Whether a source is still looking for a better copy of this track while it plays. */
    val searchingBetter: Boolean = false,
    /** True while an analysed Automix handoff is audibly mixing, which the scrubber animates. */
    val mixing: Boolean = false,
    /** Where the next transition will sit, as fractions of this track — drawn on the scrubber. */
    val transitionWindow: com.music.bitchord.data.settings.TransitionWindow? = null,
    /** How far Automix has got on this track and the next — see the player's line. */
    val smartAnalysis: com.music.bitchord.data.settings.SmartAnalysis =
        com.music.bitchord.data.settings.SmartAnalysis(),
)

data class DesktopStreamFormat(
    val codec: String? = null,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    /** How many channels the decoder is being fed; null before anything measured one. */
    val channels: Int? = null,
) {
    /** The codec's short name, whether it arrived bare or inside a MIME type. */
    private val codecName: String?
        get() = codec?.substringAfterLast('/')?.substringBefore(';')?.trim()?.lowercase()

    val isLossless: Boolean
        get() = codecName != null && (
            codecName in LOSSLESS_CODECS ||
                // FFmpeg names every uncompressed flavour `pcm_<layout>`, and Media3 names the same
                // bytes `audio/raw`.
                codecName.orEmpty().startsWith("pcm_") ||
                codecName == "raw"
            )

    /** Dolby Atmos decoded as E-AC-3 JOC; deliberately distinct from lossless. */
    val isDolbyAtmos: Boolean
        get() = codecName == "eac3" || codecName == "eac3-joc" || codecName == "ec-3"

    /**
     * Better than CD — the line Tidal, Qobuz and Apple Music all draw it at: past 16-bit or past
     * 48kHz, not merely lossless.
     */
    val isHiRes: Boolean
        get() = isLossless && ((bitDepth ?: 0) > 16 || (sampleRateHz ?: 0) > 48_000)

    /** Lossy, but the top of what lossy gets — a 320kbps AAC rather than YouTube's 160kbps Opus. */
    val isHiQuality: Boolean
        get() = !isLossless && (kbps ?: 0) >= HI_QUALITY_KBPS

    /** The rate to state on screen. */
    private val statedKbps: Int?
        get() = when {
            !isLossless -> kbps
            bitDepth != null && sampleRateHz != null && channels != null ->
                bitDepth * sampleRateHz * channels / 1_000
            else -> null
        }

    /** The codec under its usual name rather than its container's or FFmpeg's. */
    internal val codecLabel: String?
        get() = when (val name = codecName) {
            null -> null
            "opus" -> "Opus"
            "aac", "mp4a-latm", "mp4a", "mp4", "m4a" -> "AAC"
            "vorbis" -> "Vorbis"
            "mp3", "mpeg", "mpeg-4" -> "MP3"
            "flac" -> "FLAC"
            "alac" -> "ALAC"
            "eac3", "ec-3", "eac3-joc" -> "E-AC-3"
            "ac3" -> "AC-3"
            else -> if (name.startsWith("pcm_")) "PCM" else name.uppercase()
        }

    /** "FLAC · 24-bit · 96.0 kHz · 4608 kbps · Stereo" — whichever of those is actually known. */
    val summary: String
        get() = listOfNotNull(
            codecLabel,
            bitDepth?.let { "$it-bit" },
            sampleRateHz?.let { "${it / 1000f} kHz".replace(".0 ", " ") },
            statedKbps?.let { "$it kbps" },
            when (channels) {
                null -> null
                1 -> "Mono"
                2 -> "Stereo"
                else -> "$channels channels"
            },
        ).joinToString(" · ").ifBlank { "Unknown format" }

    private companion object {
        val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "aiff", "ape", "wv", "wavpack", "dsf", "dff", "tta")

        /** The bitrate a lossy stream has to reach to be worth calling out. */
        const val HI_QUALITY_KBPS = 256
    }
}

