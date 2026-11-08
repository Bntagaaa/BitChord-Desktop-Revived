package com.music.bitchord.data.settings

// Pure settings from original mobile v1.9.1 AppSettings.kt; no Android preferences.
enum class DownloadQuality(
    /** Ceiling for the YouTube Opus ladder. [Int.MAX_VALUE] means "whichever rung is best". */
    val maxKbps: Int,
    val label: String,
    val detail: String,
    /** Roughly what one four-minute track costs at this rung, sans unit context. */
    val perTrack: String,
    /** Whether a source's bit-exact file is worth keeping, or a transcode will do. */
    val keepsLossless: Boolean,
) {
    STANDARD(128, "Standard", "~128 kbps Opus · fits more on the device", "~4 MB", false),
    HIGH(Int.MAX_VALUE, "High", "Best audio on offer; source quality first", "~8 MB", false),
    LOSSLESS(
        Int.MAX_VALUE,
        "Lossless",
        "Bit-exact if a source has it, best Opus if not",
        "~35 MB",
        true,
    ),
}

enum class LocalMusicSort {
    TITLE_ASC,
    TITLE_DESC,
    DATE_ADDED,
    DATE_MODIFIED,
}
