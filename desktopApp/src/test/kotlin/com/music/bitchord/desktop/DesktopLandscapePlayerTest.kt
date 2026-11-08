package com.music.bitchord.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.player.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercise the real slot contents; a one-box control placeholder missed the overlap. */
@OptIn(ExperimentalTestApi::class)
class DesktopLandscapePlayerTest {
    @Test fun actualLandscapePlayerAnimatesBetweenItsMainAndLyricsLayouts() = verify(1360, 800)
    @Test fun actualCompactLandscapePlayerKeepsItsOutputCaptionBelowTheButtons() = verify(900, 420)
    @Test fun scaledDesktopWindowCentersVisibleButtonsAndKeepsLyricsControlsUnclipped() =
        verify(1920, 1010, density = 1.25f, reduceBlur = false, verifyScroll = false)

    @Test fun changingTracksWithLyricsOpenRevealsTheRealMetadataForTwoSeconds() =
        verify(1000, 600, verifyTrackChange = true)

    private fun verify(width: Int, height: Int, density: Float = 1f, reduceBlur: Boolean = true,
        verifyTrackChange: Boolean = false, verifyScroll: Boolean = true) = runDesktopComposeUiTest(width = width, height = height) {
        mainClock.autoAdvance = false
        val lastScreen = MutableStateFlow(LastPlayerScreen.MAIN)
        val host = object : PlayerHost by DesktopPlayerHost {
            override val settings = object : PlayerSettingsSource by DesktopPlayerSettings {
                override val lastPlayerScreen = lastScreen
                override val reduceAnimation = MutableStateFlow(false)
                override val reduceDynamicBlur = MutableStateFlow(reduceBlur)
                override val animatedCanvas = MutableStateFlow(false)
                override val hideSongStatus = MutableStateFlow(false)
                override val hideVolumeBar = MutableStateFlow(false)
                override val syncedLyrics = MutableStateFlow(true)
                override fun setLastPlayerScreen(value: LastPlayerScreen) { lastScreen.value = value }
            }
            override suspend fun canvasFor(song: Song): com.music.bitchord.data.canvas.CanvasArtwork? = null
            @Composable override fun rememberRemoteArtworkUrl(song: Song?): String? = null
        }
        PlayerPlatform.install(host)
        DesktopWindowMode.exit()
        val mounted = mutableStateOf(true)
        val songs = listOf(
            Song("landscape-a", "Landscape Track A", "Test Artist", null),
            Song("landscape-b", "Landscape Track B", "Test Artist B", null),
        )
        val index = mutableStateOf(0)
        val playhead = PlaybackPosition().apply { report(28_000) }
        try {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                if (mounted.value) MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) {
                    DesktopPlayerSheet(true, onDismiss = {}, dragToDismiss = false) { windowWidth, windowHeight ->
                        NowPlayingScreen(song = songs[index.value], isPlaying = false, isLoading = false,
                            position = playhead, durationMs = 400_000,
                            audioVersionSwitching = false, qualityUpgraded = false,
                            queue = songs, queueIndex = index.value, hasPrevious = true, hasNext = true,
                            repeatMode = 0, shuffleEnabled = false, autoplayEnabled = false,
                            signedIn = true, accountName = null, likeStatus = LikeStatus.INDIFFERENT,
                            onToggleLike = {}, onPlayPause = {}, onNext = { index.value = 1 }, onPrevious = { index.value = 0 },
                            onBlockedControl = {}, onSeek = {}, onSeekFraction = {}, onToggleShuffle = {},
                            onCycleRepeat = {}, onToggleAutoplay = {}, onJumpTo = {}, onRemoveFromQueue = {},
                            onMoveInQueue = { _, _ -> }, onClearQueue = {}, onOpenMenu = {}, onOpenAlbum = {},
                            onOpenArtist = { _, _ -> }, onOpenPlaybackSource = {}, onListenTogether = {},
                            lyrics = listOf(LyricLine(0, "First lyric"), LyricLine(20_000, "Current lyric"),
                                LyricLine(40_000, "Next lyric"), LyricLine(60_000, "Last lyric")) +
                                (4..15).map { LyricLine(it * 20_000L, "Later lyric $it") },
                            lyricsSource = LyricsSource.KUGOU, lyricsProviderStates = emptyMap(),
                            onSelectLyricsProvider = {}, lyricsUnavailable = false, lyricsOffsetOpen = false,
                            onDismissLyricsOffset = {}, windowWidth = windowWidth, windowHeight = windowHeight)
                    }
                }
                }
            }
            mainClock.advanceTimeBy(2500)
            waitForIdle()
            val artwork = onNodeWithTag("landscape-artwork").getUnclippedBoundsInRoot()
            val header = onNodeWithTag("landscape-track-header").getUnclippedBoundsInRoot()
            val controls = onNodeWithTag("landscape-main-controls").getUnclippedBoundsInRoot()
            val output = onNodeWithTag("player-output-caption").getUnclippedBoundsInRoot()
            val lyricsButton = onNodeWithContentDescription("Lyrics").getUnclippedBoundsInRoot()
            assertTrue(header.top >= artwork.top, "Main metadata must stay above its transport without entering the toolbar")
            assertTrue(header.bottom < controls.top, "Transport must sit beneath the metadata")
            assertTrue(artwork.bottom < lyricsButton.top)
            assertTrue(lyricsButton.bottom < output.top, "Output caption must never overlay the real action buttons")
            val actionBounds = onNodeWithTag("landscape-output-controls").getUnclippedBoundsInRoot()
            val columns = onNodeWithTag("landscape-columns").getUnclippedBoundsInRoot()
            assertEquals((actionBounds.top - artwork.bottom).value, (columns.bottom - actionBounds.bottom).value, 1f)
            assertEquals((columns.left.value + columns.right.value) / 2,
                (actionBounds.left.value + actionBounds.right.value) / 2, 1f, "Center the complete group in the whole player")
            assertEquals(width / 2f,
                (onNodeWithContentDescription("Lyrics").fetchSemanticsNode().boundsInRoot.left +
                    onNodeWithContentDescription("Up next").fetchSemanticsNode().boundsInRoot.right) / 2f,
                1f, "Center the visible controls in the window, not just their container")
            assertTrue(controls.bottom <= actionBounds.top, "The centered toolbar must not cover compact transport controls")
            assertTrue(output.bottom <= onNodeWithTag("landscape-output-controls").getUnclippedBoundsInRoot().bottom)
            save(onRoot().captureToImage(), "$width-main.png")

            val title = onNodeWithText("Landscape Track A").getUnclippedBoundsInRoot()
            onNodeWithContentDescription("Next").performClick()
            mainClock.advanceTimeBy(120)
            waitForIdle()
            assertTrue(onNodeWithText("Landscape Track B").getUnclippedBoundsInRoot().top > title.top,
                "The actual song title must enter from below, without moving the header")
            assertEquals(header, onNodeWithTag("landscape-track-header").getUnclippedBoundsInRoot())
            assertEquals(controls, onNodeWithTag("landscape-main-controls").getUnclippedBoundsInRoot())
            mainClock.advanceTimeBy(500)

            onNodeWithContentDescription("Lyrics").performClick()
            mainClock.advanceTimeBy(140)
            waitForIdle()
            val moving = onNodeWithTag("landscape-track-header").getUnclippedBoundsInRoot()
            if (header.top > artwork.top) {
                assertTrue(moving.top >= artwork.top && moving.top < header.top,
                    "The real header must travel upward when Lyrics opens")
            } else assertEquals(artwork.top, moving.top)
            assertEquals(output, onNodeWithTag("player-output-caption").getUnclippedBoundsInRoot())
            save(onRoot().captureToImage(), "$width-opening-lyrics.png")
            mainClock.advanceTimeBy(600)
            waitForIdle()
            val lyricsHeader = onNodeWithTag("landscape-track-header").getUnclippedBoundsInRoot()
            assertEquals(artwork.top.value, lyricsHeader.top.value, 1f)
            assertEquals(artwork.top, onNodeWithTag("landscape-lyrics-body").getUnclippedBoundsInRoot().top)
            val frame = onNodeWithTag("landscape-lyrics-frame").getUnclippedBoundsInRoot()
            val viewport = onNodeWithTag("landscape-lyrics-viewport").getUnclippedBoundsInRoot()
            assertEquals(artwork.bottom.value, frame.bottom.value, 1f, "Lyrics cut out at the bottom of the cover")
            assertEquals(frame.bottom, viewport.bottom, "Hidden controls reserve no space")
            assertEquals(artwork.top, viewport.top, "Lyrics fill the header area rather than reserving a gap")
            onNodeWithText("Lyrics by KuGou").assertDoesNotExist()
            onNodeWithContentDescription("Romanize lyrics").assertDoesNotExist()
            onNodeWithContentDescription("Translate lyrics").assertDoesNotExist()
            save(onRoot().captureToImage(), "$width-lyrics-hidden-controls.png")
            mainClock.advanceTimeBy(2000)
            waitForIdle()
            onNodeWithText("Landscape Track B").assertDoesNotExist()
            onNodeWithText("Test Artist B").assertDoesNotExist()
            assertEquals(viewport, onNodeWithTag("landscape-lyrics-viewport").getUnclippedBoundsInRoot())

            if (verifyTrackChange) {
                runOnIdle { index.value = 0 }
                mainClock.advanceTimeBy(400)
                waitForIdle()
                onNodeWithText("Landscape Track A").assertIsDisplayed()
                onNodeWithText("Test Artist").assertIsDisplayed()
                onNodeWithText("Lyrics by KuGou").assertDoesNotExist()
                mainClock.advanceTimeBy(1400)
                onNodeWithText("Landscape Track A").assertIsDisplayed()
                mainClock.advanceTimeBy(500)
                waitForIdle()
                onNodeWithText("Landscape Track A").assertDoesNotExist()
                onNodeWithText("Test Artist").assertDoesNotExist()
                assertEquals(viewport, onNodeWithTag("landscape-lyrics-viewport").getUnclippedBoundsInRoot())
                return@runDesktopComposeUiTest
            }

            onNodeWithTag("landscape-lyrics-frame").performMouseInput { enter(center) }
            mainClock.advanceTimeBy(300)
            waitForIdle()
            onNodeWithText("Lyrics by KuGou").assertIsDisplayed()
            onNodeWithText("Landscape Track B").assertIsDisplayed()
            onNodeWithText("Test Artist B").assertIsDisplayed()
            onNodeWithContentDescription("Romanize lyrics").assertIsDisplayed()
            onNodeWithContentDescription("Translate lyrics").assertIsDisplayed()
            for (description in listOf("Romanize lyrics", "Translate lyrics")) {
                val visibleBounds = onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
                assertEquals(34f * density, visibleBounds.height, 1f,
                    "$description must be fully visible, not only the top of its circle")
            }
            val shortened = onNodeWithTag("landscape-lyrics-viewport").getUnclippedBoundsInRoot()
            assertEquals(44f, (viewport.bottom - shortened.bottom).value, 1f)
            assertEquals(frame, onNodeWithTag("landscape-lyrics-frame").getUnclippedBoundsInRoot())
            assertEquals(lyricsHeader, onNodeWithTag("landscape-track-header").getUnclippedBoundsInRoot())
            save(onRoot().captureToImage(), "$width-lyrics-hover-controls.png")

            // Enter the revealed footer itself: the fixed outer hover target must
            // keep all three controls present until the pointer actually leaves.
            onNodeWithText("Lyrics by KuGou").performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(100)
            onNodeWithContentDescription("Translate lyrics").assertIsDisplayed()
            onNodeWithTag("landscape-lyrics-frame").performMouseInput { moveTo(centerRight + Offset(8f, 0f)) }
            mainClock.advanceTimeBy(300)
            waitForIdle()
            onNodeWithText("Lyrics by KuGou").assertDoesNotExist()
            onNodeWithText("Landscape Track B").assertDoesNotExist()
            assertEquals(viewport, onNodeWithTag("landscape-lyrics-viewport").getUnclippedBoundsInRoot())

            if (verifyScroll) {
                runOnIdle { playhead.report(0) }
                mainClock.advanceTimeBy(2000)
                waitForIdle()
                val firstRow = onNodeWithTag("lyrics-row-0").getUnclippedBoundsInRoot()
                onNodeWithText("Landscape Track B").assertDoesNotExist()
                assertTrue((firstRow.top - frame.top).value in 0f..60f,
                    "The opening lyric starts at the top instead of leaving half the cover empty")
                save(onRoot().captureToImage(), "$width-lyrics-start.png")
                runOnIdle { playhead.report(200_000) }
                mainClock.advanceTimeBy(2000)
                waitForIdle()
                val laterRow = onNodeWithTag("lyrics-row-10").getUnclippedBoundsInRoot()
                assertEquals((frame.top.value + frame.bottom.value) / 2f,
                    (laterRow.top.value + laterRow.bottom.value) / 2f, 3f,
                    "Later lyrics settle in the center once preceding rows fill the upper half")
                save(onRoot().captureToImage(), "$width-lyrics-later.png")
                runOnIdle { playhead.report(0) }
                mainClock.advanceTimeBy(2000)
                waitForIdle()
                assertEquals(firstRow.top.value,
                    onNodeWithTag("lyrics-row-0").getUnclippedBoundsInRoot().top.value, 1f,
                    "Seeking back to the opening returns to the top")
            } else {
                // Normal/compact cases exercise the long seek scenario. The
                // HiDPI case focuses on real viewport/control geometry with blur.
                val opening = onNodeWithTag("lyrics-row-0").getUnclippedBoundsInRoot()
                assertTrue((opening.top - frame.top).value in 0f..60f)
            }

            onNodeWithContentDescription("Close lyrics").performClick()
            mainClock.advanceTimeBy(600)
            waitForIdle()
            assertEquals(header, onNodeWithTag("landscape-track-header").getUnclippedBoundsInRoot())
            assertEquals(controls, onNodeWithTag("landscape-main-controls").getUnclippedBoundsInRoot())
            onNodeWithContentDescription("Up next").performClick()
            mainClock.advanceTimeBy(600)
            waitForIdle()
            val queueBounds = onNodeWithTag("landscape-other-pane").getUnclippedBoundsInRoot()
            assertEquals(artwork.top.value, queueBounds.top.value, 1f)
            assertEquals(artwork.bottom.value, queueBounds.bottom.value, 1f)
        } finally {
            runOnIdle { mounted.value = false; DesktopWindowMode.exit() }
            waitForIdle()
            PlayerPlatform.install(DesktopPlayerHost)
        }
    }

    private fun save(bitmap: androidx.compose.ui.graphics.ImageBitmap, name: String) {
        val pixels = bitmap.toPixelMap()
        val output = java.awt.image.BufferedImage(bitmap.width, bitmap.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val argb = IntArray(bitmap.width * bitmap.height) {
            pixels[it % bitmap.width, it / bitmap.width].toArgb()
        }
        output.setRGB(0, 0, bitmap.width, bitmap.height, argb, 0, bitmap.width)
        val target = java.io.File("build/reports/landscape-player/$name")
        target.parentFile.mkdirs()
        javax.imageio.ImageIO.write(output, "png", target)
    }
}
