package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

/** Native glyph evidence, rather than just testing the enum's weight mapping. */
@OptIn(ExperimentalTestApi::class)
class DesktopFontRenderingTest {
    @Test
    fun changingProfileRepaintsExplicitWeightsAndRestoresTheOriginalGlyphs() =
        runDesktopComposeUiTest(width = 720, height = 360) {
            val mode = mutableStateOf(DesktopFontMode.LEGACY)
            var applied: DesktopFontMode? = null
            val weights = listOf(
                FontWeight.W400, FontWeight.W500, FontWeight.W600,
                FontWeight.W700, FontWeight.W800,
            )
            setContent {
                MaterialTheme(typography = desktopTypography(mode.value)) {
                    SideEffect { applied = mode.value }
                    Column(Modifier.background(Color.White)) {
                        weights.forEach { weight ->
                            Text(
                                text = "BitChord Music Aa Bb Mm 012345",
                                color = Color.Black,
                                fontWeight = weight,
                                fontSize = 24.sp,
                                letterSpacing = 0.sp,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.size(700.dp, 52.dp).testTag("font-${weight.weight}"),
                            )
                        }
                    }
                }
            }
            waitForIdle()
            val legacy = weights.associateWith { weight ->
                pixels(onNodeWithTag("font-${weight.weight}").captureToImage())
            }
            save(onRoot().captureToImage(), "font-legacy.png")
            runOnIdle { mode.value = DesktopFontMode.DEFAULT }
            waitUntil(15_000) { applied == DesktopFontMode.DEFAULT }
            waitForIdle()
            val modern = weights.associateWith { weight ->
                pixels(onNodeWithTag("font-${weight.weight}").captureToImage())
            }
            save(onRoot().captureToImage(), "font-default.png")
            // Upstream deliberately leaves Regular unchanged; Medium and above must really move.
            assertContentEquals(legacy.getValue(FontWeight.W400), modern.getValue(FontWeight.W400))
            for (weight in weights.drop(1)) {
                assertFalse(
                    legacy.getValue(weight).contentEquals(modern.getValue(weight)),
                    "Changing Default/Legacy did not repaint W${weight.weight}",
                )
            }
            runOnIdle { mode.value = DesktopFontMode.LEGACY }
            waitUntil(15_000) { applied == DesktopFontMode.LEGACY }
            waitForIdle()
            for (weight in weights) {
                assertContentEquals(
                    legacy.getValue(weight),
                    pixels(onNodeWithTag("font-${weight.weight}").captureToImage()),
                    "Returning to Legacy kept a stale W${weight.weight} font",
                )
            }
            save(onRoot().captureToImage(), "font-legacy-restored.png")
        }

    private fun pixels(image: ImageBitmap): IntArray {
        val map = image.toPixelMap()
        return IntArray(image.width * image.height) { index ->
            map[index % image.width, index / image.width].toArgb()
        }
    }

    private fun save(image: ImageBitmap, name: String) {
        val target = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        target.setRGB(0, 0, image.width, image.height, pixels(image), 0, image.width)
        // Reuse the cross-platform render-artifact upload already present in Check.
        val file = File("build/reports/pip-rebuild/$name")
        file.parentFile.mkdirs()
        ImageIO.write(target, "png", file)
    }
}
