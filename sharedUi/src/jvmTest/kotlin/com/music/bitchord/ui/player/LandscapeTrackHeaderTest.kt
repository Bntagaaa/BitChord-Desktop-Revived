package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LandscapeTrackHeaderTest {
    @Test fun desktopLikeAndMenuUseLiveCallbacksOutsideTheMovingMetadata() =
        runDesktopComposeUiTest(width = 720, height = 240) {
            mainClock.autoAdvance = false
            val song = mutableStateOf(Song("a", "First title", "First artist", null, albumId = "album-a"))
            val status = mutableStateOf(LikeStatus.INDIFFERENT)
            var likedTrack: String? = null
            var menuTrack: String? = null
            var originClicks = 0
            setContent {
                MaterialTheme {
                    Column(Modifier.width(640.dp)) {
                        LandscapeTrackHeader(
                            compact = false, song = song.value, caption = "Playing from Search",
                            onOpenCaption = { originClicks++ }, onOpenAlbum = {}, onOpenArtist = { _, _ -> },
                            active = true, reduceMotion = false,
                            actions = {
                                LandscapeTrackActions(song.value, signedIn = false, likeStatus = status.value,
                                    showRevertCue = false, onToggleLike = {
                                        likedTrack = song.value.videoId
                                        status.value = if (status.value == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE
                                    }, onOpenMenu = { menuTrack = song.value.videoId }, desktop = true)
                            },
                        )
                    }
                }
            }
            waitForIdle()
            val like = onNodeWithContentDescription("Like").getUnclippedBoundsInRoot()
            val menu = onNodeWithContentDescription("More").getUnclippedBoundsInRoot()
            assertTrue(like.right < menu.left, "Like must precede More")
            onNodeWithContentDescription("Like").performClick()
            assertEquals("a", likedTrack)
            mainClock.advanceTimeBy(240)
            onNodeWithContentDescription("Remove from Liked Music").assertExists()
            runOnIdle { song.value = Song("b", "A much longer second title", "Another artist", null) }
            mainClock.advanceTimeBy(100)
            waitForIdle()
            assertEquals(like, onNodeWithContentDescription("Remove from Liked Music").getUnclippedBoundsInRoot())
            assertEquals(menu, onNodeWithContentDescription("More").getUnclippedBoundsInRoot())
            onNodeWithContentDescription("More").performClick()
            assertEquals("b", menuTrack, "Stationary actions must address the current song")
            onNodeWithContentDescription("Remove from Liked Music").performClick()
            assertEquals("b", likedTrack)
            mainClock.advanceTimeBy(500)
            onNodeWithContentDescription("Like").assertExists()
            onNodeWithText("First title").assertDoesNotExist()
            onNodeWithText("Playing from Search").performClick()
            assertEquals(1, originClicks)
        }

    @Test fun signedOutMobileGuestDoesNotGainAnAccountLikeButLocalMusicRetainsIt() =
        runDesktopComposeUiTest(width = 200, height = 100) {
            val local = mutableStateOf(false)
            setContent {
                MaterialTheme {
                    LandscapeTrackActions(
                        Song("a", "Title", "Artist", null, localUri = if (local.value) "file:///music.wav" else null),
                        signedIn = false, likeStatus = LikeStatus.INDIFFERENT, showRevertCue = false,
                        onToggleLike = {}, onOpenMenu = {}, desktop = false,
                    )
                }
            }
            onNodeWithContentDescription("Like").assertDoesNotExist()
            runOnIdle { local.value = true }
            onNodeWithContentDescription("Like").assertExists()
            onNodeWithContentDescription("More").assertExists()
        }
}
