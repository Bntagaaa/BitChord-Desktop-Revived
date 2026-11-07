package com.music.bitchord.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The two type scales from upstream BitChord's Android Theme.kt, shared by both hosts.
 * Source: kushagrasinghx/BitChord @ acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc.
 *
 * Build the customized roles from fresh TextStyles, as upstream does. Copying Material's
 * display/title defaults retains unrelated line heights (for example 64sp for a 34sp heading).
 * The supplied family maps logical weights to physical cuts; do not also subtract a weight here.
 */
fun bitChordTypography(family: FontFamily, legacy: Boolean): Typography {
    fun tracking(modern: Double, original: Double): TextUnit =
        (if (legacy) original else modern).sp
    fun bodyTracking(modern: Double): TextUnit =
        if (legacy) TextUnit.Unspecified else modern.sp

    val scale = Typography(
        displayLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = tracking(-0.5, -0.8)),
        headlineLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 30.sp, letterSpacing = tracking(-0.4, -0.7)),
        headlineMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 22.sp, letterSpacing = tracking(-0.25, -0.4)),
        titleLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 20.sp, letterSpacing = tracking(-0.2, -0.3)),
        titleMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 16.sp, letterSpacing = tracking(0.05, -0.2)),
        bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 16.sp, letterSpacing = bodyTracking(0.2)),
        bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp, letterSpacing = bodyTracking(0.3)),
        labelMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 12.sp, letterSpacing = bodyTracking(0.25)),
        labelSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 11.sp, letterSpacing = bodyTracking(0.3)),
    )
    // Include the less obvious defaults used by buttons, menus, captions and unstyled Text.
    // Never fake a heavier cut after selecting a deliberately lighter real font file.
    fun TextStyle.withFamily() = copy(fontFamily = family, fontSynthesis = FontSynthesis.None)
    return scale.copy(
        displayLarge = scale.displayLarge.withFamily(),
        displayMedium = scale.displayMedium.withFamily(),
        displaySmall = scale.displaySmall.withFamily(),
        headlineLarge = scale.headlineLarge.withFamily(),
        headlineMedium = scale.headlineMedium.withFamily(),
        headlineSmall = scale.headlineSmall.withFamily(),
        titleLarge = scale.titleLarge.withFamily(),
        titleMedium = scale.titleMedium.withFamily(),
        titleSmall = scale.titleSmall.withFamily(),
        bodyLarge = scale.bodyLarge.withFamily(),
        bodyMedium = scale.bodyMedium.withFamily(),
        bodySmall = scale.bodySmall.withFamily(),
        labelLarge = scale.labelLarge.withFamily(),
        labelMedium = scale.labelMedium.withFamily(),
        labelSmall = scale.labelSmall.withFamily(),
    )
}
