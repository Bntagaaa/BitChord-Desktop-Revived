package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.geometry.Offset
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

    @Test fun scaledLinuxWindowCentersToolbarAndFullyRevealsFooterInsideTheCoverBounds() =
        runDesktopComposeUiTest(width = 1920, height = 1010) {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1.25f, 1f)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Fixture(Track("a"), PlayerPane.Lyrics, width = 1100, height = 760)
                    }
                }
            }
            mainClock.advanceTimeBy(2500)
            waitForIdle()
            val toolbar = onNodeWithTag("output-buttons").fetchSemanticsNode().boundsInRoot
            assertEquals(960f, toolbar.center.x, 1f, "Center the rendered controls in the physical window")
            val artwork = onNodeWithTag("artwork").fetchSemanticsNode().boundsInRoot
            val frame = onNodeWithTag("landscape-lyrics-frame").fetchSemanticsNode().boundsInRoot
            assertEquals(artwork.top, frame.top, 1f)
            assertEquals(artwork.bottom, frame.bottom, 1f)
            onNodeWithTag("title-a").assertDoesNotExist()
            save(onRoot().captureToImage(), "1920-scaled-lyrics-idle.png")
            onNodeWithTag("landscape-lyrics-frame").performMouseInput { enter(center) }
            mainClock.advanceTimeBy(400)
            waitForIdle()
            val footer = onNodeWithTag("fixture-footer").fetchSemanticsNode().boundsInRoot
            assertEquals(55f, footer.height, 1f, "The entire 44dp footer must be visible at 125% scale")
            assertEquals(frame.bottom, footer.bottom, 1f)
            onNodeWithTag("title-a").assertIsDisplayed()
            save(onRoot().captureToImage(), "1920-scaled-lyrics-hover.png")
        }

    @Test fun headerTimesOutAndHoverRevealsBothChromeEdgesWithoutMovingLyrics() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            mainClock.autoAdvance = false
            val track = mutableStateOf(Track("a"))
            setContent { Fixture(track.value, PlayerPane.Lyrics) }
            waitForIdle()
            val lyrics = onNodeWithTag("lyrics").getUnclippedBoundsInRoot()
            mainClock.advanceTimeBy(1500)
            onNodeWithTag("title-a").assertExists()
            runOnIdle { track.value = Track("b") }
            mainClock.advanceTimeBy(900)
            waitForIdle()
            onNodeWithTag("title-b").assertIsDisplayed()
            mainClock.advanceTimeBy(1400)
            waitForIdle()
            onNodeWithTag("title-b").assertDoesNotExist()
            onNodeWithTag("landscape-lyrics-frame").performMouseInput { enter(center) }
            mainClock.advanceTimeBy(300)
            waitForIdle()
            onNodeWithTag("title-b").assertIsDisplayed()
            onNodeWithTag("fixture-footer").assertIsDisplayed()
            assertEquals(lyrics, onNodeWithTag("lyrics").getUnclippedBoundsInRoot())
            onNodeWithTag("landscape-lyrics-frame").performMouseInput { moveTo(centerRight + Offset(8f, 0f)) }
            mainClock.advanceTimeBy(300)
            waitForIdle()
            onNodeWithTag("title-b").assertDoesNotExist()
            onNodeWithTag("fixture-footer").assertDoesNotExist()
        }

    @Test fun aNewTrackRevealsAnIdleHeaderAndRapidSkipsRestartOnlyTheLatestTracksTimer() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            mainClock.autoAdvance = false
            val track = mutableStateOf(Track("a"))
            setContent { Fixture(track.value, PlayerPane.Lyrics) }
            mainClock.advanceTimeBy(2400)
            onNodeWithTag("title-a").assertDoesNotExist()
            val lyrics = onNodeWithTag("lyrics").getUnclippedBoundsInRoot()
            runOnIdle { track.value = Track("b") }
            mainClock.advanceTimeBy(300)
            onNodeWithTag("title-b").assertIsDisplayed()
            onNodeWithTag("origin-b").assertIsDisplayed()
            onNodeWithTag("artist-b").assertIsDisplayed()
            onNodeWithTag("fixture-footer").assertDoesNotExist()
            mainClock.advanceTimeBy(1500)
            runOnIdle { track.value = Track("c") }
            mainClock.advanceTimeBy(600)
            onNodeWithTag("title-c").assertIsDisplayed()
            // Correcting metadata on the same track must not extend its timer.
            runOnIdle { track.value = track.value.copy(title = "Corrected title") }
            mainClock.advanceTimeBy(1700)
            onNodeWithTag("title-c").assertDoesNotExist()
            assertEquals(lyrics, onNodeWithTag("lyrics").getUnclippedBoundsInRoot())
        }

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

    @Test fun lyricsOpensByMovingTheSameHeaderUpAndClosingRestoresItsMainPosition() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            mainClock.autoAdvance = false
            val pane = mutableStateOf(PlayerPane.Main)
            val track = mutableStateOf(Track("a"))
            var mounts = 0
            setContent { Fixture(track.value, pane.value, onHeaderMount = { mounts++ }) }
            waitForIdle()
            val title = onNodeWithTag("title-a").getUnclippedBoundsInRoot()
            val mainHeader = onNodeWithTag("header").getUnclippedBoundsInRoot()
            val artwork = onNodeWithTag("artwork").getUnclippedBoundsInRoot()
            val controls = onNodeWithTag("main").getUnclippedBoundsInRoot()
            assertTrue(mainHeader.top > artwork.top, "Main must retain its centered metadata/controls stack")
            assertTrue(mainHeader.bottom < controls.top)
            save(onRoot().captureToImage(), "main-before-lyrics.png")
            runOnIdle { pane.value = PlayerPane.Lyrics }
            mainClock.advanceTimeBy(140)
            waitForIdle()
            val between = onNodeWithTag("header").getUnclippedBoundsInRoot()
            assertTrue(between.top < mainHeader.top && between.top > artwork.top,
                "Opening Lyrics must visibly travel upward, not teleport to the top")
            save(onRoot().captureToImage(), "header-moving-to-lyrics.png")
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertEquals(artwork.top, onNodeWithTag("header").getUnclippedBoundsInRoot().top)
            save(onRoot().captureToImage(), "lyrics-after-header-move.png")
            runOnIdle { pane.value = PlayerPane.Main }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertEquals(title, onNodeWithTag("title-a").getUnclippedBoundsInRoot())
            assertEquals(controls, onNodeWithTag("main").getUnclippedBoundsInRoot())
            assertEquals(1, mounts, "Main/Lyrics/Queue must share one mounted header")
            runOnIdle { pane.value = PlayerPane.Queue; track.value = Track("hidden-change") }
            mainClock.advanceTimeBy(100)
            runOnIdle { pane.value = PlayerPane.Lyrics }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            val reopened = onNodeWithTag("title-hidden-change").getUnclippedBoundsInRoot()
            assertEquals(title.left, reopened.left)
            assertEquals(artwork.top.value + 18f, reopened.top.value, .1f)
        }

    @Test fun outputCaptionIsBelowTheActionButtonsAndBothStayBelowTheArtwork() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            setContent { Fixture(Track("a"), PlayerPane.Main) }
            waitForIdle()
            val art = onNodeWithTag("artwork").getUnclippedBoundsInRoot()
            val buttons = onNodeWithTag("output-buttons").getUnclippedBoundsInRoot()
            val caption = onNodeWithTag("output-caption").getUnclippedBoundsInRoot()
            val window = onNodeWithTag("viewport").getUnclippedBoundsInRoot()
            assertEquals((window.left.value + window.right.value) / 2,
                (buttons.left.value + buttons.right.value) / 2, .1f,
                "Center the entire toolbar in the player, not in the artwork column")
            assertTrue(art.bottom < buttons.top)
            assertEquals(18f, caption.top.value - buttons.bottom.value, .1f)
            assertEquals(72f, (caption.bottom - buttons.top).value, .1f)
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

    @Test fun switchingPanelsDuringASlideDoesNotRestartMetadataOrMoveThePaneSlot() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            mainClock.autoAdvance = false
            val pane = mutableStateOf(PlayerPane.Main)
            val track = mutableStateOf(Track("a"))
            setContent { Fixture(track.value, pane.value) }
            waitForIdle()
            val header = onNodeWithTag("header").getUnclippedBoundsInRoot()
            runOnIdle { track.value = Track("b") }
            mainClock.advanceTimeBy(100)
            waitForIdle()
            val beforeSwitch = (onNodeWithTag("title-b").getUnclippedBoundsInRoot().top -
                onNodeWithTag("header").getUnclippedBoundsInRoot().top).value
            runOnIdle { pane.value = PlayerPane.Lyrics }
            mainClock.advanceTimeBy(32)
            waitForIdle()
            val afterSwitch = (onNodeWithTag("title-b").getUnclippedBoundsInRoot().top -
                onNodeWithTag("header").getUnclippedBoundsInRoot().top).value
            assertTrue(afterSwitch < beforeSwitch,
                "The same slide must continue upward across a pane change")
            // A suspended host frame (for example while minimized) settles the same clock;
            // returning to another panel cannot replay a mount-time entrance.
            mainClock.advanceTimeBy(10_000)
            runOnIdle { pane.value = PlayerPane.Main }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertEquals(header.top.value + 18f,
                onNodeWithTag("title-b").getUnclippedBoundsInRoot().top.value, .1f)
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
                    assertEquals(art.top, body.top, "The header overlays lyrics instead of reserving space")
                    assertEquals(art.bottom.value, body.bottom.value, .1f, "Lyrics must stop at the cover bottom")
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
                    boundLyricsToArtwork = true,
                    trackKey = track.id,
                    artwork = { Box(it.background(Color.DarkGray).testTag("artwork")) },
                    actions = {
                        Box(Modifier.fillMaxWidth().height(34.dp).testTag("output-buttons")) { Text("Lyrics   Output   Queue") }
                        Spacer(Modifier.height(18.dp))
                        Box(Modifier.fillMaxWidth().height(20.dp).testTag("output-caption")) { Text("System output") }
                    },
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
                    mainPane = { Box(Modifier.fillMaxWidth().height(200.dp).testTag("main")) { Text("Scrubber and transport") } },
                    lyricsPane = {
                        LandscapeLyricsFrame(true, false, reduceMotion,
                            lyrics = { Box(Modifier.fillMaxSize().testTag("lyrics")) { Text("Synchronized lyric rows") } },
                            controls = { Box(Modifier.fillMaxWidth().height(44.dp).testTag("fixture-footer")) },
                        )
                    },
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
