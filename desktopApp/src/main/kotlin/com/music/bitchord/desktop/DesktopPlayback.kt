package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException

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
    /** Whether this server requires byte-range windows; see [DesktopRangeStream]. */
    val windowedReads: Boolean = false,
)

/** yt-dlp is the sole desktop YouTube audio resolver, not an InnerTubeX fallback. */
internal object DesktopStreamClient {
    suspend fun resolve(song: Song, maxKbps: Int = Int.MAX_VALUE): Result<DesktopStream> = try {
        val stream = DesktopYtDlpResolver.resolve(song.videoId, maxKbps)
        DesktopTrackLog.log("youtube: direct audio resolved through yt-dlp")
        Result.success(stream)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun resolveUrl(song: Song): Result<String> = resolve(song).map(DesktopStream::url)

    // Retained for Main's shared Android/video resolver configuration. The desktop audio path
    // above never invokes that resolver and never writes this ThreadLocal.
    internal val ceiling = ThreadLocal<Int>()
}
