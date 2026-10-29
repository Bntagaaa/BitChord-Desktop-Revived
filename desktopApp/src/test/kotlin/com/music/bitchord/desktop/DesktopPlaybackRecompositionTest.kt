package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.music.bitchord.playback.PlaybackPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopPlaybackRecompositionTest {
    @Test fun movingProgressRepaintsWithoutRecomposingTheDesktopRoot() = runDesktopComposeUiTest(width=400,height=300) {
        val source=MutableStateFlow(DesktopPlaybackState(positionMs=20_000,isPlaying=true,durationMs=200_000))
        val position=PlaybackPosition()
        val roots=AtomicInteger()
        val draws=AtomicInteger()
        setContent {
            val state by remember { source.distinctUntilChanged(::samePlaybackUiState) }.collectAsState(source.value)
            SideEffect { roots.incrementAndGet() }
            LaunchedEffect(Unit) { source.collect { position.report(it.positionMs,it.positionSampledAtNanos) } }
            Text(if(state.isPlaying) "Playing" else "Paused")
            Box(Modifier.size(100.dp).drawBehind { draws.incrementAndGet(); drawRect(Color.Red.copy(alpha=(position.positionMs / 200_000f).coerceIn(0f,1f))) })
        }
        waitForIdle()
        val initialRoots=roots.get();val initialDraws=draws.get()
        repeat(30) { tick ->
            runOnIdle { source.value=source.value.copy(positionMs=21_000L+tick*250,positionSampledAtNanos=tick.toLong()+1) }
            waitForIdle()
        }
        assertEquals(initialRoots,roots.get())
        assertTrue(draws.get()>initialDraws)
        runOnIdle { source.value=source.value.copy(isPlaying=false) }
        onNodeWithText("Paused").assertExists()
        assertTrue(roots.get()>initialRoots)
    }
}
