package com.music.bitchord.desktop

import com.music.bitchord.data.bestpart.BestPart
import com.music.bitchord.data.bestpart.Heatmap
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

internal data class BestPartLookupResult(val heat: Heatmap?)

/** Only fetches while the expanded player needs the replay curve; lyrics come from its existing session. */
internal class DesktopBestPart(
    private val next: suspend (String) -> JsonObject = Innertube::webNext,
    private val timeMs: () -> Long = System::currentTimeMillis,
) {
    private data class Cached(val heat: Heatmap?, val fetchedAtMs: Long)
    private val cached = LinkedHashMap<String, Cached>(16, 0.75f, true)

    suspend fun heatmap(song: Song, durationMs: Long): Heatmap? {
        if (durationMs in 1 until BestPart.MIN_SONG_MS ||
            song.localUri != null || song.localPath != null || !youtubeId.matches(song.videoId)
        ) return null
        val id = song.videoId
        val now = timeMs()
        val entry = synchronized(cached) { cached[id] }
        if (entry != null) {
            val ttl = if (entry.heat == null) 7 * DAY_MS else 30 * DAY_MS
            if (now - entry.fetchedAtMs in 0 until ttl) return entry.heat
        }

        // Failures and timeouts aren't negative responses; don't cache them. Propagate a track
        // change/closing the player instead of publishing the previous song's result.
        val answer = withTimeoutOrNull(8_000) {
            try {
                withContext(Dispatchers.IO) { BestPart.parseHeatmap(next(id)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withTimeoutOrNull null
            }.let(::BestPartLookupResult)
        } ?: return entry?.heat
        synchronized(cached) {
            cached[id] = Cached(answer.heat, now)
            if (cached.size > 100) cached.remove(cached.keys.first())
        }
        return answer.heat
    }

    private companion object {
        val youtubeId = Regex("[A-Za-z0-9_-]{11}")
        const val DAY_MS = 86_400_000L
    }
}
