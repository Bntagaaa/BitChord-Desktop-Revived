package com.music.bitchord.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LandscapeLyricsFrameTest {
    @Test fun visibleViewportFadesAtBothEdgesAndFollowsTheHeaderOverlay() =
        runDesktopComposeUiTest(width = 160, height = 160) {
            val covered = mutableStateOf(0f)
            setContent {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Box(Modifier.offset(30.dp, 30.dp).size(100.dp)
                        .landscapeLyricEdges(top = { covered.value })) {
                        Canvas(Modifier.fillMaxSize()) { drawRect(Color.White) }
                    }
                }
            }
            var pixels = onRoot().captureToImage().toPixelMap()
            assertTrue(pixels[80, 30].red < .1f)
            assertTrue(pixels[80, 44].red in .3f.. .7f)
            assertTrue(pixels[80, 80].red > .95f)
            assertTrue(pixels[80, 116].red in .3f.. .7f)
            assertTrue(pixels[80, 129].red < .1f)
            runOnIdle { covered.value = 20f }
            pixels = onRoot().captureToImage().toPixelMap()
            assertTrue(pixels[80, 45].red < .1f, "Lyrics behind the header stay hidden")
            assertTrue(pixels[80, 64].red in .3f.. .7f, "Fade follows the revealed header edge")
            assertTrue(pixels[80, 85].red > .95f)
        }

    @Test fun viewportPreservesAllFourRoundedCornersOfAHighlightBleedingIntoTheGutter() =
        runDesktopComposeUiTest(width = 160, height = 160) {
            setContent {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Box(Modifier.offset(30.dp, 30.dp).size(100.dp)
                        .clipLandscapeLyricsVertically().landscapeLyricEdges()) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRoundRect(Color.White, topLeft = Offset(-10f, 30f),
                                size = Size(120f, 40f), cornerRadius = CornerRadius(10f))
                        }
                    }
                }
            }
            val pixels = onRoot().captureToImage().toPixelMap()
            for (x in listOf(21, 138)) {
                assertTrue(pixels[x, 80].red > .95f, "Both sides of the highlight remain visible")
                assertTrue(pixels[x, 61].red < .1f, "Top corners remain rounded")
                assertTrue(pixels[x, 98].red < .1f, "Bottom corners remain rounded")
            }
        }

    @Test fun hoverReservesFooterSpaceWithoutMovingOrRemountingTheLyricRenderer() =
        runDesktopComposeUiTest(width = 440, height = 420) {
            mainClock.autoAdvance = false
            var mounts = 0
            setContent { Fixture(onMount = { mounts++ }) }
            waitForIdle()
            val viewport = onNodeWithTag("landscape-lyrics-viewport").getUnclippedBoundsInRoot()
            val renderer = onNodeWithTag("renderer").getUnclippedBoundsInRoot()
            val frame = onNodeWithTag("landscape-lyrics-frame").getUnclippedBoundsInRoot()
            assertEquals(frame.bottom, viewport.bottom)
            onNodeWithText("Lyrics by provider").assertDoesNotExist()
            onNodeWithTag("landscape-lyrics-frame").performMouseInput { enter(center) }
            mainClock.advanceTimeBy(80)
            waitForIdle()
            val intermediate = onNodeWithTag("landscape-lyrics-viewport").getUnclippedBoundsInRoot()
            assertTrue(intermediate.bottom < viewport.bottom && intermediate.bottom.value > viewport.bottom.value - 44f)
            mainClock.advanceTimeBy(250)
            waitForIdle()
            val revealed = onNodeWithTag("landscape-lyrics-viewport").getUnclippedBoundsInRoot()
            val footer = onNodeWithTag("footer").getUnclippedBoundsInRoot()
            assertEquals(44f, (viewport.bottom - revealed.bottom).value, .1f)
            assertEquals(revealed.bottom, footer.top)
            assertEquals(frame.bottom, footer.bottom)
            assertEquals(renderer, onNodeWithTag("renderer").getUnclippedBoundsInRoot())
            onNodeWithText("Lyrics by provider").performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(100)
            onNodeWithText("Translate").assertIsDisplayed()
            onNodeWithTag("landscape-lyrics-frame").performMouseInput { moveTo(centerRight + Offset(8f, 0f)) }
            mainClock.advanceTimeBy(300)
            waitForIdle()
            assertEquals(viewport, onNodeWithTag("landscape-lyrics-viewport").getUnclippedBoundsInRoot())
            onNodeWithText("Lyrics by provider").assertDoesNotExist()
            assertEquals(1, mounts)
        }

    @Test fun revealedButtonsInvokeCallbacksAndPinnedControlsSurviveHoverExit() =
        runDesktopComposeUiTest(width = 440, height = 420) {
            val pinned = mutableStateOf(false)
            val clicks = mutableListOf<String>()
            setContent { Fixture(pinned = pinned.value, onClick = { clicks += it; pinned.value = true }) }
            onNodeWithTag("landscape-lyrics-frame").performMouseInput { enter(center) }
            mainClock.advanceTimeBy(300)
            onNodeWithText("Globe").performClick()
            onNodeWithText("Lyrics by provider").performClick()
            onNodeWithText("Translate").performClick()
            assertEquals(listOf("Globe", "Lyrics by provider", "Translate"), clicks)
            onNodeWithTag("landscape-lyrics-frame").performMouseInput { moveTo(centerRight + Offset(8f, 0f)) }
            mainClock.advanceTimeBy(300)
            onNodeWithText("Lyrics by provider").assertIsDisplayed()
            runOnIdle { pinned.value = false }
            mainClock.advanceTimeBy(300)
            onNodeWithText("Lyrics by provider").assertDoesNotExist()
        }

    @Test fun keyboardFocusCanRevealControlsWithoutHovering() =
        runDesktopComposeUiTest(width = 440, height = 420) {
            setContent { Fixture() }
            onNodeWithText("Translate").assertDoesNotExist()
            onNodeWithTag("landscape-lyrics-frame").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            mainClock.advanceTimeBy(300)
            onNodeWithText("Translate").assertIsDisplayed()
        }

    @Test fun touchAndStandaloneModesKeepTheirControlsVisibleWithoutHover() =
        runDesktopComposeUiTest(width = 440, height = 420) {
            setContent { Fixture(hover = false) }
            onNodeWithText("Globe").assertIsDisplayed()
            onNodeWithText("Translate").assertIsDisplayed()
            val viewport = onNodeWithTag("landscape-lyrics-viewport").getUnclippedBoundsInRoot()
            val renderer = onNodeWithTag("renderer").getUnclippedBoundsInRoot()
            val frame = onNodeWithTag("landscape-lyrics-frame").getUnclippedBoundsInRoot()
            assertEquals(44f, (frame.bottom - viewport.bottom).value, .1f)
            assertEquals(viewport, renderer)
        }

    @Test fun reducedMotionRevealsTheFooterWithoutAnAnimatedResize() =
        runDesktopComposeUiTest(width = 440, height = 420) {
            mainClock.autoAdvance = false
            setContent { Fixture(reduceMotion = true) }
            val frame = onNodeWithTag("landscape-lyrics-frame").getUnclippedBoundsInRoot()
            onNodeWithTag("landscape-lyrics-frame").performMouseInput { enter(center) }
            mainClock.advanceTimeBy(48)
            waitForIdle()
            val viewport = onNodeWithTag("landscape-lyrics-viewport").getUnclippedBoundsInRoot()
            assertEquals(44f, (frame.bottom - viewport.bottom).value, .1f)
        }

    @Composable
    private fun Fixture(hover: Boolean = true, pinned: Boolean = false, reduceMotion: Boolean = false,
        onMount: () -> Unit = {}, onClick: (String) -> Unit = {}) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface {
                LandscapeLyricsFrame(hover, pinned, reduceMotion, Modifier.size(400.dp, 360.dp),
                    lyrics = {
                        DisposableEffect(Unit) { onMount(); onDispose {} }
                        Box(Modifier.fillMaxSize().testTag("renderer")) { Text("Current lyric line") }
                    },
                    controls = {
                        Row(Modifier.fillMaxWidth().height(44.dp).testTag("footer"), horizontalArrangement = Arrangement.SpaceBetween) {
                            for (text in listOf("Globe", "Lyrics by provider", "Translate")) {
                                Text(text, Modifier.clickable { onClick(text) })
                            }
                        }
                    },
                )
            }
        }
    }
}
