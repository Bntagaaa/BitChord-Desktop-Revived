package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.player.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DesktopVideoPlayerTest {
    @Test fun realPlayerUsesOneFullscreenControlAndNoLyricsAcrossReopen() = verify(1440, 900, true)
    @Test fun compactPlayerRetainsDesktopWatchView() = verify(800, 560, false)

    private fun verify(width: Int, height: Int, export: Boolean) = runDesktopComposeUiTest(width = width, height = height) {
        var canvasCalls = 0
        val beta = MutableStateFlow(true)
        val host = object : PlayerHost by DesktopPlayerHost {
            override val musicVideoEnabled = beta
            override val settings = object : PlayerSettingsSource by DesktopPlayerSettings {
                override val reduceAnimation = MutableStateFlow(true)
                override val reduceDynamicBlur = MutableStateFlow(true)
            }
            override suspend fun canvasFor(song: Song): com.music.bitchord.data.canvas.CanvasArtwork? { canvasCalls++; return null }
            @Composable override fun rememberRemoteArtworkUrl(song: Song?): String? = null
            @Composable override fun MusicVideo(videoId: String, fit: Boolean, onRenderedChanged: (Boolean) -> Unit, modifier: Modifier) {
                LaunchedEffect(videoId) { onRenderedChanged(true) }
                Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF38444F), Color(0xFF66554B)))))
            }
        }
        PlayerPlatform.install(host)
        DesktopWindowMode.exit()
        val visible = mutableStateOf(true)
        // Unmount the real player before the coroutine test scope finishes.
        val mounted = mutableStateOf(true)
        val song = Song("preview", "NAIF — Karena Kamu Cuma Satu", "NAIFband Indonesia", null, isVideo = true)
        val songs = listOf(song, song.copy(videoId = "next", title = "Next music video"))
        try {
            setContent {
                if (mounted.value) MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) {
                    DesktopPlayerSheet(visible.value, onDismiss = { visible.value = false }, dragToDismiss = false) { width, height ->
                        NowPlayingScreen(song = song, isPlaying = false, isLoading = false,
                            position = remember { PlaybackPosition().apply { report(28_000) } }, durationMs = 240_000,
                            audioVersionSwitching = false, qualityUpgraded = false,
                            queue = songs, queueIndex = 0, hasPrevious = false, hasNext = true,
                            repeatMode = 0, shuffleEnabled = false, autoplayEnabled = false,
                            signedIn = true, accountName = null, likeStatus = LikeStatus.INDIFFERENT,
                            onToggleLike = {}, onPlayPause = {}, onNext = {}, onPrevious = {}, onBlockedControl = {},
                            onSeek = {}, onSeekFraction = {}, onToggleShuffle = {}, onCycleRepeat = {}, onToggleAutoplay = {},
                            onJumpTo = {}, onRemoveFromQueue = {}, onMoveInQueue = { _, _ -> }, onClearQueue = {},
                            onOpenMenu = {}, onOpenAlbum = {}, onOpenArtist = { _, _ -> }, onOpenPlaybackSource = {},
                            onListenTogether = {}, lyrics = null, lyricsSource = null, lyricsProviderStates = emptyMap(),
                            onSelectLyricsProvider = {}, lyricsUnavailable = true, lyricsOffsetOpen = false,
                            onDismissLyricsOffset = {}, windowWidth = width, windowHeight = height)
                    }
                }
            }
            mainClock.advanceTimeBy(500)
            onNodeWithTag("desktop-video-sidebar").assertIsDisplayed()
            onNodeWithText("Lyrics").assertDoesNotExist()
            onNodeWithContentDescription("Lyrics").assertDoesNotExist()
            onAllNodesWithContentDescription("Toggle fullscreen").assertCountEquals(1)
            onNodeWithContentDescription("Fullscreen").assertDoesNotExist()
            // Validate the real player on compact sizes. The wide test owns lifecycle stress.
            onNodeWithTag("desktop-video-stage").assertIsDisplayed()
            if (export) {
                val bitmap = onRoot().captureToImage()
                val pixels = bitmap.toPixelMap()
                val output = java.awt.image.BufferedImage(bitmap.width, bitmap.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) output.setRGB(x, y, pixels[x, y].toArgb())
                val target = java.io.File("build/reports/desktop-video-player.png")
                target.parentFile.mkdirs(); javax.imageio.ImageIO.write(output, "png", target)
            }
            if (export) {
                onNodeWithContentDescription("Toggle fullscreen").performClick()
                mainClock.advanceTimeBy(300)
                onNodeWithTag("desktop-video-sidebar").assertDoesNotExist()
                onAllNodesWithContentDescription("Toggle fullscreen").assertCountEquals(1)
                onNodeWithContentDescription("Exit fullscreen").assertDoesNotExist()
                onNodeWithContentDescription("Toggle fullscreen").performClick()
                mainClock.advanceTimeBy(300)
                onNodeWithTag("desktop-video-sidebar").assertIsDisplayed()
                onNodeWithContentDescription("Minimize player").performClick()
                mainClock.advanceTimeBy(400)
                onNodeWithTag("desktop-video-sidebar").assertDoesNotExist()
                runOnIdle { visible.value = true }
                mainClock.advanceTimeBy(100)
                onNodeWithTag("desktop-video-sidebar").assertIsDisplayed()
                runOnIdle { beta.value = false }
                mainClock.advanceTimeBy(300)
                onNodeWithTag("desktop-video-sidebar").assertDoesNotExist()
                runOnIdle { beta.value = true }
                mainClock.advanceTimeBy(300)
                onNodeWithTag("desktop-video-sidebar").assertIsDisplayed()
            }
            assertEquals(0, canvasCalls, "A music video must not start another Canvas lookup")
        } finally {
            // Close the actual composition before the coroutine test scheduler is torn down.
            runOnIdle {
                mounted.value = false
                visible.value = false
                beta.value = false
                DesktopWindowMode.exit()
            }
            waitForIdle()
            PlayerPlatform.install(DesktopPlayerHost)
        }
    }
}
