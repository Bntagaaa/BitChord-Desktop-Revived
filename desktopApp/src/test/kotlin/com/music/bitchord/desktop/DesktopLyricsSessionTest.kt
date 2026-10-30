package com.music.bitchord.desktop

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class DesktopLyricsSessionTest {
    private fun request(id: String = "first", duration: Long = 180000) = DesktopLyricsRequest(
        Song(id, "Song $id", "Artist", null), duration, true, false, false, emptySet(), emptyList(), null)
    private fun result(text: String) = Result.success(DesktopLyrics("fixture", listOf(LyricLine(1000, text))))
    private suspend fun await(predicate: () -> Boolean) = withTimeout(3000) { while (!predicate()) yield() }

    @Test fun `late canceled provider cannot publish the previous song`() = runBlocking {
        val requests = MutableStateFlow(request())
        val session = DesktopLyricsSession()
        val started = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val loaded = mutableListOf<String>()
        val job = launch {
            session.follow(requests, { requests.value.song?.videoId }, lookup = { song, _, _ ->
                if (song.videoId == "first") {
                    started.complete(Unit)
                    // Providers can absorb cancellation or return a Result after cleanup.
                    withContext(NonCancellable) { releaseOld.await() }
                }
                result(song.videoId)
            }, onLoaded = { id, _ -> loaded += id })
        }
        try {
            started.await()
            requests.value = request("second")
            releaseOld.complete(Unit)
            await { session.state.trackId == "second" && session.state.result != null }
            assertEquals(listOf("second"), loaded)
            assertEquals("second", session.state.result!!.lines.single().text)
            assertFalse(session.state.loading)
        } finally { releaseOld.complete(Unit); job.cancelAndJoin() }
    }

    @Test fun `duration waits for the selected song then stays fixed until the next song`() = runBlocking {
        val requests = MutableStateFlow(request(duration = 0))
        val session = DesktopLyricsSession()
        val lengths = mutableListOf<Pair<String, Long>>()
        val job = launch {
            session.follow(requests, { requests.value.song?.videoId }, lookup = { song, length, _ ->
                lengths += song.videoId to length
                result(song.videoId)
            })
        }
        try {
            await { session.state.loading }
            assertTrue(lengths.isEmpty())
            requests.value = request(duration = 180000)
            await { session.state.result != null }
            requests.value = request(duration = 185000)
            // A source/settings update after the rendition change proves the duration latch
            // survives a genuine refresh and the extra duration did not trigger a lookup.
            requests.value = requests.value.copy(enabled = false)
            await { session.state.error != null }
            assertEquals(listOf("first" to 180000L), lengths)
            requests.value = requests.value.copy(enabled = true)
            await { lengths.size == 2 }
            assertEquals("first" to 180000L, lengths.last())
            requests.value = request("second", 220000)
            await { session.state.trackId == "second" && session.state.result != null }
            assertEquals("second" to 220000L, lengths.last())
        } finally { job.cancelAndJoin() }
    }

    @Test fun `video disable provider override and errors replace old lyrics explicitly`() = runBlocking {
        val requests = MutableStateFlow(request().copy(song = request().song!!.copy(isVideo = true), videoBeta = true))
        val session = DesktopLyricsSession()
        val providers = mutableListOf<String?>()
        val job = launch {
            session.follow(requests, { requests.value.song?.videoId }, lookup = { _, _, only ->
                providers += only
                if (only == "missing") Result.failure(IllegalStateException("No words found")) else result("New words")
            })
        }
        try {
            await { session.state.error != null }
            assertTrue(providers.isEmpty())
            requests.value = requests.value.copy(videoBeta = false, only = "chosen")
            await { session.state.result != null }
            assertEquals(listOf<String?>("chosen"), providers)
            requests.value = requests.value.copy(only = "missing")
            await { session.state.error == "No words found" }
            assertNull(session.state.result)
            assertFalse(session.state.loading)
            requests.value = requests.value.copy(song = null)
            await { session.state.trackId == null }
            assertEquals(DesktopLyricsSnapshot(), session.state)
        } finally { job.cancelAndJoin() }
    }
}
