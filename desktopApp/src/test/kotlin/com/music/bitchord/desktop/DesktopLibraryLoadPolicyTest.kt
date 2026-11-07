package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLibraryLoadPolicyTest {
    @Test
    fun `startup fetches the sidebar playlist shelf without opening Library`() {
        assertTrue(DesktopLibraryLoadPolicy.shouldFetch(
            accountChanged = false,
            hasLoadedLibrary = false,
            playlistDialogOpen = false,
            libraryVisible = false,
        ))
    }

    @Test
    fun `a loaded library is reused when navigating between pages`() {
        assertFalse(DesktopLibraryLoadPolicy.shouldFetch(false, true, false, false))
        assertFalse(DesktopLibraryLoadPolicy.shouldFetch(false, true, false, true))
    }

    @Test
    fun `account changes and missing library data request another fetch`() {
        assertTrue(DesktopLibraryLoadPolicy.shouldFetch(true, true, false, false))
        assertTrue(DesktopLibraryLoadPolicy.shouldFetch(false, false, false, true))
    }

    @Test
    fun `opening the playlist picker from Home refreshes account playlists`() {
        assertTrue(DesktopLibraryLoadPolicy.shouldFetch(false, true, true, false))
        assertFalse(DesktopLibraryLoadPolicy.shouldFetch(false, true, true, true))
    }
}
