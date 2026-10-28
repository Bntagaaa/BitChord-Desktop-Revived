package com.music.bitchord.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.screens.VideoShelfCard
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DesktopVideoCardsTest {
    @Test fun homeAndArtistCardsStayWidescreenWhileBetaIsOffAndClicksKeepIdentity() = runDesktopComposeUiTest(width = 1200, height = 700) {
        val previous = DesktopVideoBeta.enabled.value
        DesktopVideoBeta.enabled.value = false
        val video = ShelfItem("Music video", "Adrian Khalif · 70K views", null, "video", null, isVideo = true)
        val audio = video.copy(title = "Audio", videoId = "audio", isVideo = false)
        var clicked: Song? = null
        try {
            setContent {
                MaterialTheme {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        DesktopShelfCard(video) { clicked = it.toSong() }
                        DesktopShelfCard(audio) { clicked = it.toSong() }
                        VideoShelfCard(video, onClick = { clicked = video.toSong() })
                    }
                }
            }
            val artistVideo = onNodeWithTag("shelf-art-video", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val artistAudio = onNodeWithTag("shelf-art-audio", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val homeVideo = onNodeWithTag("home-video-art-video", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(16f / 9, artistVideo.width / artistVideo.height, .02f)
            assertEquals(16f / 9, homeVideo.width / homeVideo.height, .02f)
            assertEquals(1f, artistAudio.width / artistAudio.height, .01f)
            onNodeWithTag("shelf-art-video", useUnmergedTree = true).performMouseInput { click() }
            runOnIdle { assertTrue(assertNotNull(clicked).isVideo); assertTrue(assertNotNull(clicked).isVideoOrigin) }
            onNodeWithTag("home-video-art-video", useUnmergedTree = true).performMouseInput { click() }
            runOnIdle { assertTrue(assertNotNull(clicked).isVideo); assertEquals("video", clicked?.videoId) }
        } finally { DesktopVideoBeta.enabled.value = previous }
    }
}
