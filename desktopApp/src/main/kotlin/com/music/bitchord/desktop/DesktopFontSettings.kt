package com.music.bitchord.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Same two SF Pro Display modes as the mobile app; only the mapping of the already bundled
 * physical font cuts and the letter spacing change. DEFAULT matches Apple Music; LEGACY keeps
 * the original heavier/tighter BitChord typography.
 */
internal enum class DesktopFontMode(val label: String) {
    DEFAULT("Default"), LEGACY("Legacy");

    companion object {
        fun fromStored(value: String): DesktopFontMode =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }

    /** Which real OTF cut should render a requested Compose weight. */
    fun physicalCut(requested: FontWeight): FontWeight =
        if (this == LEGACY) requested else when (requested) {
            FontWeight.W500 -> FontWeight.W400
            FontWeight.W600 -> FontWeight.W500
            FontWeight.W700 -> FontWeight.W600
            FontWeight.W800 -> FontWeight.W700
            else -> requested
        }

    fun tracking(): DesktopFontTracking = if (this == DEFAULT) {
        DesktopFontTracking(
            displayLarge = (-0.5).sp,
            headlineLarge = (-0.4).sp,
            headlineMedium = (-0.25).sp,
            titleLarge = (-0.2).sp,
            titleMedium = 0.05.sp,
            bodyLarge = 0.2.sp,
            bodyMedium = 0.3.sp,
            labelMedium = 0.25.sp,
            labelSmall = 0.3.sp,
        )
    } else {
        DesktopFontTracking(
            displayLarge = (-0.8).sp,
            headlineLarge = (-0.7).sp,
            headlineMedium = (-0.4).sp,
            titleLarge = (-0.3).sp,
            titleMedium = (-0.2).sp,
            bodyLarge = TextUnit.Unspecified,
            bodyMedium = TextUnit.Unspecified,
            labelMedium = TextUnit.Unspecified,
            labelSmall = TextUnit.Unspecified,
        )
    }
}

/** Matches both typography scales defined in the original Android BitChord Theme.kt. */
internal data class DesktopFontTracking(
    val displayLarge: TextUnit,
    val headlineLarge: TextUnit,
    val headlineMedium: TextUnit,
    val titleLarge: TextUnit,
    val titleMedium: TextUnit,
    val bodyLarge: TextUnit,
    val bodyMedium: TextUnit,
    val labelMedium: TextUnit,
    val labelSmall: TextUnit,
)

/** Snapshot-driven selection updates MaterialTheme live, and persists across desktop restarts. */
internal object DesktopFontSettings {
    internal const val KEY = "app_font"

    var mode: DesktopFontMode by mutableStateOf(
        DesktopFontMode.fromStored(DesktopPersistence().string(KEY, DesktopFontMode.DEFAULT.name)),
    )
        private set

    fun selectMode(value: DesktopFontMode) {
        if (mode == value) return
        DesktopPersistence().saveString(KEY, value.name)
        mode = value
    }
}
