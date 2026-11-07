package com.music.bitchord.desktop

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    val regular = composeFont(sfProCutResource(mode.physicalCut(FontWeight.W400)), FontWeight.W400)
    val medium = composeFont(sfProCutResource(mode.physicalCut(FontWeight.W500)), FontWeight.W500)
    val semibold = composeFont(sfProCutResource(mode.physicalCut(FontWeight.W600)), FontWeight.W600)
    val bold = composeFont(sfProCutResource(mode.physicalCut(FontWeight.W700)), FontWeight.W700)
    val heavy = composeFont(sfProCutResource(mode.physicalCut(FontWeight.W800)), FontWeight.W800)
    // Include the loaded Font objects: a resource completing its asynchronous load must replace
    // the temporary fallback family, even when the selected profile has not changed.
    return remember(mode, regular, medium, semibold, bold, heavy) {
        FontFamily(regular, medium, semibold, bold, heavy)
    }
}

@Composable
internal fun desktopSfProTypography(mode: DesktopFontMode): Typography {
    val family = desktopSfProFamily(mode)
    return remember(mode, family) { bitChordTypography(family, legacy = mode == DesktopFontMode.LEGACY) }
}
