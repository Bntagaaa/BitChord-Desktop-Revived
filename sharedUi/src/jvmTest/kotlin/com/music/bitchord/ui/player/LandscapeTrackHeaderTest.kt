package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * This component test needs only the platform's visual chrome flag. Any other
 * PlayerHost interaction is unexpected and should fail rather than silently
 * making the test behave differently from the real player.
 */
private fun withLandscapeTestHost(block: () -> Unit) {
    val host = Proxy.newProxyInstance(
        PlayerHost::class.java.classLoader,
        arrayOf(PlayerHost::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getMobileTextChrome" -> false
            else -> error("Unexpected PlayerHost access in landscape test: ${method.name}")
        }
    } as PlayerHost
    PlayerPlatform.withHostForTest(host, block)
}

@OptIn(ExperimentalTestApi::class)
class LandscapeTrackHeaderTest {
    @Test fun desktopLikeAndMenuUseLiveCallbacksOutsideTheMovingMetadata() =
        withLandscapeTestHost { runDesktopComposeUiTest(width = 720, height = 240) {
            mainClock.autoAdvance = false
            val song = mutableStateOf(Song("a", "First title", "First artist", null, albumId = "album-a"))
            val status = mutableStateOf(LikeStatus.INDIFFERENT)
            var likedTrack: String? = null
            var menuTrack: String? = null
            var openedAlbum: String? = null
            var openedArtist: Pair<String?, String>? = null
            var originClicks = 0
            setContent {
                val currentSong = song.value
                MaterialTheme {
                    Column(Modifier.width(640.dp)) {
                        LandscapeTrackHeader(
                            compact = false, song = currentSong, caption = "Playing from Search",
                            onOpenCaption = { originClicks++ }, onOpenAlbum = { openedAlbum = it },
                            onOpenArtist = { id, name -> openedArtist = id to name },
                            active = true, reduceMotion = false,
                            actions = {
                                LandscapeTrackActions(currentSong, signedIn = false, likeStatus = status.value,
                                    showRevertCue = false, onToggleLike = {
                                        likedTrack = currentSong.videoId
                                        status.value = if (status.value == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE
                                    }, onOpenMenu = { menuTrack = currentSong.videoId }, desktop = true)
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
            runOnIdle { song.value = Song("b", "A much longer second title", "Another artist", null,
                albumId = "album-b", artistId = "artist-b") }
            mainClock.advanceTimeBy(100)
            waitForIdle()
            assertEquals(like, onNodeWithContentDescription("Remove from Liked Music").getUnclippedBoundsInRoot())
            assertEquals(menu, onNodeWithContentDescription("More").getUnclippedBoundsInRoot())
            onNodeWithText("First title").assertDoesNotExist()
            onNodeWithContentDescription("More").performClick()
            assertEquals("b", menuTrack, "Stationary actions must address the current song")
            onNodeWithContentDescription("Remove from Liked Music").performClick()
            assertEquals("b", likedTrack)
            mainClock.advanceTimeBy(500)
            onNodeWithContentDescription("Like").assertExists()
            onNodeWithText("First title").assertDoesNotExist()
            onNodeWithText("Playing from Search").performClick()
            assertEquals(1, originClicks)
            onNodeWithText("A much longer second title").performClick()
            assertEquals("album-b", openedAlbum)
            onNodeWithText("Another artist").performClick()
            assertEquals("artist-b" to "Another artist", openedArtist)
        } }

    @Test fun signedOutMobileGuestDoesNotGainAnAccountLikeButLocalMusicRetainsIt() =
        withLandscapeTestHost { runDesktopComposeUiTest(width = 200, height = 100) {
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
        } }
}
