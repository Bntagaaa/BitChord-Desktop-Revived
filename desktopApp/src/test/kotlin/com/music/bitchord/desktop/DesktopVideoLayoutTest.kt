package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.player.DesktopMusicVideoLayout
import com.music.bitchord.ui.player.DesktopVideoQueue
import com.music.bitchord.ui.player.DesktopVideoQualityControl
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.data.model.Song
import androidx.compose.ui.Alignment
import kotlin.test.assertEquals
import org.junit.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopVideoLayoutTest {
    @Test fun wideWatchLayoutKeepsVideoAndSidebarSeparate() = verify(1440, 900, true)
    @Test fun compactDesktopKeepsControlsAndQueueReachable() = verify(960, 600, false)

    private fun verify(width: Int, height: Int, export: Boolean) = runDesktopComposeUiTest(width = width, height = height) {
        PlayerPlatform.install(DesktopPlayerHost)
        setContent {

            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3B302E), Color(0xFF14151A))))) {
                    DesktopPlayerSheet(visible = true, onDismiss = {}, dragToDismiss = false) { _, _ ->
                    DesktopMusicVideoLayout("Playing from Search",
                        video = {
                            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF242D35), Color(0xFF484D45)))))
                            DesktopVideoQualityControl({}, Modifier.align(Alignment.TopEnd).padding(12.dp))
                        },
                        credits = { Column { Text("NAIF — Karena Kamu Cuma Satu", style = MaterialTheme.typography.titleLarge); Text("NAIFband Indonesia", color = Color.LightGray) } },
                        trackActions = { TextButton({}) { Text("More") } },
                        scrubber = { Column { LinearProgressIndicator(progress = { .24f }, modifier = Modifier.fillMaxWidth()); Text("0:28                                             4:11", style = MaterialTheme.typography.labelSmall) } },
                        transport = { Row { TextButton({}) { Text("Previous") }; TextButton({}) { Text("Pause") }; TextButton({}) { Text("Next") } } },
                        volume = { Text("Volume   ━━━━━━━━━") },
                        panel = { DesktopVideoQueue(
                            queue = listOf(Song("audio", "Current audio track", "Artist", null),
                                Song("video", "Music video", "Artist", null, isVideo = true),
                                Song("next", "Next track", "Artist", null)),
                            currentIndex = 0, autoplayEnabled = false, controlsLocked = false,
                            onJumpTo = {}, onRemove = {}, onMove = { _, _ -> }, onClear = {},
                            modifier = Modifier.fillMaxSize()) },
                        panelActions = { Text("System default", color = Color.LightGray) })
                    }
                }
            }
        }
        val stage = onNodeWithTag("desktop-video-stage").fetchSemanticsNode().boundsInRoot
        val sidebar = onNodeWithTag("desktop-video-sidebar").fetchSemanticsNode().boundsInRoot
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(stage.width > sidebar.width, "Video should dominate the watch layout")
        assertTrue(stage.right < sidebar.left, "Sidebar must not overlay the video")
        assertTrue(stage.height > root.height * .5f, "Avoid a small phone-sized video card")
        assertTrue(sidebar.right <= root.right && sidebar.bottom <= root.bottom)
        onNodeWithText("Pause").assertIsDisplayed()
        onNodeWithText("Lyrics").assertDoesNotExist()
        onNodeWithText("Music video").assertIsDisplayed()
        onAllNodesWithContentDescription("Toggle fullscreen").assertCountEquals(1)
        onAllNodesWithContentDescription("Switch to Picture in Picture").assertCountEquals(1)
        onAllNodesWithContentDescription("Switch to Picture in Picture").assertCountEquals(1)
        onNodeWithContentDescription("Fullscreen").assertDoesNotExist()
        onNodeWithContentDescription("Exit fullscreen").assertDoesNotExist()
        val audio = onNodeWithTag("queue-art-audio", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val video = onNodeWithTag("queue-art-video", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(1f, audio.width / audio.height, .01f)
        assertEquals(16f / 9, video.width / video.height, .03f)
        val timeline = onNodeWithTag("desktop-video-timeline").fetchSemanticsNode().boundsInRoot
        assertTrue(timeline.bottom <= root.bottom)
        assertTrue(timeline.top > stage.bottom)
        if (export) {
            val bitmap = onRoot().captureToImage()
            val pixels = bitmap.toPixelMap()
            val output = java.awt.image.BufferedImage(bitmap.width, bitmap.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) output.setRGB(x, y, pixels[x, y].toArgb())
            val target = java.io.File("build/reports/desktop-video-layout.png")
            target.parentFile.mkdirs()
            javax.imageio.ImageIO.write(output, "png", target)
        }
    }
}
