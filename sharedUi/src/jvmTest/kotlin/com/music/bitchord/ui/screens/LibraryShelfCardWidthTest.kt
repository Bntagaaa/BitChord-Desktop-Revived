package com.music.bitchord.ui.screens

import androidx.compose.ui.unit.dp
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryShelfCardWidthTest {
    private val playlists = YtMusicRepository.PLAYLISTS_SHELF

    @Test
    fun `only desktop Library Playlists thumbnails are compact`() {
        assertEquals(112.dp, libraryShelfCardWidth(playlists, desktop = true))
    }

    @Test
    fun `Android and other Library shelves retain their original widths`() {
        assertEquals(SHELF_CARD_WIDTH, libraryShelfCardWidth(playlists, desktop = false))
        assertEquals(SHELF_CARD_WIDTH, libraryShelfCardWidth("Albums", desktop = true))
        assertEquals(SHELF_CARD_WIDTH, libraryShelfCardWidth("Artists", desktop = false))
    }
}
