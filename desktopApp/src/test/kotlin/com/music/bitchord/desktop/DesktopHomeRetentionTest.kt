package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopHomeRetentionTest {
    @Test fun homeOnlyKeepsTheFeedWhileItIsActuallyVisible() {
        assertTrue(isHomeFeedVisible(DesktopDestination.LISTEN_NOW))
        listOf(
            DesktopDestination.NEW, DesktopDestination.SEARCH,
            DesktopDestination.LIBRARY, DesktopDestination.DOWNLOADS,
        ).forEach { assertFalse(isHomeFeedVisible(it)) }
    }

    @Test fun nestedPagesDiscardHomeEvenWhenSidebarStillPointsAtHome() {
        assertFalse(isHomeFeedVisible(DesktopDestination.LISTEN_NOW, nestedPageOpen = true))
        assertFalse(isHomeFeedVisible(DesktopDestination.LISTEN_NOW, settingsOpen = true))
        assertFalse(isHomeFeedVisible(DesktopDestination.LISTEN_NOW, replayOpen = true))
        assertFalse(isHomeFeedVisible(DesktopDestination.LISTEN_NOW, moodGridOpen = true))
    }

    @Test fun fullscreenNowPlayingRetainsHomeUntilActualNavigation() {
        // The overlay must not discard the feed or reset the retained LazyListState.
        assertTrue(isHomeFeedVisible(DesktopDestination.LISTEN_NOW, nowPlayingOpen = true))
        assertFalse(isHomeFeedVisible(DesktopDestination.NEW, nowPlayingOpen = true))
        assertFalse(isHomeFeedVisible(DesktopDestination.LISTEN_NOW, settingsOpen = true, nowPlayingOpen = true))
    }
}
