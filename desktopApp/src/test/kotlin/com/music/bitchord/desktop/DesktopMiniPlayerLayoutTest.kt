package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopMiniPlayerLayoutTest {
    @Test
    fun volumeHoverKeepsTheTrackFixedAndItsControlsDoNotExpandThePlayer() =
        runDesktopComposeUiTest(width = 840, height = 160) {
            mainClock.autoAdvance = false
            val events = PlayerEvents()
            setContent {
                MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography()) {
                    Box(Modifier.fillMaxSize().testTag("scene"), contentAlignment = Alignment.Center) {
                        PlayerFixture(events)
                    }
                }
            }
            mainClock.advanceTimeBy(300)
            val dock = onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
            val track = onNodeWithTag("mini-player-track").fetchSemanticsNode().boundsInRoot
            val progress = onNodeWithTag("mini-player-progress", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val tools = onNodeWithTag("mini-player-actions").fetchSemanticsNode().boundsInRoot
            onNodeWithContentDescription("Lyrics").assertIsDisplayed()
            assertEquals(track.left, progress.left, 1f)
            assertEquals(track.right, progress.right, 1f)
            assertTrue(progress.left > dock.left && progress.right < tools.left,
                "Progress belongs below the metadata, clear of the transport and tools")
            save(onRoot().captureToImage(), "wide-normal.png")

            val speaker = onNodeWithTag("mini-player-volume-button")
            speaker.performMouseInput { enter(center) }
            mainClock.advanceTimeBy(300)
            val slider = onNodeWithTag("mini-player-volume")
            slider.assertIsDisplayed()
            onNodeWithContentDescription("Lyrics").assertDoesNotExist()
            slider.performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(250)
            slider.assertIsDisplayed()
            assertEquals(dock, onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot)
            assertEquals(track, onNodeWithTag("mini-player-track").fetchSemanticsNode().boundsInRoot)
            assertEquals(tools, onNodeWithTag("mini-player-actions").fetchSemanticsNode().boundsInRoot)
            assertEquals(progress, onNodeWithTag("mini-player-progress", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot)
            save(onRoot().captureToImage(), "wide-volume-hover.png")

            // Crossing from the speaker to the slider must keep it open; leaving
            // the whole tools region must restore the actions after the grace period.
            onNodeWithTag("scene").performMouseInput { moveTo(Offset(20f, 20f)) }
            mainClock.advanceTimeBy(400)
            slider.assertDoesNotExist()
            onNodeWithContentDescription("Lyrics").assertIsDisplayed()

            speaker.performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(300)
            slider.performMouseInput {
                moveTo(Offset(center.x * 0.5f, center.y))
                press()
                moveTo(Offset(center.x * 1.5f, center.y))
                release()
            }
            speaker.performMouseInput { click(center) }
            runOnIdle {
                assertTrue(events.volume > 0.6f, "Dragging the visible slider must change playback volume")
                assertEquals(listOf("audio"), events.actions)
                val anchor = assertNotNull(events.outputAnchor)
                assertTrue(anchor.width > 0f && anchor.left >= tools.left && anchor.right <= dock.right)
            }
            onNodeWithTag("mini-player-track").performMouseInput { click(center) }
            runOnIdle { assertEquals(listOf("audio", "expand"), events.actions) }
        }

    @Test
    fun narrowOverflowKeepsDesktopToolsAndExistingSongActionsReachable() {
        // Exercise both the standalone fallback and the app's actual track-menu slot.
        for (withSongMenu in listOf(false, true)) {
            runDesktopComposeUiTest(width = 360, height = 700) {
                val events = PlayerEvents()
                val portal = DesktopMenuPortalState()
                setContent {
                    MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography()) {
                        CompositionLocalProvider(LocalDesktopMenuPortal provides portal) {
                            Box(Modifier.fillMaxSize().desktopMenuPointer(portal),
                                contentAlignment = Alignment.BottomCenter) {
                                PlayerFixture(events, withSongMenu = withSongMenu)
                                DesktopMenuPortal(portal)
                            }
                        }
                    }
                }
                val labels = listOf("Shuffle", "Repeat Off", "Lyrics", "Queue",
                    "Picture in picture", "Now playing details", "Audio output")
                onNodeWithContentDescription("More options").performMouseInput { click() }
                labels.forEach { onNodeWithText(it).assertIsDisplayed() }
                save(onRoot().captureToImage(), if (withSongMenu) "narrow-song-menu.png" else "narrow-player-menu.png")
                onNodeWithText("Shuffle").performMouseInput { click() }
                for (label in labels.drop(1)) {
                    onNodeWithContentDescription("More options").performMouseInput { click() }
                    onNodeWithText(label).assertIsDisplayed().performMouseInput { click() }
                }
                runOnIdle {
                    assertTrue(events.shuffle)
                    assertEquals(DesktopRepeatMode.ALL, events.repeatMode)
                    assertEquals(listOf("shuffle", "repeat", "lyrics", "queue", "pip", "details", "audio"), events.actions)
                    assertNotNull(events.outputAnchor)
                }
                if (withSongMenu) {
                    onNodeWithContentDescription("More options").performMouseInput { click() }
                    onNodeWithText("Add to queue").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                    runOnIdle { assertEquals("add-to-queue", events.actions.last()) }
                }
                onNodeWithText("Picture in picture").assertDoesNotExist()
                runOnIdle { assertTrue("expand" !in events.actions) }
            }
        }
    }

    @Test
    fun longTitlesAndEmptyStateStayInsideTheCapsuleAcrossThemeAndScale() {
        val originalTheme = DesktopTheme.mode
        val originalPitchBlack = DesktopTheme.pitchBlack
        val artwork = DesktopPipUiTest.fixtureArtwork()
        val variants = listOf(
            Variant(736, 1f), Variant(736, 1f, light = true), Variant(736, 1f, empty = true),
            Variant(480, 1f), Variant(360, 1f),
            Variant(736, 1.25f), Variant(480, 1.25f), Variant(360, 1.25f),
        )
        try {
            DesktopTheme.updatePitchBlack(false)
            for (variant in variants) {
                DesktopTheme.selectMode(if (variant.light) DesktopThemeMode.LIGHT else DesktopThemeMode.DARK)
                val song = if (variant.empty) null else longSong.copy(thumbnailUrl = artwork.toURI().toString())
                runDesktopComposeUiTest(width = ((variant.width + 48) * variant.scale).roundToInt(),
                    height = (136 * variant.scale).roundToInt()) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(variant.scale)) {
                            MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography()) {
                                Box(Modifier.fillMaxSize().background(DesktopBackground).padding(24.dp),
                                    contentAlignment = Alignment.Center) {
                                    val events = remember { PlayerEvents() }
                                    Box(Modifier.width(variant.width.dp)) { PlayerFixture(events, song) }
                                }
                            }
                        }
                    }
                    val dock = onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
                    assertEquals(variant.width * variant.scale, dock.width, 1f)
                    assertEquals(64f * variant.scale, dock.height, 1f)
                    val tools = onNodeWithTag("mini-player-actions").fetchSemanticsNode().boundsInRoot
                    assertTrue(tools.left >= dock.left && tools.right <= dock.right)
                    if (song == null) {
                        onNodeWithTag("mini-player-track").assertDoesNotExist()
                        onNodeWithTag("mini-player-progress", useUnmergedTree = true).assertDoesNotExist()
                        onNodeWithContentDescription("Play").assertIsNotEnabled()
                        val logo = onNodeWithContentDescription("BitChord").fetchSemanticsNode().boundsInRoot
                        assertEquals(dock.center.x, logo.center.x, 1f)
                    } else {
                        val track = onNodeWithTag("mini-player-track").fetchSemanticsNode().boundsInRoot
                        assertTrue(track.width > 0f && track.left >= dock.left && track.right <= tools.left,
                            "Long title must retain a visible bounded track section at ${variant.width}dp")
                        val title = onNodeWithText(song.title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                        assertTrue(title.left >= track.left && title.right <= track.right + 1f)
                        onNodeWithContentDescription("Like").assertIsDisplayed()
                        onNodeWithContentDescription("More options").assertIsDisplayed()
                    }
                    save(onRoot().captureToImage(), variant.filename)
                }
            }
        } finally {
            DesktopTheme.selectMode(originalTheme)
            DesktopTheme.updatePitchBlack(originalPitchBlack)
            artwork.delete()
        }
    }

    private data class Variant(val width: Int, val scale: Float, val light: Boolean = false, val empty: Boolean = false) {
        val filename: String get() = "${width}dp-${(scale * 100).roundToInt()}pct-${if (light) "light" else "dark"}${if (empty) "-empty" else ""}.png"
    }

    private class PlayerEvents {
        val actions = mutableListOf<String>()
        var volume by mutableStateOf(0.5f)
        var shuffle by mutableStateOf(false)
        var repeatMode by mutableStateOf(DesktopRepeatMode.OFF)
        var outputAnchor: Rect? = null
    }

    @Composable
    private fun PlayerFixture(events: PlayerEvents, song: Song? = longSong, withSongMenu: Boolean = false) {
        var menuOpen by remember { mutableStateOf(false) }
        val menu: (@Composable (DesktopMiniPlayerMenuActions) -> Unit)? = if (song != null && withSongMenu) {
            { miniActions ->
                Box {
                    DesktopMoreButton(onClick = { menuOpen = true })
                    if (menuOpen) {
                        DesktopSongMenuFor(song, false, DesktopSongActions(
                            signedIn = true, disliked = false, downloaded = false, downloadInProgress = false,
                            sleepTimerMinutes = null, sleepAfterTrack = false, onToggleDislike = {},
                            onAddToPlaylist = {}, onDownload = {}, onRemoveDownload = {}, onStartRadio = {},
                            onPlayNext = {}, onAddToQueue = { events.actions += "add-to-queue" },
                            onOpenAlbum = {}, onOpenArtist = {}, onSleepTimer = {}, onSleepAfterTrack = {}, onShare = {},
                        ), {}, null, null, onDismiss = { menuOpen = false }, miniPlayerActions = miniActions)
                    }
                }
            }
        } else null
        DesktopMiniPlayer(
            song = song, isPlaying = song != null, isLoading = false, previousEnabled = song != null,
            progress = 0.36f, volume = events.volume, shuffle = events.shuffle, repeatMode = events.repeatMode,
            sidePanel = null, onExpand = { events.actions += "expand" }, onPlayPause = {}, onPrevious = {}, onNext = {},
            onShuffleChange = { events.shuffle = it; events.actions += "shuffle" },
            onRepeatModeChange = { events.repeatMode = it; events.actions += "repeat" },
            onVolumeChange = { events.volume = it },
            onOpenAudioOutput = { events.outputAnchor = it; events.actions += "audio" },
            onOpenLyrics = { events.actions += "lyrics" }, onOpenPip = { events.actions += "pip" },
            onOpenQueue = { events.actions += "queue" }, onOpenDetails = { events.actions += "details" },
            onToggleLike = {}, songMenu = menu,
        )
    }

    private fun save(bitmap: ImageBitmap, name: String) {
        val pixels = bitmap.toPixelMap()
        val output = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) output.setRGB(x, y, pixels[x, y].toArgb())
        val target = File("build/reports/mini-player/$name")
        target.parentFile.mkdirs()
        ImageIO.write(output, "png", target)
    }

    companion object {
        private val longSong = Song("mini-player-test", "Berapa Kali Kita Akan Saling Memaafkan — A deliberately long track title", "Pamungkas and a long artist credit", null)
    }
}
