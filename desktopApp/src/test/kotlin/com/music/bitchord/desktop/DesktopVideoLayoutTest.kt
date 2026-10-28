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
import org.junit.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopVideoLayoutTest {
    @Test fun wideWatchLayoutKeepsVideoAndSidebarSeparate() = verify(1440, 900, true)
    @Test fun compactDesktopKeepsControlsAndTabsReachable() = verify(960, 600, false)

    private fun verify(width: Int, height: Int, export: Boolean) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            var lyrics by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3B302E), Color(0xFF14151A))))) {
                    DesktopMusicVideoLayout("Playing from Search", lyrics,
                        onSelectQueue = { lyrics = false }, onSelectLyrics = { lyrics = true },
                        video = { Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF242D35), Color(0xFF484D45))))) },
                        credits = { Column { Text("NAIF — Karena Kamu Cuma Satu", style = MaterialTheme.typography.titleLarge); Text("NAIFband Indonesia", color = Color.LightGray) } },
                        scrubber = { Column { LinearProgressIndicator(progress = { .24f }, modifier = Modifier.fillMaxWidth()); Text("0:28                                             4:11", style = MaterialTheme.typography.labelSmall) } },
                        transport = { Row { TextButton({}) { Text("Previous") }; TextButton({}) { Text("Pause") }; TextButton({}) { Text("Next") } } },
                        volume = { Text("Volume   ━━━━━━━━━") },
                        panel = { Column(Modifier.padding(20.dp)) { if (lyrics) Text("Lyrics content") else repeat(6) { Text("${it + 1}   Up next track", modifier = Modifier.padding(vertical = 14.dp)) } } },
                        panelActions = { Text("Output · System default", color = Color.LightGray) })
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
        onNodeWithText("Lyrics").performClick()
        onNodeWithText("Lyrics").assertIsSelected()
        onNodeWithText("Lyrics content").assertIsDisplayed()
        onNodeWithText("Up next").performClick()
        onNodeWithText("1   Up next track").assertIsDisplayed()
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
