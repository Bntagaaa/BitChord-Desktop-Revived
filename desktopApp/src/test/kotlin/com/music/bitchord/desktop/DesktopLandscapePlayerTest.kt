package com.music.bitchord.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
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

    private fun verify(width: Int, height: Int) = runDesktopComposeUiTest(width = width, height = height) {
        mainClock.autoAdvance = false
        val lastScreen = MutableStateFlow(LastPlayerScreen.MAIN)
        val host = object : PlayerHost by DesktopPlayerHost {
            override val settings = object : PlayerSettingsSource by DesktopPlayerSettings {
                override val lastPlayerScreen = lastScreen
                override val reduceAnimation = MutableStateFlow(false)
                override val reduceDynamicBlur = MutableStateFlow(true)
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
        try {
            setContent {
                if (mounted.value) MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) {
                    DesktopPlayerSheet(true, onDismiss = {}, dragToDismiss = false) { windowWidth, windowHeight ->
                        NowPlayingScreen(song = songs[index.value], isPlaying = false, isLoading = false,
                            position = remember { PlaybackPosition().apply { report(28_000) } }, durationMs = 240_000,
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
                                LyricLine(40_000, "Next lyric"), LyricLine(60_000, "Last lyric")),
                            lyricsSource = LyricsSource.KUGOU, lyricsProviderStates = emptyMap(),
                            onSelectLyricsProvider = {}, lyricsUnavailable = false, lyricsOffsetOpen = false,
                            onDismissLyricsOffset = {}, windowWidth = windowWidth, windowHeight = windowHeight)
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
            assertTrue(header.top > artwork.top, "Main metadata must retain its centered position")
            assertTrue(header.bottom < controls.top, "Transport must sit beneath the metadata")
            assertTrue(artwork.bottom < lyricsButton.top)
            assertTrue(lyricsButton.bottom < output.top, "Output caption must never overlay the real action buttons")
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
            assertTrue(moving.top > artwork.top && moving.top < header.top,
                "The real header must visibly travel upward when Lyrics opens")
            assertEquals(output, onNodeWithTag("player-output-caption").getUnclippedBoundsInRoot())
            save(onRoot().captureToImage(), "$width-opening-lyrics.png")
            mainClock.advanceTimeBy(600)
            waitForIdle()
            val lyricsHeader = onNodeWithTag("landscape-track-header").getUnclippedBoundsInRoot()
            assertEquals(artwork.top.value, lyricsHeader.top.value, 1f)
            assertTrue(onNodeWithTag("landscape-lyrics-body").getUnclippedBoundsInRoot().top >= lyricsHeader.bottom)
            val frame = onNodeWithTag("landscape-lyrics-frame").getUnclippedBoundsInRoot()
            val viewport = onNodeWithTag("landscape-lyrics-viewport").getUnclippedBoundsInRoot()
            assertEquals(artwork.bottom.value, frame.bottom.value, 1f, "Lyrics cut out at the bottom of the cover")
            assertEquals(frame.bottom, viewport.bottom, "Hidden controls reserve no space")
            onNodeWithText("Lyrics by KuGou").assertDoesNotExist()
            onNodeWithContentDescription("Romanize lyrics").assertDoesNotExist()
            onNodeWithContentDescription("Translate lyrics").assertDoesNotExist()
            save(onRoot().captureToImage(), "$width-lyrics-hidden-controls.png")

            onNodeWithTag("landscape-lyrics-frame").performMouseInput { enter(center) }
            mainClock.advanceTimeBy(300)
            waitForIdle()
            onNodeWithText("Lyrics by KuGou").assertIsDisplayed()
            onNodeWithContentDescription("Romanize lyrics").assertIsDisplayed()
            onNodeWithContentDescription("Translate lyrics").assertIsDisplayed()
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
            assertEquals(viewport, onNodeWithTag("landscape-lyrics-viewport").getUnclippedBoundsInRoot())

            onNodeWithContentDescription("Close lyrics").performClick()
            mainClock.advanceTimeBy(600)
            waitForIdle()
            assertEquals(header, onNodeWithTag("landscape-track-header").getUnclippedBoundsInRoot())
            assertEquals(controls, onNodeWithTag("landscape-main-controls").getUnclippedBoundsInRoot())
        } finally {
            runOnIdle { mounted.value = false; DesktopWindowMode.exit() }
            waitForIdle()
            PlayerPlatform.install(DesktopPlayerHost)
        }
    }

    private fun save(bitmap: androidx.compose.ui.graphics.ImageBitmap, name: String) {
        val pixels = bitmap.toPixelMap()
        val output = java.awt.image.BufferedImage(bitmap.width, bitmap.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) output.setRGB(x, y, pixels[x, y].toArgb())
        val target = java.io.File("build/reports/landscape-player/$name")
        target.parentFile.mkdirs()
        javax.imageio.ImageIO.write(output, "png", target)
    }
}
