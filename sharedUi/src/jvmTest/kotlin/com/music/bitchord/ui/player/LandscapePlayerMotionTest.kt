package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LandscapePlayerMotionTest {
    private data class Track(val id: String, val title: String = "Title $id", val origin: String = "Playing from Search", val artist: String = "Artist $id")

    @Test fun songChangeSlidesEveryMetadataLineUpWithoutMovingHeaderActionsOrLyrics() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            mainClock.autoAdvance = false
            val track = mutableStateOf(Track("a"))
            setContent { Fixture(track.value, PlayerPane.Lyrics) }
            waitForIdle()
            val header = onNodeWithTag("header").getUnclippedBoundsInRoot()
            val lyrics = onNodeWithTag("lyrics").getUnclippedBoundsInRoot()
            val like = onNodeWithTag("like").getUnclippedBoundsInRoot()
            val titleY = onNodeWithTag("title-a").getUnclippedBoundsInRoot().top.value
            runOnIdle { track.value = Track("b", "The next song") }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(100)
            waitForIdle()
            val origin = onNodeWithTag("origin-b").getUnclippedBoundsInRoot()
            val title = onNodeWithTag("title-b").getUnclippedBoundsInRoot()
            val artist = onNodeWithTag("artist-b").getUnclippedBoundsInRoot()
            assertTrue(title.top.value > titleY, "Incoming metadata must enter from below")
            assertEquals(18f, title.top.value - origin.top.value, .1f)
            assertEquals(24f, artist.top.value - title.top.value, .1f)
            assertEquals(header, onNodeWithTag("header").getUnclippedBoundsInRoot())
            assertEquals(lyrics, onNodeWithTag("lyrics").getUnclippedBoundsInRoot())
            assertEquals(like, onNodeWithTag("like").getUnclippedBoundsInRoot())
            save(onRoot().captureToImage(), "metadata-mid-slide.png")
            mainClock.advanceTimeBy(400)
            waitForIdle()
            assertEquals(titleY, onNodeWithTag("title-b").getUnclippedBoundsInRoot().top.value, .1f)
            assertEquals(lyrics, onNodeWithTag("lyrics").getUnclippedBoundsInRoot())
            save(onRoot().captureToImage(), "metadata-settled.png")
        }

    @Test fun sameTrackCorrectionsAndActionClicksDoNotStartAnotherSlide() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            mainClock.autoAdvance = false
            val track = mutableStateOf(Track("a"))
            val liked = mutableStateOf(false)
            setContent { Fixture(track.value, PlayerPane.Main, liked = liked.value, onLike = { liked.value = !liked.value }) }
            waitForIdle()
            val before = onNodeWithTag("title-a").getUnclippedBoundsInRoot()
            val header = onNodeWithTag("header").getUnclippedBoundsInRoot()
            onNodeWithTag("like").performClick()
            runOnIdle { track.value = track.value.copy(title = "Corrected title", origin = "Playing from Album") }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertTrue(liked.value)
            val corrected = onNodeWithTag("title-a").assertTextEquals("Corrected title").getUnclippedBoundsInRoot()
            assertEquals(before.top, corrected.top)
            assertEquals(before.left, corrected.left)
            assertEquals(before.bottom, corrected.bottom)
            assertEquals(header, onNodeWithTag("header").getUnclippedBoundsInRoot())
        }

    @Test fun paneSwitchesKeepOneHeaderAndReopeningDoesNotReplayAnEntrance() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            mainClock.autoAdvance = false
            val pane = mutableStateOf(PlayerPane.Main)
            val track = mutableStateOf(Track("a"))
            var mounts = 0
            setContent { Fixture(track.value, pane.value, onHeaderMount = { mounts++ }) }
            waitForIdle()
            val title = onNodeWithTag("title-a").getUnclippedBoundsInRoot()
            for (next in listOf(PlayerPane.Lyrics, PlayerPane.Main, PlayerPane.Queue, PlayerPane.Lyrics)) {
                runOnIdle { pane.value = next }
                mainClock.advanceTimeByFrame()
                if (next != PlayerPane.Queue) {
                    waitForIdle()
                    assertEquals(title, onNodeWithTag("title-a").getUnclippedBoundsInRoot())
                }
                mainClock.advanceTimeBy(500)
            }
            assertEquals(1, mounts, "Main/Lyrics/Queue must share one mounted header")
            runOnIdle { pane.value = PlayerPane.Queue; track.value = Track("hidden-change") }
            mainClock.advanceTimeBy(100)
            runOnIdle { pane.value = PlayerPane.Lyrics }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            val reopened = onNodeWithTag("title-hidden-change").getUnclippedBoundsInRoot()
            assertEquals(title.left, reopened.left)
            assertEquals(title.top, reopened.top)
            assertEquals(title.bottom, reopened.bottom)
        }

    @Test fun rapidSkipsAndReducedMotionSettleOnTheLatestTrack() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            mainClock.autoAdvance = false
            val track = mutableStateOf(Track("a"))
            val reduce = mutableStateOf(false)
            setContent { Fixture(track.value, PlayerPane.Lyrics, reduceMotion = reduce.value) }
            waitForIdle()
            val restY = onNodeWithTag("title-a").getUnclippedBoundsInRoot().top.value
            for (id in listOf("b", "c", "d", "a", "e")) {
                runOnIdle { track.value = Track(id) }
                mainClock.advanceTimeBy(50)
            }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertEquals(restY, onNodeWithTag("title-e").getUnclippedBoundsInRoot().top.value, .1f)
            runOnIdle { track.value = Track("f") }
            mainClock.advanceTimeBy(80)
            runOnIdle { reduce.value = true; track.value = Track("reduced") }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertEquals(restY, onNodeWithTag("title-reduced").getUnclippedBoundsInRoot().top.value, .1f)
            runOnIdle { reduce.value = false }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertEquals(restY, onNodeWithTag("title-reduced").getUnclippedBoundsInRoot().top.value, .1f)
        }

    @Test fun resizingAndFontScalingKeepHeaderAlignedAndPanesBounded() =
        runDesktopComposeUiTest(width = 1500, height = 1200) {
            val size = mutableStateOf(1000 to 700)
            val scale = mutableStateOf(1f)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(scale.value, scale.value)) {
                    Fixture(Track("a"), PlayerPane.Lyrics, width = size.value.first, height = size.value.second)
                }
            }
            for ((width, height) in listOf(1000 to 700, 900 to 600, 760 to 420, 700 to 300)) {
                for (density in listOf(1f, 1.25f)) {
                    runOnIdle { size.value = width to height; scale.value = density }
                    waitForIdle()
                    val art = onNodeWithTag("artwork").getUnclippedBoundsInRoot()
                    val header = onNodeWithTag("header").getUnclippedBoundsInRoot()
                    val body = onNodeWithTag("lyrics").getUnclippedBoundsInRoot()
                    val root = onNodeWithTag("viewport").getUnclippedBoundsInRoot()
                    assertEquals(art.top.value, header.top.value, .1f, "$width x $height at $density")
                    assertTrue(body.top >= header.bottom)
                    assertTrue(body.bottom <= root.bottom)
                    assertTrue(body.right <= root.right)
                    assertTrue((body.bottom - body.top).value > 0)
                }
            }
            save(onRoot().captureToImage(), "compact-landscape.png")
        }

    @Test fun marqueePausesWithoutResettingItsHorizontalPosition() =
        runDesktopComposeUiTest(width = 100, height = 100) {
            mainClock.autoAdvance = false
            val paused = mutableStateOf(false)
            var offset: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>? = null
            setContent {
                val current = rememberPlayerMarqueeOffset("long title", 500, 0, paused.value, .1f, 5000)
                SideEffect { offset = current }
            }
            mainClock.advanceTimeBy(1000)
            val before = requireNotNull(offset).value
            assertTrue(before < 0)
            runOnIdle { paused.value = true }
            mainClock.advanceTimeByFrame()
            val frozen = requireNotNull(offset).value
            mainClock.advanceTimeBy(400)
            assertEquals(frozen, requireNotNull(offset).value)
            runOnIdle { paused.value = false }
            mainClock.advanceTimeBy(100)
            assertTrue(requireNotNull(offset).value < frozen)
        }

    @Composable
    private fun Fixture(track: Track, pane: PlayerPane, reduceMotion: Boolean = false,
        width: Int = 1000, height: Int = 700, liked: Boolean = false, onLike: () -> Unit = {},
        onHeaderMount: () -> Unit = {}) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface {
                LandscapePlayerColumns(
                    pane, compact = height < 440, gutter = 24.dp, reduceMotion = reduceMotion,
                    artwork = { Box(it.background(Color.DarkGray).testTag("artwork")) },
                    actions = { Box(Modifier.height(48.dp)) { Text("Player controls") } },
                    header = { active ->
                        DisposableEffect(Unit) { onHeaderMount(); onDispose {} }
                        Row(Modifier.fillMaxWidth().testTag("header")) {
                            LandscapeMetadataMotion(track, { it.id }, active && !reduceMotion, Modifier.weight(1f)) { shown, _, _ ->
                                Column(Modifier.fillMaxWidth()) {
                                    Text(shown.origin, modifier = Modifier.height(18.dp).testTag("origin-${shown.id}"))
                                    Text(shown.title, modifier = Modifier.height(24.dp).testTag("title-${shown.id}"))
                                    Text(shown.artist, modifier = Modifier.height(20.dp).testTag("artist-${shown.id}"))
                                }
                            }
                            Box(Modifier.size(34.dp).testTag("like").clickable(onClick = onLike)) { Text(if (liked) "♥" else "♡") }
                            Box(Modifier.size(34.dp).testTag("menu")) { Text("⋯") }
                        }
                    },
                    mainPane = { Box(Modifier.fillMaxSize().testTag("main")) { Text("Scrubber and transport") } },
                    lyricsPane = { Box(Modifier.fillMaxSize().testTag("lyrics")) { Text("Synchronized lyric rows") } },
                    queuePane = { Box(Modifier.fillMaxSize().testTag("queue")) { Text("Up Next") } },
                    modifier = Modifier.size(width.dp, height.dp).testTag("viewport"),
                )
            }
        }
    }

    private fun save(image: androidx.compose.ui.graphics.ImageBitmap, name: String) {
        val map = image.toPixelMap()
        val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        bitmap.setRGB(0, 0, image.width, image.height,
            IntArray(image.width * image.height) { map[it % image.width, it / image.width].toArgb() }, 0, image.width)
        val target = File("build/reports/landscape-player/$name")
        target.parentFile.mkdirs()
        ImageIO.write(bitmap, "png", target)
    }
}
