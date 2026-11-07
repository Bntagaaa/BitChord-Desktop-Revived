package com.music.bitchord.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight

/** Same two SF Pro Display profiles as upstream mobile; the real font files are already bundled. */
internal enum class DesktopFontMode(val label: String) {
    DEFAULT("Default"), LEGACY("Legacy");

    companion object {
        fun fromStored(value: String): DesktopFontMode =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }

    /** One remapping at font-family construction covers typography roles and explicit weights. */
    fun physicalCut(requested: FontWeight): FontWeight =
        if (this == LEGACY) requested else when (requested) {
            FontWeight.W500 -> FontWeight.W400
            FontWeight.W600 -> FontWeight.W500
            FontWeight.W700 -> FontWeight.W600
            FontWeight.W800 -> FontWeight.W700
            else -> requested
        }
}

/** Shared snapshot state reaches the main window, popups and the independently themed PiP. */
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
