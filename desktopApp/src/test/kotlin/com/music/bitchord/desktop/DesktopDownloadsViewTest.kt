package com.music.bitchord.desktop

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.music.bitchord.data.model.Song
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopDownloadsViewTest {
    @Test
    fun `playlist tiles switch to rows and back from the downloads toolbar`() {
        val prefs = Preferences.userRoot().node("com.music.bitchord.tests/downloads-view-${UUID.randomUUID()}")
        try {
            val persistence = DesktopPersistence(prefs)
            persistence.saveString("downloaded_view", "LIST")
            val song = Song("track", "Track", "Artist", null)
            val first = DesktopPlaylistDownload("VLfirst", "First playlist", "", null, true, listOf(song), 0, 0)
            val second = first.copy(id = "VLsecond", title = "Second playlist")
            var opened: DesktopPlaylistDownload? = null

            runDesktopComposeUiTest(width = 800, height = 600) {
                setContent {
                    MaterialTheme {
                        DesktopDownloadsPage(
                            persistence = persistence,
                            downloads = emptyList(),
                            playlistRecords = listOf(first, second),
                            downloadedIds = setOf(song.videoId),
                            downloadInProgress = emptySet(),
                            playlistStaleIds = emptySet(),
                            onSongClick = {},
                            onOpenPlaylist = { opened = it },
                            onUpdatePlaylist = {},
                            onRemovePlaylist = {},
                            contentPadding = PaddingValues(),
                        )
                    }
                }

                onNodeWithText("Playlists").assertIsSelected()
                val firstPlaylist = onNodeWithText(first.title)
                val secondPlaylist = onNodeWithText(second.title)
                val changeView = onNodeWithContentDescription("Change view")
                assertTrue(firstPlaylist.fetchSemanticsNode().boundsInRoot.right <
                    secondPlaylist.fetchSemanticsNode().boundsInRoot.left)

                changeView.performClick()
                assertTrue(firstPlaylist.fetchSemanticsNode().boundsInRoot.bottom <
                    secondPlaylist.fetchSemanticsNode().boundsInRoot.top)
                secondPlaylist.performClick()
                runOnIdle { assertEquals(second, opened) }
                assertEquals("LIST", persistence.string("downloaded_playlists_view", ""))

                changeView.performClick()
                assertTrue(firstPlaylist.fetchSemanticsNode().boundsInRoot.right <
                    secondPlaylist.fetchSemanticsNode().boundsInRoot.left)
                assertEquals("GRID", persistence.string("downloaded_playlists_view", ""))
                assertEquals("LIST", persistence.string("downloaded_view", ""))
            }
        } finally {
            prefs.removeNode()
        }
    }
}
