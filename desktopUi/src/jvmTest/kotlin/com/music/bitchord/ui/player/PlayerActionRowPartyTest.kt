package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.listentogether.PartyMember
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PlayerActionRowPartyTest {
    @Test
    fun activeJamRestoresCapsuleAndTracksMemberCount() {
        val party = MutableStateFlow(PartyUi())
        val cast = MutableStateFlow(CastUi())
        val watch = MutableStateFlow(WatchOutputUi())
        val host = Proxy.newProxyInstance(
            PlayerHost::class.java.classLoader,
            arrayOf(PlayerHost::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getMobileTextChrome" -> true
                "getParty" -> party
                "getCastState" -> cast
                "getWatchOutput" -> watch
                "rememberAudioOutputs" -> emptyList<AudioOutputDevice>()
                else -> error("Unexpected host access: ${method.name}")
            }
        } as PlayerHost
        PlayerPlatform.withHostForTest(host) {
            runDesktopComposeUiTest(width = 600, height = 200) {
                var outputClicks = 0
                var memberClicks = 0
                setContent {
                    MaterialTheme {
                        Box(Modifier.width(440.dp)) {
                            PlayerActionRow(
                                lyricsOpen = false,
                                queueOpen = false,
                                shuffleEnabled = false,
                                repeatMode = 0,
                                autoplayEnabled = false,
                                onToggleLyrics = {},
                                onToggleQueue = {},
                                onToggleShuffle = {},
                                onCycleRepeat = {},
                                onToggleAutoplay = {},
                                onOpenOutput = { outputClicks++ },
                                onListenTogether = { error("Must open members instead") },
                                onOpenListenTogetherMembers = { memberClicks++ },
                            )
                        }
                    }
                }
                waitForIdle()
                onNodeWithContentDescription("Audio output").assertIsDisplayed()
                onNodeWithContentDescription("Listening together · 2").assertDoesNotExist()

                val members = listOf(PartyMember("host", isHost = true), PartyMember("guest"))
                runOnIdle { party.value = PartyUi(inParty = true, members = members) }
                waitForIdle()
                onNodeWithContentDescription("Listening together · 2").assertIsDisplayed()
                onNodeWithText("2").assertIsDisplayed()
                onNodeWithContentDescription("Listening together · 2").performClick()
                onNodeWithContentDescription("Audio output").performClick()
                runOnIdle {
                    assertEquals(1, memberClicks)
                    assertEquals(1, outputClicks)
                    party.value = party.value.copy(members = members + PartyMember("new"))
                }
                waitForIdle()
                onNodeWithContentDescription("Listening together · 3").assertIsDisplayed()
                onNodeWithText("3").assertIsDisplayed()

                runOnIdle { party.value = PartyUi() }
                waitForIdle()
                onNodeWithContentDescription("Listening together · 3").assertDoesNotExist()
                onNodeWithContentDescription("Audio output").assertIsDisplayed()
            }
        }
    }
}
