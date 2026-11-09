package com.music.bitchord.desktop

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.music.bitchord.playback.PlaybackPosition
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DesktopPlaybackPresentationTest {
    @Test fun clockTicksUpdateTheScrubberWithoutRecomposingTheApplication() =
        runDesktopComposeUiTest {
            val engine = MutableStateFlow(DesktopPlaybackState())
            val clock = PlaybackPosition()
            val appCompositions = AtomicInteger()
            setContent {
                val presentation = remember { engine.presentationStates() }
                val metadata by presentation.collectAsState(DesktopPlaybackState())
                LaunchedEffect(Unit) {
                    engine.collect { clock.report(it.positionMs, it.positionSampledAtNanos) }
                }
                SideEffect { appCompositions.incrementAndGet() }
                BasicText(metadata.isPlaying.toString(), Modifier.testTag("playing"))
                ClockProbe(clock)
            }
            waitForIdle()
            val initial = appCompositions.get()
            repeat(12) { tick ->
                runOnIdle {
                    engine.value = engine.value.copy(
                        positionMs = (tick + 1) * 250L,
                        positionSampledAtNanos = (tick + 1).toLong(),
                    )
                }
                waitForIdle()
            }
            assertEquals(initial, appCompositions.get(), "clock-only updates must not invalidate the app")
            onNodeWithTag("position").assertTextEquals("3000")
            runOnIdle { engine.value = engine.value.copy(isPlaying = true) }
            waitForIdle()
            assertEquals(initial + 1, appCompositions.get(), "transport changes must still reach the app")
            onNodeWithTag("playing").assertTextEquals("true")
        }
}

@Composable
private fun ClockProbe(clock: PlaybackPosition) {
    BasicText(clock.positionMs.toString(), Modifier.testTag("position"))
}
