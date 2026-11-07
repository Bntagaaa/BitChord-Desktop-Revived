package com.music.bitchord.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class BitChordTypographyTest {
    @Test
    fun everyRoleUsesTheSelectedFamilyWithoutSyntheticWeight() {
        for (legacy in listOf(false, true)) {
            val family = if (legacy) FontFamily.Serif else FontFamily.SansSerif
            val styles = roles(bitChordTypography(family, legacy))
            assertEquals(15, styles.size)
            for ((name, style) in styles) {
                assertEquals(family, style.fontFamily, name)
                assertEquals(FontSynthesis.None, style.fontSynthesis, name)
            }
        }
    }

    @Test
    fun customizedRolesRetainUpstreamMetricsRatherThanMaterialLineHeights() {
        val sizes = listOf(34, 30, 22, 20, 16, 16, 14, 12, 11)
        val weights = listOf(800, 800, 700, 700, 600, 400, 400, 600, 600)
        val keys = listOf("displayLarge", "headlineLarge", "headlineMedium", "titleLarge",
            "titleMedium", "bodyLarge", "bodyMedium", "labelMedium", "labelSmall")
        for (legacy in listOf(false, true)) {
            val all = roles(bitChordTypography(FontFamily.SansSerif, legacy))
            keys.forEachIndexed { index, name ->
                val style = all.getValue(name)
                assertEquals(sizes[index].sp, style.fontSize, name)
                assertEquals(FontWeight(weights[index]), style.fontWeight, name)
                assertEquals(TextUnit.Unspecified, style.lineHeight, name)
            }
        }
    }

    @Test
    fun defaultAndLegacySpacingMatchUpstreamExactly() {
        val modern = bitChordTypography(FontFamily.SansSerif, legacy = false)
        val legacy = bitChordTypography(FontFamily.SansSerif, legacy = true)
        assertEquals((-0.5).sp, modern.displayLarge.letterSpacing)
        assertEquals((-0.8).sp, legacy.displayLarge.letterSpacing)
        assertEquals((-0.4).sp, modern.headlineLarge.letterSpacing)
        assertEquals((-0.7).sp, legacy.headlineLarge.letterSpacing)
        assertEquals((-0.25).sp, modern.headlineMedium.letterSpacing)
        assertEquals((-0.4).sp, legacy.headlineMedium.letterSpacing)
        assertEquals((-0.2).sp, modern.titleLarge.letterSpacing)
        assertEquals((-0.3).sp, legacy.titleLarge.letterSpacing)
        assertEquals(0.05.sp, modern.titleMedium.letterSpacing)
        assertEquals((-0.2).sp, legacy.titleMedium.letterSpacing)
        assertEquals(0.2.sp, modern.bodyLarge.letterSpacing)
        assertEquals(0.3.sp, modern.bodyMedium.letterSpacing)
        assertEquals(0.25.sp, modern.labelMedium.letterSpacing)
        assertEquals(0.3.sp, modern.labelSmall.letterSpacing)
        for (style in listOf(legacy.bodyLarge, legacy.bodyMedium, legacy.labelMedium, legacy.labelSmall)) {
            assertEquals(TextUnit.Unspecified, style.letterSpacing)
        }
    }

    private fun roles(t: Typography): Map<String, TextStyle> = linkedMapOf(
        "displayLarge" to t.displayLarge, "displayMedium" to t.displayMedium, "displaySmall" to t.displaySmall,
        "headlineLarge" to t.headlineLarge, "headlineMedium" to t.headlineMedium, "headlineSmall" to t.headlineSmall,
        "titleLarge" to t.titleLarge, "titleMedium" to t.titleMedium, "titleSmall" to t.titleSmall,
        "bodyLarge" to t.bodyLarge, "bodyMedium" to t.bodyMedium, "bodySmall" to t.bodySmall,
        "labelLarge" to t.labelLarge, "labelMedium" to t.labelMedium, "labelSmall" to t.labelSmall,
    )
}
