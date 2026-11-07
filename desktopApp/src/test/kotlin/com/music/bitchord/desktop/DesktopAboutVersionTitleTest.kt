package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.LoadedFont
import androidx.compose.ui.text.platform.Font as loadedFont
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchord.desktopapp.generated.resources.Res
import java.awt.Font
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopAboutVersionTitleTest {
    private val versions = listOf("1.9.1", "1.8.2-test", "1.9.1-beta.10", "2.0.0+build.123")

    @Test fun suppliedFontIsBundledInFullAndCoversVersionLabels() = runBlocking {
        val bytes = Res.readBytes("font/bintang_music_black_about.ttf")
        // Exact uploaded Bintang-Music-Black.ttf, rather than the old 5 KB subset.
        assertEquals("dbc7b5014205d52e6f66b321b2334d12f0a191a9399f2a1e5a07a281022f7a21", hash(bytes))
        val font = Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(bytes))
        for (version in versions) assertEquals(-1, font.canDisplayUpTo("BitChord $version"), version)
    }

    @Test fun releaseAndTestVersionsRenderWithTheSuppliedFaceInBothProfiles() {
        val bytes = runBlocking { Res.readBytes("font/bintang_music_black_about.ttf") }
        val reference = FontFamily(loadedFont("uploaded-bintang-music-black", bytes, FontWeight.Black))
        runDesktopComposeUiTest(width = 720, height = 160) {
            val version = mutableStateOf(versions.first())
            val mode = mutableStateOf(DesktopFontMode.DEFAULT)
            setContent {
                MaterialTheme(typography = desktopTypography(mode.value)) {
                    Column(Modifier.background(Color(0xff202020))) {
                        DesktopAboutVersionTitle(version.value, Color.White,
                            Modifier.height(64.dp).testTag("about-title"))
                        Text("BitChord ${version.value}", color = Color.White,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontFamily = reference, fontSynthesis = FontSynthesis.None,
                                fontSize = 34.sp, letterSpacing = 0.sp,
                            ), fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 1,
                            modifier = Modifier.fillMaxWidth().height(64.dp).testTag("uploaded-font-reference"))
                    }
                }
            }
            for (label in versions) for (profile in DesktopFontMode.entries) {
                runOnIdle { version.value = label; mode.value = profile }
                waitUntil(timeoutMillis = 15_000) {
                    val layout = onNodeWithTag("about-title").layoutResult()
                    val font = (layout.layoutInput.style.fontFamily as? FontListFontFamily)?.fonts?.singleOrNull()
                    layout.layoutInput.text.text == "BitChord $label" && font is LoadedFont
                }
                waitForIdle()
                val title = onNodeWithTag("about-title")
                val font = (title.layoutResult().layoutInput.style.fontFamily as FontListFontFamily).fonts.single() as LoadedFont
                assertEquals(hash(bytes), hash(font.data), "$label / $profile must load the supplied file")
                val actual = title.captureToImage().toPixelMap()
                val expected = onNodeWithTag("uploaded-font-reference").captureToImage().toPixelMap()
                assertEquals(expected.width, actual.width)
                assertEquals(expected.height, actual.height)
                assertTrue((0 until actual.height).all { y -> (0 until actual.width).all { x ->
                    expected[x, y].toArgb() == actual[x, y].toArgb()
                } }, "$label / $profile must draw the uploaded font without a version-specific fallback")
            }
        }
    }

    private fun SemanticsNodeInteraction.layoutResult(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
