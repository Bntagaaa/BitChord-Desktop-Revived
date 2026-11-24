package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopBestPartTest {
    private val song = Song("abcdefghijk", "Title", "Artist", null)

    @Test fun localTracksAndShortTracksDoNotRequestReplayData() = runBlocking {
        var calls = 0
        val lookup = DesktopBestPart(next = { calls++; JsonObject(emptyMap()) })
        assertNull(lookup.heatmap(song.copy(localUri = "file:///test.mp3"), 120_000))
        assertNull(lookup.heatmap(song.copy(videoId = "addon:opaque"), 120_000))
        assertNull(lookup.heatmap(song, 40_000))
        assertEquals(0, calls)
    }

    @Test fun networkFailureIsNotCachedButARealMissingCurveIs() = runBlocking {
        var calls = 0
        val lookup = DesktopBestPart(next = {
            if (++calls == 1) throw IOException("offline")
            JsonObject(emptyMap())
        })
        repeat(3) { assertNull(lookup.heatmap(song, 180_000)) }
        assertEquals(2, calls)
    }

    @Test fun cancellingInFlightTrackDoesNotPoisonItsCacheOrBlockSuccessor() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val requested = mutableListOf<String>()
        val lookup = DesktopBestPart(next = { id ->
            requested += id
            if (requested.size == 1) {
                firstStarted.complete(Unit)
                awaitCancellation()
            }
            JsonObject(emptyMap())
        })
        val obsolete = async { lookup.heatmap(song, 180_000) }
        firstStarted.await()
        obsolete.cancelAndJoin()
        val nextSong = song.copy(videoId = "abcdefghij2")
        assertNull(lookup.heatmap(nextSong, 180_000))
        assertNull(lookup.heatmap(song, 180_000))
        assertEquals(listOf(song.videoId, nextSong.videoId, song.videoId), requested)
    }
}
