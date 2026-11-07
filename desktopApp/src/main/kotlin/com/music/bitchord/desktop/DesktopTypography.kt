package com.music.bitchord.desktop

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.LoadedFont
import androidx.compose.ui.text.platform.Font as loadedFont
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.sf_pro_display_bold
import bitchord.desktopapp.generated.resources.sf_pro_display_heavy
import bitchord.desktopapp.generated.resources.sf_pro_display_medium
import bitchord.desktopapp.generated.resources.sf_pro_display_regular
import bitchord.desktopapp.generated.resources.sf_pro_display_semibold
import com.music.bitchord.ui.theme.bitChordTypography
import org.jetbrains.compose.resources.Font as composeFont
import org.jetbrains.compose.resources.FontResource

internal fun sfProCutResource(weight: FontWeight): FontResource = when (weight) {
    FontWeight.W400 -> Res.font.sf_pro_display_regular
    FontWeight.W500 -> Res.font.sf_pro_display_medium
    FontWeight.W600 -> Res.font.sf_pro_display_semibold
    FontWeight.W700 -> Res.font.sf_pro_display_bold
    FontWeight.W800 -> Res.font.sf_pro_display_heavy
    else -> error("No bundled SF Pro cut for $weight")
}

/** All app text, including explicit inline weights, resolves through the same five-file family. */
@Composable
internal fun desktopSfProFamily(mode: DesktopFontMode): FontFamily {
    val regular = desktopSfProFont(mode, FontWeight.W400)
    val medium = desktopSfProFont(mode, FontWeight.W500)
    val semibold = desktopSfProFont(mode, FontWeight.W600)
    val bold = desktopSfProFont(mode, FontWeight.W700)
    val heavy = desktopSfProFont(mode, FontWeight.W800)
    // Include the loaded Font objects: a resource completing its asynchronous load must replace
    // the temporary fallback family, even when the selected profile has not changed.
    return remember(mode, regular, medium, semibold, bold, heavy) {
        FontFamily(regular, medium, semibold, bold, heavy)
    }
}

@Composable
private fun desktopSfProFont(mode: DesktopFontMode, logical: FontWeight): androidx.compose.ui.text.font.Font {
    val physical = mode.physicalCut(logical)
    val font = composeFont(sfProCutResource(physical), logical)
    return remember(font, physical) {
        if (font !is LoadedFont) font else {
            val original = font.data
            val classified = fontBytesWithWeight(original, physical.weight)
            if (classified === original) font else loadedFont(
                identity = "${font.identity}:physical-weight=${physical.weight}",
                data = classified,
                weight = logical,
            )
        }
    }
}

@Composable
internal fun desktopSfProTypography(mode: DesktopFontMode): Typography {
    val family = desktopSfProFamily(mode)
    return remember(mode, family) { bitChordTypography(family, legacy = mode == DesktopFontMode.LEGACY) }
}
