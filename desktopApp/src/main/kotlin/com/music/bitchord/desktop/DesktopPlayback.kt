package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.innertube.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

internal data class DesktopStream(
    val url: String,
    val format: DesktopStreamFormat = DesktopStreamFormat(),
    val headers: Map<String, String> = emptyMap(),
    /** The configured source that produced this stream, for media-failure fallback. */
    val sourceId: String? = null,
    /** Whether the source says this is the immersive mix rather than a stereo one. */
    val isDolbyAtmos: Boolean = false,
    /** Explicit add-on transport (`hls` or `dash`) for extensionless manifest URLs. */
    val transport: String? = null,
    /** Catalogue runtime, retained for recording-match and diagnostics parity with Android. */
    val durationSec: Int? = null,
    /**
     * Whether this server will only hand the file over a window at a time — see
     * [DesktopRangeStream].
     */
    val windowedReads: Boolean = false,
)

/**
 * A YouTube track's stream, resolved the way the phone resolves it.
 *
 * The phone's own [StreamResolver], shared from `:shared`: InnerTubeX's
 * live-benchmarked client catalog and cipher tiers first, each minted URL
 * probed before it is trusted, NewPipe as the one fallback, and a track that
 * cannot play remembered as such. The media fetch wears the headers of
 * whichever client minted the URL.
 */
internal object DesktopStreamClient {
    suspend fun resolve(song: Song, maxKbps: Int = Int.MAX_VALUE): Result<DesktopStream> = runCatching {
        val url = withContext(Dispatchers.IO) {
            ceiling.set(maxKbps)
            try {
                StreamResolver.resolve(song.videoId)
            } finally {
                ceiling.remove()
            }
        }
        DesktopTrackLog.log("  youtube: resolved '${song.title}' through InnerTubeX")
        DesktopStream(
            url = url,
            format = DesktopStreamFormat(
                codec = mimeOf(url),
                kbps = NerdStats.pickedBitrateKbps(song.videoId),
            ),
            headers = StreamResolver.mediaHeadersFor(url),
            sourceId = "youtube",
            // Google's media servers answer 403 to a request for a whole file and 206 to one for
            // a window of it, and FFmpeg's own HTTP client asks for the whole thing.
            windowedReads = true,
        )
    }

    suspend fun resolveUrl(song: Song): Result<String> = resolve(song).map(DesktopStream::url)

    /**
     * The ceiling a resolve on this thread was asked for, read by
     * [StreamResolver.maxKbps] — the phone reads its own setting there instead.
     */
    internal val ceiling = ThreadLocal<Int>()

    /** `mime=audio/webm` off a googlevideo URL: the container, which is what FFmpeg is told. */
    private fun mimeOf(url: String): String? =
        runCatching { java.net.URI(url).rawQuery }.getOrNull()
            ?.split('&')
            ?.firstOrNull { it.startsWith("mime=") }
            ?.substringAfter('=')
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?.substringAfterLast('/')
            ?.lowercase()
}
