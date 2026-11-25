package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Downloads page's tab order and the tab it greets you with: a page that
 * carries downloaded collections opens on them, and a page that does not
 * (Local Music) keeps its three tabs and its songs first.
 */
class DesktopDownloadsTabsTest {

    @Test
    fun `a page with downloaded collections wears four tabs with playlists between songs and artists`() {
        assertEquals(
            listOf(DownloadsTab.SONGS, DownloadsTab.PLAYLISTS, DownloadsTab.ARTISTS, DownloadsTab.ALBUMS),
            downloadsTabs(withPlaylists = true),
        )
    }

    @Test
    fun `local music keeps its three tabs`() {
        assertEquals(
            listOf(DownloadsTab.SONGS, DownloadsTab.ARTISTS, DownloadsTab.ALBUMS),
            downloadsTabs(withPlaylists = false),
        )
    }

    @Test
    fun `the downloads page opens on the playlists tab`() {
        assertEquals(1, downloadsDefaultTabIndex(downloadsTabs(withPlaylists = true)))
    }

    @Test
    fun `a page without playlists still opens on its songs`() {
        assertEquals(0, downloadsDefaultTabIndex(downloadsTabs(withPlaylists = false)))
    }
}
