package com.music.bitchord.desktop

/**
 * Phrase-length choices for desktop AutoMix. The shared upstream musical planner already
 * implements 8- and 16-bar overlaps; desktop only selects one of those options.
 * Adaptive remains the default for existing installs.
 */
internal enum class DesktopAutomixDuration(
    val phraseBars: Int,
    val optionLabel: String,
    val description: String,
) {
    ADAPTIVE(
        0, "Adaptive",
        "Chooses an overlap from the music and keeps instrumental loop mixing available.",
    ),
    EIGHT_BARS(
        8, "8 Bars",
        "About 16 seconds at 120 BPM. Uses beat-aligned overlap when the analysis is reliable; otherwise falls back safely.",
    ),
    SIXTEEN_BARS(
        16, "16 Bars",
        "About 32 seconds at 120 BPM for a longer blend. Falls back safely when the beat grid or available audio cannot support it.",
    );

    companion object {
        fun fromStored(value: String): DesktopAutomixDuration =
            entries.firstOrNull { it.name == value } ?: ADAPTIVE
    }
}
