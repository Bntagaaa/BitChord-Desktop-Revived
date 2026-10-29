package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopPlaybackUiTest {
    @Test fun thousandAudioClockTicksDoNotInvalidateTheRootUi() = runBlocking {
        val samples=(0 until 1000).map { DesktopPlaybackState(song=Song("id","Song","Artist",null),isPlaying=true,
            positionMs=it*250L,positionSampledAtNanos=it*250_000_000L) }
        val visible=samples.asFlow().distinctUntilChanged(::samePlaybackUiState).toList()
        assertEquals(2,visible.size) // initial sample and Previous-button threshold, not 1000 root updates
        assertEquals(10_250L,visible.last().positionMs)
    }
    @Test fun everyNonClockChangeAndSeekStillReachesTheRoot() {
        val sample=DesktopPlaybackState(positionMs=30_000,isPlaying=true)
        assertTrue(samePlaybackUiState(sample,sample.copy(positionMs=30_250,positionSampledAtNanos=10)))
        for (change in listOf(sample.copy(isPlaying=false),sample.copy(isLoading=true),sample.copy(volume=.5f),
            sample.copy(seeks=1),sample.copy(awaitingAudio=true),sample.copy(durationMs=100_000),
            sample.copy(error="failure"),sample.copy(mixing=true),sample.copy(searchingBetter=true),sample.copy(streamSourceId="new"))) {
            assertFalse(samePlaybackUiState(sample,change), "$change must reach the UI")
        }
        assertFalse(samePlaybackUiState(sample,sample.copy(positionMs=0)))
    }
}
