package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontSynthesis
import org.jetbrains.compose.resources.Font as composeFont
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real native glyph tests: a correct enum or Font.weight value alone is not render evidence. */
@OptIn(ExperimentalTestApi::class)
class DesktopFontRenderingTest {
    private val weights = listOf(FontWeight.W400, FontWeight.W500, FontWeight.W600, FontWeight.W700, FontWeight.W800)

    @Test
    fun lyricsExtraBoldUsesTheUpstreamBoldOutlinesAndSwitchesLive() =
        runDesktopComposeUiTest(width = 720, height = 180) {
            val mode = mutableStateOf(DesktopFontMode.DEFAULT)
            var ready = false
            var applied: DesktopFontMode? = null
            setContent {
                MaterialTheme(typography = desktopTypography(mode.value)) {
                    val reference = composeFont(sfProCutResource(FontWeight.W700), FontWeight.W700)
                    val family = MaterialTheme.typography.bodyLarge.fontFamily
                    SideEffect {
                        ready = isFamilyLoaded(family) &&
                            reference is androidx.compose.ui.text.platform.LoadedFont
                        applied = mode.value
                    }
                    // Same style as the shared LyricsPanel, including its explicit W800 override.
                    val style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 41.sp,
                        letterSpacing = 0.sp,
                    )
                    Column(Modifier.background(Color.White)) {
                        Text("Dan lihatlah dunia", style = style, color = Color.Black,
                            modifier = Modifier.size(700.dp, 60.dp).testTag("lyrics"))
                        Text("Dan lihatlah dunia", style = style.copy(
                            fontFamily = FontFamily(reference), fontWeight = FontWeight.W700,
                            fontSynthesis = FontSynthesis.None,
                        ), color = Color.Black, modifier = Modifier.size(700.dp, 60.dp).testTag("upstream-bold"))
                    }
                }
            }
            waitUntil(timeoutMillis = 15_000) { ready && applied == DesktopFontMode.DEFAULT }
            waitForIdle()
            val modern = pixels(onNodeWithTag("lyrics").captureToImage())
            assertSamePixels(pixels(onNodeWithTag("upstream-bold").captureToImage()), modern,
                "Default lyrics must draw the real Bold cut without Skia emboldening it")
            save(onRoot().captureToImage(), "lyrics-default-versus-bold.png")
            runOnIdle { mode.value = DesktopFontMode.LEGACY }
            waitUntil(timeoutMillis = 15_000) { ready && applied == DesktopFontMode.LEGACY }
            waitForIdle()
            val legacy = pixels(onNodeWithTag("lyrics").captureToImage())
            assertTrue(ink(modern) < ink(legacy), "Default lyrics must be visibly thinner than Legacy")
            save(onRoot().captureToImage(), "lyrics-legacy-versus-bold.png")
            runOnIdle { mode.value = DesktopFontMode.DEFAULT }
            waitUntil(timeoutMillis = 15_000) { ready && applied == DesktopFontMode.DEFAULT }
            waitForIdle()
            assertSamePixels(modern, pixels(onNodeWithTag("lyrics").captureToImage()),
                "Returning to Default must immediately restore the thinner lyrics")
        }

    @Test
    fun changingProfileRepaintsExplicitWeightsAndRestoresTheOriginalGlyphs() =
        runDesktopComposeUiTest(width = 720, height = 360) {
            val mode = mutableStateOf(DesktopFontMode.LEGACY)
            var applied: DesktopFontMode? = null
            var ready = false
            setContent {
                MaterialTheme(typography = desktopTypography(mode.value)) {
                    val family = MaterialTheme.typography.bodyLarge.fontFamily
                    SideEffect { applied = mode.value; ready = isFamilyLoaded(family) }
                    Column(Modifier.background(Color.White)) {
                        weights.forEach { weight -> Probe("font-${weight.weight}", weight) }
                    }
                }
            }
            waitUntil(timeoutMillis = 15_000) { ready && applied == DesktopFontMode.LEGACY }
            waitForIdle()
            val legacy = weights.associateWith { pixels(onNodeWithTag("font-${it.weight}").captureToImage()) }
            save(onRoot().captureToImage(), "font-legacy.png")
            runOnIdle { mode.value = DesktopFontMode.DEFAULT }
            waitUntil(timeoutMillis = 15_000) { ready && applied == DesktopFontMode.DEFAULT }
            waitForIdle()
            val modern = weights.associateWith { pixels(onNodeWithTag("font-${it.weight}").captureToImage()) }
            save(onRoot().captureToImage(), "font-default.png")
            // Upstream deliberately retains Regular. Medium and above must actually change.
            assertContentEquals(legacy.getValue(FontWeight.W400), modern.getValue(FontWeight.W400))
            for (weight in weights.drop(1)) {
                assertFalse(legacy.getValue(weight).contentEquals(modern.getValue(weight)),
                    "Changing Default/Legacy did not repaint W${weight.weight}")
            }
            runOnIdle { mode.value = DesktopFontMode.LEGACY }
            waitUntil(timeoutMillis = 15_000) { ready && applied == DesktopFontMode.LEGACY }
            waitForIdle()
            for (weight in weights) {
                assertContentEquals(legacy.getValue(weight),
                    pixels(onNodeWithTag("font-${weight.weight}").captureToImage()),
                    "Returning to Legacy kept a stale W${weight.weight} font")
            }
            save(onRoot().captureToImage(), "font-legacy-restored.png")
        }

    @Test
    fun logicalWeightsRenderTheExpectedPhysicalCutsWithoutFakeBold() =
        runDesktopComposeUiTest(width = 720, height = 600) {
            var ready = false
            setContent {
                MaterialTheme(typography = desktopTypography(DesktopFontMode.DEFAULT)) {
                    val family = MaterialTheme.typography.bodyLarge.fontFamily
                    SideEffect { ready = isFamilyLoaded(family) }
                    Column(Modifier.background(Color.White)) {
                        weights.forEach { logical ->
                            val physical = DesktopFontMode.DEFAULT.physicalCut(logical)
                            val realCut = FontFamily(composeFont(sfProCutResource(physical), physical))
                            Probe("logical-${logical.weight}", logical)
                            Probe("physical-${logical.weight}", physical, realCut)
                        }
                    }
                }
            }
            waitUntil(timeoutMillis = 15_000) { ready }
            waitForIdle()
            save(onRoot().captureToImage(), "font-logical-versus-physical.png")
            for (logical in weights) {
                assertSamePixels(
                    pixels(onNodeWithTag("physical-${logical.weight}").captureToImage()),
                    pixels(onNodeWithTag("logical-${logical.weight}").captureToImage()),
                    "W${logical.weight} does not draw the expected real SF Pro cut",
                )
            }
        }

    @Test
    fun persistedSelectionUpdatesIndependentThemeConsumersWithoutRestart() =
        runDesktopComposeUiTest(width = 720, height = 200) {
            val previous = DesktopFontSettings.mode
            var applied: DesktopFontMode? = null
            var ready = false
            try {
                // No Compose root exists yet; do not wait for an idle composition before setContent.
                DesktopFontSettings.selectMode(DesktopFontMode.LEGACY)
                setContent {
                    Column(Modifier.background(Color.White)) {
                        MaterialTheme(typography = desktopTypography()) {
                            val family = MaterialTheme.typography.bodyLarge.fontFamily
                            SideEffect { applied = DesktopFontSettings.mode; ready = isFamilyLoaded(family) }
                            Probe("main-consumer", FontWeight.W700)
                        }
                        // PiP has its own MaterialTheme; it must not retain the first loaded profile.
                        MaterialTheme(typography = desktopTypography()) {
                            Probe("independent-consumer", FontWeight.W700)
                        }
                    }
                }
                waitUntil(timeoutMillis = 15_000) { ready && applied == DesktopFontMode.LEGACY }
                waitForIdle()
                val before = pixels(onNodeWithTag("main-consumer").captureToImage())
                runOnIdle { DesktopFontSettings.selectMode(DesktopFontMode.DEFAULT) }
                waitUntil(timeoutMillis = 15_000) { ready && applied == DesktopFontMode.DEFAULT }
                waitForIdle()
                val after = pixels(onNodeWithTag("main-consumer").captureToImage())
                assertFalse(before.contentEquals(after), "Settings change did not reach the root typography")
                assertContentEquals(after, pixels(onNodeWithTag("independent-consumer").captureToImage()))
                assertEquals("DEFAULT", DesktopPersistence().string(DesktopFontSettings.KEY))
            } finally {
                DesktopFontSettings.selectMode(previous)
            }
        }

    private fun isFamilyLoaded(family: FontFamily?): Boolean =
        (family as? FontListFontFamily)?.fonts?.map { it.weight.weight } == listOf(400, 500, 600, 700, 800)

    @Composable
    private fun Probe(tag: String, weight: FontWeight, family: FontFamily? = null) {
        Text(
            text = "BitChord Music Aa Bb Mm 012345",
            color = Color.Black,
            fontWeight = weight,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.sp,
            style = MaterialTheme.typography.bodyLarge.let { if (family == null) it else it.copy(fontFamily = family) },
            modifier = Modifier.size(700.dp, 52.dp).testTag(tag),
        )
    }

    private fun pixels(image: ImageBitmap): IntArray {
        val map = image.toPixelMap()
        return IntArray(image.width * image.height) { map[it % image.width, it / image.width].toArgb() }
    }

    private fun assertSamePixels(expected: IntArray, actual: IntArray, message: String) {
        // Do not dump hundreds of thousands of pixel values into failed CI logs.
        assertEquals(expected.size, actual.size, message)
        assertTrue(expected.contentEquals(actual), message)
    }

    private fun ink(pixels: IntArray): Long = pixels.sumOf { (255 - (it and 0xff)).toLong() }

    private fun save(image: ImageBitmap, name: String) {
        val target = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        target.setRGB(0, 0, image.width, image.height, pixels(image), 0, image.width)
        val file = File("build/reports/pip-rebuild/$name")
        file.parentFile.mkdirs()
        ImageIO.write(target, "png", file)
    }
}
