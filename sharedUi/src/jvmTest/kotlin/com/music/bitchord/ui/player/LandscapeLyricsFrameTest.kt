package com.music.bitchord.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LandscapeLyricsFrameTest {
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
