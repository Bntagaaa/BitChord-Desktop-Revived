package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.music.bitchord.data.model.Song
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopAutomixOverviewMenuTest {
    private fun sampleActions() = DesktopSongActions(
        signedIn = true,
        disliked = false,
        downloaded = false,
        downloadInProgress = false,
        sleepTimerMinutes = null,
        sleepAfterTrack = false,
        onToggleDislike = {},
        onAddToPlaylist = {},
        onDownload = {},
        onRemoveDownload = {},
        onStartRadio = {},
        onPlayNext = {},
        onAddToQueue = {},
        onOpenAlbum = {},
        onOpenArtist = {},
        onSleepTimer = {},
        onSleepAfterTrack = {},
        onShare = {},
    )

    @Test
    fun songMenuShowsAutomixOverviewWhenProvidedAndInvokesCallback() = runDesktopComposeUiTest(width = 640, height = 720) {
        val song = Song("song-1", "Focus", "NIKI", null)
        val portal = DesktopMenuPortalState()
        var opened = 0
        var dismissed = 0
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopMenuPortal provides portal) {
                    Box(Modifier.fillMaxSize().desktopMenuPointer(portal)) {
                        DesktopSongMenuFor(
                            song = song,
                            liked = false,
                            actions = sampleActions(),
                            onToggleLike = {},
                            onRevertToOriginal = null,
                            onUpgradeQuality = null,
                            onDismiss = { dismissed++ },
                            onAutomixOverview = { opened++ },
                        )
                        DesktopMenuPortal(portal)
                    }
                }
            }
        }
        onNodeWithText("Automix overview").assertIsDisplayed().performMouseInput { click() }
        runOnIdle {
            assertEquals(1, opened)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun songMenuHidesAutomixOverviewWhenOmitted() = runDesktopComposeUiTest(width = 640, height = 720) {
        val song = Song("song-1", "Focus", "NIKI", null)
        val portal = DesktopMenuPortalState()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopMenuPortal provides portal) {
                    Box(Modifier.fillMaxSize().desktopMenuPointer(portal)) {
                        DesktopSongMenuFor(
                            song = song,
                            liked = false,
                            actions = sampleActions(),
                            onToggleLike = {},
                            onRevertToOriginal = null,
                            onUpgradeQuality = null,
                            onDismiss = {},
                            onAutomixOverview = null,
                        )
                        DesktopMenuPortal(portal)
                    }
                }
            }
        }
        onNodeWithText("Sleep timer").assertIsDisplayed()
        assertTrue(onAllNodesWithText("Automix overview").fetchSemanticsNodes().isEmpty())
    }
}
