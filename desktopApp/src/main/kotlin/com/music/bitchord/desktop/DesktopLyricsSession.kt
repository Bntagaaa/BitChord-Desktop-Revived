package com.music.bitchord.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class DesktopLyricsRequest(
    val song: Song?,
    /** Only a duration reported for this selected song; zero until the engine adopts it. */
    val durationMs: Long,
    val enabled: Boolean,
    val videoBeta: Boolean,
    val prioritizeSyllables: Boolean,
    val sources: Set<String>,
    val order: List<String>,
    val only: String?,
)

internal data class DesktopLyricsSnapshot(
    val trackId: String? = null,
    val result: DesktopLyrics? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** The one app-wide lookup. Collection continues when the main Window's frame clock is paused. */
internal class DesktopLyricsSession {
    var state by mutableStateOf(DesktopLyricsSnapshot())
        private set

    suspend fun follow(
        requests: Flow<DesktopLyricsRequest>,
        currentTrack: () -> String?,
        lookup: suspend (Song, Long, String?) -> Result<DesktopLyrics> = { song, length, only ->
            DesktopLyricsClient.lookup(song, length, only)
        },
        onLoaded: (String, DesktopLyrics) -> Unit = { _, _ -> },
    ) {
        var durationTrack: String? = null
        var duration = 0L
        requests.map { request ->
            if (durationTrack != request.song?.videoId) {
                durationTrack = request.song?.videoId
                duration = 0L
            }
            // A rendition upgrade must not trigger another lyrics lookup mid-song.
            if (duration <= 0 && request.durationMs > 0) duration = request.durationMs
            request.copy(durationMs = duration.takeIf { it > 0 } ?: request.song?.durationMillis() ?: 0L)
        }.distinctUntilChanged().collectLatest { request ->
            val song = request.song
            state = DesktopLyricsSnapshot(trackId = song?.videoId)
            if (song == null) return@collectLatest
            if (!request.enabled || (song.isVideo && request.videoBeta)) {
                state = state.copy(error = "Synced lyrics are switched off")
                return@collectLatest
            }
            state = state.copy(loading = true)
            // Wait for a useful duration; a zero-length database match can select a wrong song.
            if (request.durationMs <= 0) return@collectLatest
            val result = lookup(song, request.durationMs, request.only)
            currentCoroutineContext().ensureActive()
            if (currentTrack() != song.videoId) return@collectLatest
            result.fold(
                onSuccess = { lyrics ->
                    state = DesktopLyricsSnapshot(song.videoId, result = lyrics)
                    onLoaded(song.videoId, lyrics)
                },
                onFailure = { state = DesktopLyricsSnapshot(song.videoId, error = it.message ?: "Lyrics unavailable") },
            )
        }
    }
}
