package com.music.bitchord.ui.player

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Desktop side-panel appearance. The main player and Android continue to use white artwork
 * lyrics; desktop provides a contrasting palette only inside its standalone lyrics sidebar.
 */
data class LyricsPanelColors(
    val primary: Color = Color.White,
    val secondary: Color = Color.White.copy(alpha = 0.65f),
    val inactive: Color = Color.White,
    val iconSurface: Color = Color.White,
    val selection: Color = Color.White,
    val isLight: Boolean = false,
)

/**
 * The standalone desktop lyrics sidebar sits on the app theme surface instead of the
 * artwork backdrop. Only that sidebar needs dark ink in light mode; the fullscreen
 * player keeps the existing white/tinted lyrics palette.
 */
internal fun lyricsSidePanelColors(isLight: Boolean, onSurface: Color): LyricsPanelColors =
    if (isLight) {
        LyricsPanelColors(
            primary = onSurface,
            secondary = onSurface.copy(alpha = 0.76f),
            inactive = onSurface,
            iconSurface = onSurface,
            selection = onSurface,
            isLight = true,
        )
    } else {
        LyricsPanelColors()
    }

val LocalLyricsPanelColors = staticCompositionLocalOf { LyricsPanelColors() }
