package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import java.lang.reflect.Proxy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun withCenteredTestHost(block: () -> Unit) {
    val host = Proxy.newProxyInstance(
        PlayerHost::class.java.classLoader,
        arrayOf(PlayerHost::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getMobileTextChrome" -> true
            else -> error("Unexpected PlayerHost access in centered layout test: ${method.name}")
        }
    } as PlayerHost
    PlayerPlatform.withHostForTest(host, block)
}

@OptIn(ExperimentalTestApi::class)
class CenteredLandscapeLayoutTest {
    @Test
    fun geometryCentersInMainAndShiftsLeftInSplit() {
        val g = centeredLandscapeGeometry(
            width = 1600,
            height = 900,
            inset = 30,
            controlsHeight = 180,
            reservedControlsHeight = 200,
            bottomBarHeight = 44,
            stackGap = 22,
            bottomGap = 16,
            maxContentWidth = 1200,
            maxArtSize = 520,
            minControlsWidth = 280,
        )
        assertEquals(520, g.artworkSize)
        assertEquals(520, g.stackWidth)
        assertEquals((1600 - 520) / 2, g.centeredStackX)
        assertTrue(g.leftStackX < g.centeredStackX, "Left column position must lie left of centered position")
        assertTrue(g.leftStackX + g.stackWidth <= 800, "Left column stack must stay within left half")
        assertEquals(200 + 600 + 30, g.rightPaneLeft)
    }

    @Test
    fun mainCentersStackAndLyricsSlidesLeftWithTween360() = withCenteredTestHost {
        runDesktopComposeUiTest(width = 1100, height = 800) {
            mainClock.autoAdvance = false
            val pane = mutableStateOf(PlayerPane.Main)
            setContent {
                MaterialTheme {
                    CenteredLandscapePlayerColumns(
                        pane = pane.value,
                        compact = false,
                        gutter = 30.dp,
                        reduceMotion = false,
                        lyricsEnabled = true,
                        queueButtonVisible = true,
                        onToggleLyrics = {
                            pane.value = if (pane.value == PlayerPane.Lyrics) PlayerPane.Main else PlayerPane.Lyrics
                        },
                        onToggleQueue = {
                            pane.value = if (pane.value == PlayerPane.Queue) PlayerPane.Main else PlayerPane.Queue
                        },
                        artwork = { mod ->
                            Box(mod.background(Color.DarkGray))
                        },
                        header = { _ ->
                            Box(Modifier.fillMaxWidth().height(48.dp)) { Text("Song Title") }
                        },
                        lyricStrip = {
                            Box(Modifier.fillMaxWidth().height(24.dp)) { Text("Current lyric line") }
                        },
                        scrubber = {
                            Box(Modifier.fillMaxWidth().height(44.dp))
                        },
                        transport = {
                            Box(Modifier.fillMaxWidth().height(60.dp))
                        },
                        volume = {
                            Box(Modifier.fillMaxWidth().height(32.dp))
                        },
                        volumeLevel = { 0.8f },
                        lyricsPane = {
                            Box(Modifier.fillMaxSize().testTag("test-lyrics-pane")) { Text("Lyrics") }
                        },
                        queuePane = {
                            Box(Modifier.fillMaxSize().testTag("test-queue-pane")) { Text("Queue") }
                        },
                    )
                }
            }
            waitForIdle()

            val mainArt = onNodeWithTag("landscape-artwork").getUnclippedBoundsInRoot()
            val mainControls = onNodeWithTag("centered-stack-controls").getUnclippedBoundsInRoot()
            assertEquals(550f, (mainArt.left.value + mainArt.right.value) / 2f, 2f)
            assertEquals(mainArt.left.value, mainControls.left.value, 2f)
            assertEquals(mainArt.right.value, mainControls.right.value, 2f)

            val bottomLeft = onNodeWithTag("centered-bottom-left").getUnclippedBoundsInRoot()
            val bottomRight = onNodeWithTag("centered-bottom-right").getUnclippedBoundsInRoot()
            assertEquals(24f, bottomLeft.left.value, 1f)
            assertEquals(1100f - 24f, bottomRight.right.value, 1f)
            onNodeWithTag("centered-lyric-strip").assertIsDisplayed()

            onNodeWithContentDescription("Lyrics").performClick()
            mainClock.advanceTimeBy(180)
            waitForIdle()
            val midArt = onNodeWithTag("landscape-artwork").getUnclippedBoundsInRoot()
            assertTrue(
                midArt.left < mainArt.left,
                "Artwork should be sliding left mid-transition",
            )

            mainClock.advanceTimeBy(300)
            waitForIdle()
            val settledArt = onNodeWithTag("landscape-artwork").getUnclippedBoundsInRoot()
            val settledControls = onNodeWithTag("centered-stack-controls").getUnclippedBoundsInRoot()
            val lyricsPaneBounds = onNodeWithTag("test-lyrics-pane").getUnclippedBoundsInRoot()
            assertTrue(settledArt.right <= 550.dp, "Settled artwork must sit in the left column")
            onNodeWithTag("centered-lyric-strip").assertDoesNotExist()
            onNodeWithTag("test-lyrics-pane").assertIsDisplayed()
            assertEquals(settledArt.top.value, lyricsPaneBounds.top.value, 2f)
            assertEquals(settledControls.bottom.value, lyricsPaneBounds.bottom.value, 2f)

            onNodeWithContentDescription("Volume").performClick()
            mainClock.advanceTimeBy(200)
            waitForIdle()
            onNodeWithTag("centered-volume-popup").assertIsDisplayed()
        }
    }
}
