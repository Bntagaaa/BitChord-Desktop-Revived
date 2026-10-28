package com.music.bitchord.desktop

import java.awt.event.KeyEvent
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopQuickSearchTest {
    @Test fun quickSearchIncludesSongsArtistsAlbumsAndPlaylists() {
        val track = SearchResult.Track(Song("test-id", "Song", "Artist", null))
        val artist = SearchResult.Browse(BrowseItem("artist-id", "Artist", "2M listeners", null, BrowseType.ARTIST))
        val album = SearchResult.Browse(BrowseItem("album-id", "Album", "", null, BrowseType.ALBUM))
        val playlist = SearchResult.Browse(BrowseItem("playlist-id", "Mix", "", null, BrowseType.PLAYLIST))
        val results = quickSearchEntries(listOf(track, artist, album, playlist))
        assertEquals(listOf(track, artist, album, playlist), results)
        assertEquals("browse:artist-id", quickSearchEntryKey(artist))
        assertEquals("browse:album-id", quickSearchEntryKey(album))
        assertEquals("browse:playlist-id", quickSearchEntryKey(playlist))
        assertEquals("track:test-id", quickSearchEntryKey(track))
    }

    @Test fun playlistRemainsVisibleWhenFirstFiveResultsAreSongs() {
        val tracks = (1..7).map { SearchResult.Track(Song("id$it", "Song $it", "Artist", null)) }
        val playlist = SearchResult.Browse(BrowseItem("pl1", "My Mix", "", null, BrowseType.PLAYLIST))
        val displayed = quickSearchEntries(tracks + playlist)
        assertEquals(5, displayed.size)
        assertEquals(tracks.take(4) + playlist, displayed)
    }

    private fun state() = DesktopQuickSearchState<String> { it }

    private fun ready(vararg items: String): DesktopQuickSearchState<String> = state().also {
        it.edit("query")
        it.complete(it.state.value.request!!, items.toList())
    }

    @Test fun blankQueryDoesNotCreateRequest() {
        val s = state()
        assertNull(s.state.value.request)
        s.edit("   ")
        assertNull(s.state.value.request)
        assertFalse(s.state.value.loading)
        assertNull(s.selected())
    }

    @Test fun queryKeepsTextButTrimsRequest() {
        val s = state()
        s.edit("  havana ")
        assertEquals("  havana ", s.state.value.query)
        assertEquals("havana", s.state.value.request!!.query)
        assertTrue(s.state.value.loading)
    }

    @Test fun firstResultIsSelectedWithoutPlayingAnything() {
        val s = ready("first", "second")
        assertEquals(0, s.state.value.selectedIndex)
        assertEquals("first", s.selected())
    }

    @Test fun resultsAreDeduplicatedAndLimited() {
        val s = state()
        s.edit("q")
        s.complete(s.state.value.request!!, listOf("", " ", "a", "a") + (1..20).map { "song$it" })
        assertEquals(5, s.state.value.items.size)
        assertEquals("a", s.selected())
        assertEquals(5, s.state.value.items.distinct().size)
    }

    @Test fun editingImmediatelyInvalidatesOldSelection() {
        val s = ready("old")
        s.edit("new query")
        assertTrue(s.state.value.items.isEmpty())
        assertNull(s.selected())
        assertFalse(s.canPlay("old"))
    }

    @Test fun staleResponseCannotReplaceCurrentQuery() {
        val s = state()
        s.edit("a")
        val old = s.state.value.request!!
        s.edit("b")
        assertFalse(s.complete(old, listOf("old result")))
        assertEquals("b", s.state.value.query)
        assertTrue(s.state.value.loading)
    }

    @Test fun staleFailureCannotClearNewResults() {
        val s = state()
        s.edit("a")
        val old = s.state.value.request!!
        s.edit("b")
        s.complete(s.state.value.request!!, listOf("new"))
        assertFalse(s.fail(old, "old error"))
        assertEquals("new", s.selected())
        assertNull(s.state.value.error)
    }

    @Test fun resetInvalidatesRequestsEvenWhenSameQueryIsReopened() {
        val s = state()
        s.edit("same")
        val old = s.state.value.request!!
        s.reset()
        s.edit("same")
        assertNotEquals(old, s.state.value.request)
        assertFalse(s.complete(old, listOf("stale")))
    }

    @Test fun clearingReturnsToCompactEmptyState() {
        val s = ready("song")
        s.edit("")
        assertNull(s.state.value.request)
        assertFalse(s.state.value.loading)
        assertTrue(s.state.value.items.isEmpty())
        assertNull(s.selected())
    }

    @Test fun movementClampsAtBothEnds() {
        val s = ready("a", "b", "c")
        s.move(-1)
        assertEquals("a", s.selected())
        s.move(1)
        assertEquals("b", s.selected())
        s.move(99)
        assertTrue(s.isSearchAllSelected())
        s.move(1)
        assertTrue(s.isSearchAllSelected())
        s.move(-1)
        assertEquals("c", s.selected())
    }

    @Test fun emptyListNavigationIsSafe() {
        val s = ready()
        s.move(1)
        s.move(-1)
        assertEquals(0, s.state.value.selectedIndex)
        assertTrue(s.isSearchAllSelected())
        assertNull(s.selected())
    }

    @Test fun keyboardCanSelectSearchAllFooter() {
        val s = ready("song", "artist")
        s.move(2)
        assertTrue(s.isSearchAllSelected())
        assertNull(s.selected())
        s.move(1)
        assertTrue(s.isSearchAllSelected())
        s.move(-1)
        assertEquals("artist", s.selected())
    }

    @Test fun emptySearchCanStillSelectSearchAll() {
        val s = ready()
        assertTrue(s.isSearchAllSelected())
        assertEquals("query", s.state.value.query)
    }

    @Test fun hoverOnFooterDoesNotTriggerKeyboardScroll() {
        val s = ready("song")
        val before = s.state.value.scrollRevision
        s.hoverSearchAll()
        assertTrue(s.isSearchAllSelected())
        assertEquals(before, s.state.value.scrollRevision)
    }

    @Test fun mouseSelectionDoesNotCauseKeyboardScrollFeedback() {
        val s = ready("a", "b")
        val before = s.state.value.scrollRevision
        s.hover(1)
        assertEquals("b", s.selected())
        assertEquals(before, s.state.value.scrollRevision)
        s.move(-1)
        assertTrue(s.state.value.scrollRevision > before)
    }

    @Test fun invalidMouseIndexIsIgnored() {
        val s = ready("a")
        s.hover(100)
        assertEquals("a", s.selected())
    }

    @Test fun errorsStopLoadingAndRetryMakesFreshRequest() {
        val s = state()
        s.edit("q")
        val old = s.state.value.request!!
        assertTrue(s.fail(old, "Network unavailable"))
        assertFalse(s.state.value.loading)
        assertEquals("Network unavailable", s.state.value.error)
        assertNull(s.selected())
        s.retry()
        assertTrue(s.state.value.loading)
        assertNull(s.state.value.error)
        assertNotEquals(old, s.state.value.request)
    }

    @Test fun playbackErrorKeepsQueryAndResults() {
        val s = ready("song")
        s.playbackError("Host only")
        assertEquals("query", s.state.value.query)
        assertEquals("song", s.selected())
        assertEquals("Host only", s.state.value.error)
    }

    @Test fun enterPlaysAndClosesOnlyOnRelease() {
        val keys = DesktopQuickSearchKeys()
        assertEquals(DesktopQuickSearchKeyAction.CONSUME, keys.handle(KeyEvent.VK_ENTER, true, true))
        assertEquals(DesktopQuickSearchKeyAction.PLAY, keys.handle(KeyEvent.VK_ENTER, false, true))
    }

    @Test fun shiftEnterPlaysWithoutClosingOnRelease() {
        val keys = DesktopQuickSearchKeys()
        repeat(5) {
            assertEquals(DesktopQuickSearchKeyAction.CONSUME, keys.handle(KeyEvent.VK_ENTER, true, true, shift = true))
        }
        assertEquals(DesktopQuickSearchKeyAction.PLAY_KEEP_OPEN, keys.handle(KeyEvent.VK_ENTER, false, true))
        assertEquals(DesktopQuickSearchKeyAction.PASS, keys.handle(KeyEvent.VK_ENTER, false, true))
    }

    @Test fun addingShiftToHeldEnterDoesNotTurnItIntoPlay() {
        val keys = DesktopQuickSearchKeys()
        keys.handle(KeyEvent.VK_ENTER, true, true)
        keys.handle(KeyEvent.VK_ENTER, true, true, shift = true)
        assertEquals(DesktopQuickSearchKeyAction.PLAY, keys.handle(KeyEvent.VK_ENTER, false, true, shift = true))
    }

    @Test fun otherEnterModifiersNeverPlay() {
        val keys = DesktopQuickSearchKeys()
        keys.handle(KeyEvent.VK_ENTER, true, true, ctrl = true, shift = true)
        assertEquals(DesktopQuickSearchKeyAction.CONSUME, keys.handle(KeyEvent.VK_ENTER, false, true))
        keys.handle(KeyEvent.VK_ENTER, true, true, alt = true, shift = true)
        assertEquals(DesktopQuickSearchKeyAction.CONSUME, keys.handle(KeyEvent.VK_ENTER, false, true))
    }

    @Test fun escapeClosesOnlyAtRelease() {
        val keys = DesktopQuickSearchKeys()
        assertEquals(DesktopQuickSearchKeyAction.CONSUME, keys.handle(KeyEvent.VK_ESCAPE, true, true))
        assertEquals(DesktopQuickSearchKeyAction.DISMISS, keys.handle(KeyEvent.VK_ESCAPE, false, true))
        assertEquals(DesktopQuickSearchKeyAction.PASS, keys.handle(KeyEvent.VK_ESCAPE, false, false))
    }

    @Test fun mouseDismissWhileKeyHeldConsumesOrphanedRelease() {
        val keys = DesktopQuickSearchKeys()
        keys.handle(KeyEvent.VK_ENTER, true, true, shift = true)
        assertEquals(DesktopQuickSearchKeyAction.CONSUME, keys.handle(KeyEvent.VK_ENTER, false, false))
        keys.handle(KeyEvent.VK_ESCAPE, true, true)
        assertEquals(DesktopQuickSearchKeyAction.CONSUME, keys.handle(KeyEvent.VK_ESCAPE, false, false))
    }

    @Test fun arrowsRepeatForNavigationAndConsumeKeyUp() {
        val keys = DesktopQuickSearchKeys()
        repeat(3) { assertEquals(DesktopQuickSearchKeyAction.NEXT, keys.handle(KeyEvent.VK_DOWN, true, true)) }
        assertEquals(DesktopQuickSearchKeyAction.CONSUME, keys.handle(KeyEvent.VK_DOWN, false, true))
        assertEquals(DesktopQuickSearchKeyAction.PREVIOUS, keys.handle(KeyEvent.VK_UP, true, true))
    }

    @Test fun editorSpaceAndSelectionAndWordNavigationRemainAvailable() {
        val keys = DesktopQuickSearchKeys()
        assertEquals(DesktopQuickSearchKeyAction.PASS, keys.handle(KeyEvent.VK_SPACE, true, true, globalShortcut = true))
        assertEquals(DesktopQuickSearchKeyAction.PASS, keys.handle(KeyEvent.VK_LEFT, true, true, shift = true, globalShortcut = true))
        assertEquals(DesktopQuickSearchKeyAction.PASS, keys.handle(KeyEvent.VK_RIGHT, true, true, ctrl = true, globalShortcut = true))
        assertEquals(DesktopQuickSearchKeyAction.PASS, keys.handle(KeyEvent.VK_C, true, true, ctrl = true))
    }

    @Test fun controlKAndTabRefocusRatherThanStackingModals() {
        val keys = DesktopQuickSearchKeys()
        assertEquals(DesktopQuickSearchKeyAction.FOCUS_QUERY, keys.handle(KeyEvent.VK_K, true, true, ctrl = true))
        assertEquals(DesktopQuickSearchKeyAction.CONSUME, keys.handle(KeyEvent.VK_K, true, true, ctrl = true))
        assertEquals(DesktopQuickSearchKeyAction.CONSUME, keys.handle(KeyEvent.VK_K, false, true, ctrl = true))
        assertEquals(DesktopQuickSearchKeyAction.FOCUS_QUERY, keys.handle(KeyEvent.VK_TAB, true, true))
    }

    @Test fun otherGlobalShortcutsAreSuppressedOnlyWhileOverlayOpen() {
        val keys = DesktopQuickSearchKeys()
        assertEquals(DesktopQuickSearchKeyAction.CONSUME, keys.handle(KeyEvent.VK_SLASH, true, true, ctrl = true, globalShortcut = true))
        keys.handle(KeyEvent.VK_SLASH, false, true)
        assertEquals(DesktopQuickSearchKeyAction.PASS, keys.handle(KeyEvent.VK_SLASH, true, false, ctrl = true, globalShortcut = true))
    }

    @Test fun windowFocusResetCancelsPendingPlay() {
        val keys = DesktopQuickSearchKeys()
        keys.handle(KeyEvent.VK_ENTER, true, true, shift = true)
        keys.reset()
        assertEquals(DesktopQuickSearchKeyAction.PASS, keys.handle(KeyEvent.VK_ENTER, false, true))
    }
}
