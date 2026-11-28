package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A looping video that stands in for a track's cover art — what Spotify calls a Canvas and Apple
 * calls motion artwork.
 *
 * [url] is what the player mounts; [fallbackUrl] is tried once if that errors, which is how the
 * Apple provider hands over a second rendition of the same clip. The metadata is not decoration:
 * providers search by free text and will happily return the wrong album's clip, so [matches]
 * re-checks the answer against what is playing.
 */
data class DesktopCanvasArtwork(
    val url: String,
    val fallbackUrl: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: DesktopCanvasSource = DesktopCanvasSource.OTHER,
) {
    /**
     * Whether this clip really belongs to the track we asked about.
     *
     * Title and artists must match exactly once punctuation, case and accents are stripped — a near
     * miss is a different song by the same artist, which is the failure people notice. The album is
     * only held to that standard when both sides know it, since a track's album resolves after the
     * player opens and one queued from search may never get one.
     */
    fun matches(wantTitle: String, wantArtist: String, wantAlbum: String?): Boolean {
        val titleOk = title == null || wantTitle.isBlank() ||
            title.normalizeForCanvasMatch() == wantTitle.normalizeForCanvasMatch()
        val wanted = splitCanvasArtists(wantArtist)
        val ours = splitCanvasArtists(artist.orEmpty())
        val artistOk = artist == null || wantArtist.isBlank() ||
            (wanted.isNotEmpty() && ours.isNotEmpty() && wanted.all { want -> ours.any { it == want } })
        val albumOk = album.isNullOrBlank() || wantAlbum.isNullOrBlank() ||
            album.normalizeForCanvasMatch() == wantAlbum.normalizeForCanvasMatch()
        return titleOk && artistOk && albumOk
    }
}

/**
 * The four providers, asked in turn until one answers with a clip that is really this track's.
 *
 * General-purpose motion artwork for the main player uses Apple, Tidal and community sources.
 * Spotify Canvas is reserved for the Now Playing Details card via lookupSpotify().
 */
object DesktopCanvasClient {

    private const val CACHE_SIZE = 64

    /**
     * A settled answer for one track or release.
     *
     * [withAlbum] records whether the album name was known when this was worked out. It is the one
     * thing that can turn a miss into a hit later: the album is what makes the catalogue searches
     * land, and on the player it resolves a beat after the track starts.
     */
    private class Entry(val artwork: DesktopCanvasArtwork?, val withAlbum: Boolean) {
        /** A hit is a hit — the album could only have confirmed it. A miss stands too, unless it
         * was reached blind and there is now an album name to try. */
        fun reusable(nowWithAlbum: Boolean): Boolean =
            artwork != null || withAlbum || !nowWithAlbum
    }

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > CACHE_SIZE
    }

    // Deduplicate concurrent lookups for the same key (e.g. Details panel + NowPlayingScreen)
    // without serializing unrelated songs behind one global mutex.
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<DesktopCanvasArtwork?>>()
    private val spotifyGate = Mutex()

    /**
     * The canvas for [song], or null when there is not one. Never throws.
     *
     * Only a local file with no catalogue identity is answered as a miss without a request. A
     * download carries both a videoId and a local path, and skipping on the path alone is what
     * left downloaded tracks with no canvas.
     */
    suspend fun lookup(song: Song): DesktopCanvasArtwork? {
        if (song.videoId.isBlank() && (song.localUri != null || song.localPath != null)) return null
        val title = song.title.cleanedForCanvas()
        val artist = song.artist.cleanedForCanvas()
        if (title.isBlank() || artist.isBlank()) return null
        val album = song.albumName
        // Keyed on the track alone: the album arrives after the player opens, and keying on it made
        // the late arrival look like a different question.
        return resolve("song|${song.videoId}", album != null) {
            firstHit(
                { DesktopAppleMusicCanvas.search(title, artist, album) },
                { DesktopTidalCanvas.search(title, artist, album) },
                { DesktopCommunityCanvas.search(title, artist, album) },
            ) { it.matches(title, artist, album) }
        }
    }

    /**
     * Spotify-only Canvas for the Now Playing Details card.
     *
     * Kept separate from the main player's four-provider cache: a square Apple/Tidal
     * motion cover must never be mistaken for a Spotify Canvas. No Spotify session
     * means no request, and a missing clip simply leaves the static album artwork.
     */
    suspend fun lookupSpotify(song: Song): DesktopCanvasArtwork? {
        if (DesktopSpotifyToken.cookie().isBlank()) return null
        if (song.videoId.isBlank() && (song.localUri != null || song.localPath != null)) return null
        val title = song.title.cleanedForCanvas()
        val artist = song.artist.cleanedForCanvas()
        if (title.isBlank() || artist.isBlank()) return null
        val album = song.albumName
        // Keep this dedicated to Now Playing Details; the general player never requests Spotify.
        cached(song)?.takeIf {
            // Spotify can index this recording under a different release name from
            // YouTube Music. The exact track and credited artist are the identity.
            it.source == DesktopCanvasSource.SPOTIFY && it.matches(title, artist, null)
        }?.let { return it }
        // Only cache successful Spotify results. Authentication and Canvas endpoints are
        // external, so a timeout/401/temporary miss must not poison the next retry.
        val key = "spotify|song|${song.videoId}|${title}|${artist}"
        return spotifyGate.withLock {
            synchronized(cache) { cache[key]?.artwork }?.let { return@withLock it }
            val found = withContext(Dispatchers.IO) {
                runCatching { DesktopSpotifyCanvas.search(title, artist, album) }
                    .onFailure { DesktopTrackLog.log("canvas: Spotify lookup failed (${it.message})") }
                    .getOrNull()
            }?.takeIf {
                it.source == DesktopCanvasSource.SPOTIFY && it.matches(title, artist, null)
            }
            if (found != null) synchronized(cache) { cache[key] = Entry(found, album != null) }
            found
        }
    }

    /** A canvas already worked out for [song], without going near the network. */
    fun cached(song: Song): DesktopCanvasArtwork? =
        synchronized(cache) { cache["song|${song.videoId}"]?.artwork }

    /** Pre-resolves metadata for the upcoming track without touching video decoders or frame RAM. */
    suspend fun prefetch(song: Song, warmMedia: Boolean = false, maxEdge: Int = DesktopCanvasDecoder.MAX_EDGE) {
        val artwork = cached(song) ?: runCatching { lookup(song) }.getOrNull() ?: return
        if (warmMedia) {
            withContext(Dispatchers.IO) {
                DesktopCanvasCache.warm(artwork.url, maxEdge)
            }
        }
    }

    /**
     * The canvas for a release, for an album page's header.
     *
     * A separate lookup rather than the first track's: the services hang motion artwork off the
     * album, so asking directly is both fewer requests and a better match.
     */
    suspend fun lookupAlbum(album: String, artist: String): DesktopCanvasArtwork? {
        val name = album.cleanedForCanvas()
        val credit = artist.cleanedForCanvas()
        if (name.isBlank() || credit.isBlank()) return null
        return resolve("album|$name|$credit", withAlbum = true) {
            firstHit(
                { DesktopAppleMusicCanvas.searchAlbum(name, credit) },
                { DesktopTidalCanvas.searchAlbum(name, credit) },
                { DesktopCommunityCanvas.searchAlbum(name, credit) },
            ) { it.matches(name, credit, name) }
        }
    }

    private suspend fun resolve(
        key: String,
        withAlbum: Boolean,
        lookUp: suspend () -> DesktopCanvasArtwork?,
    ): DesktopCanvasArtwork? {
        synchronized(cache) {
            cache[key]?.let { if (it.reusable(withAlbum)) return it.artwork }
        }
        val flightKey = "$key|album=$withAlbum"
        val deferred = synchronized(inFlight) {
            inFlight[flightKey] ?: lookupScope.async(start = CoroutineStart.LAZY) {
                try {
                    val found = lookUp()
                    synchronized(cache) {
                        val existing = cache[key]
                        if (existing == null || found != null || (!existing.withAlbum && withAlbum)) {
                            cache[key] = Entry(found, withAlbum)
                        }
                    }
                    found
                } finally {
                    inFlight.remove(flightKey)
                }
            }.also {
                inFlight[flightKey] = it
                it.start()
            }
        }
        return deferred.await()
    }

    /**
     * Queries providers in parallel with a bounded timeout and picks the highest-priority
     * match that survives [accept]. If a lower-priority source already answered, higher-priority
     * sources only get a short grace window so a slow Apple lookup never stalls a ready Tidal clip.
     */
    private suspend fun firstHit(
        vararg sources: () -> DesktopCanvasArtwork?,
        accept: (DesktopCanvasArtwork) -> Boolean,
    ): DesktopCanvasArtwork? = coroutineScope {
        val ready = Channel<Int>(sources.size)
        val jobs = sources.mapIndexed { index, source ->
            async(Dispatchers.IO) {
                val hit = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                    try {
                        runInterruptible { source() }?.takeIf(accept)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
                ready.trySend(index)
                hit
            }
        }
        try {
            repeat(jobs.size) {
                val index = ready.receive()
                val hit = jobs[index].await() ?: return@repeat
                val preferred = jobs.take(index)
                return@coroutineScope withTimeoutOrNull(PRIORITY_GRACE_MS) {
                    preferred.firstNotNullOfOrNull { it.await() }
                } ?: preferred.firstNotNullOfOrNull { if (it.isCompleted) it.await() else null } ?: hit
            }
            null
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    private const val PROVIDER_TIMEOUT_MS = 4_500L
    private const val PRIORITY_GRACE_MS = 650L
}

/**
 * YouTube Music titles carry packaging the catalogue services never see. Searching with it finds
 * nothing and matching against it rejects everything, so it comes off before either.
 */
internal fun String.cleanedForCanvas(): String = replace(CANVAS_NOISE, " ")
    .substringBefore(" | ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .ifBlank { this }

private val CANVAS_NOISE = Regex(
    """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
        """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
    RegexOption.IGNORE_CASE,
)

/** Whether this URL points at a playlist rather than at the media itself. */
internal fun isManifest(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".mpd")
}

internal fun isHlsManifest(url: String): Boolean =
    url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)

/** The clip itself, on disk. */
internal object DesktopCanvasCache {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun fileFor(url: String): Path? {
        DesktopMediaCache.existing(url, "mp4")?.let { return it }
        return runCatching {
            val target = DesktopMediaCache.pathFor(url, "mp4")
            val staging = Files.createTempDirectory(target.parent, "motion-")
            val download = staging.resolve("video")
            try {
                val response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", DESKTOP_CANVAS_UA)
                        .header("Accept", "*/*")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofFile(download),
                )
                check(response.statusCode() in 200..299 && Files.size(download) > 0) {
                    "motion download did not return video (HTTP ${response.statusCode()})"
                }
                DesktopMediaCache.existing(url, "mp4") ?: Files.move(download, target, ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(download)
                Files.deleteIfExists(staging)
            }
        }.onSuccess {
            DesktopMediaCache.trim()
        }.onFailure {
            DesktopTrackLog.log("motion download failed: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private val resolvedVariants = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 64
    }
    private val warmingKeys = ConcurrentHashMap.newKeySet<String>()
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun hlsFileIfCached(url: String, maxEdge: Int): Path? {
        val cacheKey = "$maxEdge|$url"
        return DesktopMediaCache.existing(cacheKey, "m3u8")?.takeIf(::segmentsPresent)
    }

    fun discardHlsPlaylist(path: Path) {
        runCatching { Files.deleteIfExists(path) }
            .onFailure { DesktopTrackLog.log("motion HLS cache removal failed: ${it.javaClass.simpleName}") }
    }

    fun resolveHlsMediaUrl(url: String, maxEdge: Int): String {
        val cacheKey = "$maxEdge|$url"
        synchronized(resolvedVariants) { resolvedVariants[cacheKey] }?.let { return it }
        val body = canvasGet(url, mapOf("User-Agent" to DESKTOP_CANVAS_UA)) ?: return url
        val mediaUrl = if (body.contains("#EXT-X-STREAM-INF:")) {
            selectContinuedArtistVariant(url, body, maxEdge) ?: url
        } else {
            url
        }
        synchronized(resolvedVariants) { resolvedVariants[cacheKey] = mediaUrl }
        return mediaUrl
    }

    fun warm(url: String, maxEdge: Int) {
        if (isManifest(url)) {
            if (hlsFileIfCached(url, maxEdge) == null) hlsFileFor(url, maxEdge)
        } else {
            fileFor(url)
        }
    }

    fun warmHlsInBackground(url: String, maxEdge: Int) {
        val cacheKey = "$maxEdge|$url"
        if (hlsFileIfCached(url, maxEdge) != null) return
        if (!warmingKeys.add(cacheKey)) return
        warmScope.launch {
            try {
                hlsFileFor(url, maxEdge)
            } finally {
                warmingKeys.remove(cacheKey)
            }
        }
    }

    /**
     * Resolves an HLS motion playlist to the smallest variant covering [maxEdge] and caches its
     * media playlist + segments in [DesktopMediaCache] so loops and replays stay local.
     * Uses sibling filenames so FFmpeg resolves them against the playlist on Windows and Unix.
     * Returns null for encrypted/unsupported playlists so the caller can fall back to streaming.
     */
    fun hlsFileFor(url: String, maxEdge: Int): Path? {
        val cacheKey = "$maxEdge|$url"
        hlsFileIfCached(url, maxEdge)?.let { return it }
        return runCatching {
            val mediaUrl = resolveHlsMediaUrl(url, maxEdge)
            val mediaBody = canvasGet(mediaUrl, mapOf("User-Agent" to DESKTOP_CANVAS_UA)) ?: return@runCatching null
            if (!mediaBody.contains("#EXTINF:") || mediaBody.contains("#EXT-X-KEY:")) return@runCatching null

            val baseUri = URI.create(mediaUrl)
            val lines = mediaBody.lines()
            var segmentCount = 0
            val rewritten = buildList(lines.size) {
                for (rawLine in lines) {
                    val trimmed = rawLine.trim()
                    when {
                        trimmed.startsWith("#EXT-X-MAP:") -> {
                            val uriMatch = Regex("""URI="([^"]+)"""").find(trimmed)
                            val rawUri = uriMatch?.groupValues?.get(1) ?: return@runCatching null
                            val resolved = baseUri.resolve(rawUri).toString()
                            val file = downloadSegment(resolved, segmentExtension(resolved, "mp4")) ?: return@runCatching null
                            add(trimmed.replace(uriMatch.value, """URI="${file.fileName}""""))
                        }
                        trimmed.isEmpty() || trimmed.startsWith("#") -> add(rawLine)
                        else -> {
                            if (++segmentCount > MAX_HLS_SEGMENTS) return@runCatching null
                            val resolved = baseUri.resolve(trimmed).toString()
                            val file = downloadSegment(resolved, segmentExtension(resolved, "ts")) ?: return@runCatching null
                            add(file.fileName.toString())
                        }
                    }
                }
            }
            if (segmentCount == 0) return@runCatching null

            val target = DesktopMediaCache.pathFor(cacheKey, "m3u8")
            val staging = Files.createTempDirectory(target.parent, "hls-")
            val tempPlaylist = staging.resolve("playlist.m3u8")
            try {
                Files.write(tempPlaylist, rewritten)
                Files.move(tempPlaylist, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tempPlaylist)
                Files.deleteIfExists(staging)
            }
            target
        }.onFailure {
            DesktopTrackLog.log("motion HLS cache failed: ${it.javaClass.simpleName}")
        }.getOrNull().also { DesktopMediaCache.trim() }
    }

    private fun segmentsPresent(playlist: Path): Boolean = runCatching {
        val dir = playlist.parent ?: return false
        fun exists(ref: String): Boolean {
            val path = if (ref.startsWith("file:", ignoreCase = true)) Path.of(URI.create(ref)) else dir.resolve(ref)
            return Files.isReadable(path)
        }
        Files.readAllLines(playlist).all { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-MAP:") -> {
                    val file = Regex("""URI="([^"]+)"""").find(trimmed)?.groupValues?.get(1) ?: return false
                    exists(file)
                }
                trimmed.isEmpty() || trimmed.startsWith("#") -> true
                else -> exists(trimmed)
            }
        }
    }.getOrDefault(false)

    private fun segmentExtension(url: String, defaultExt: String): String =
        url.substringBefore('?').substringBefore('#').substringAfterLast('.', defaultExt)
            .lowercase(Locale.ROOT).takeIf { it in setOf("mp4", "m4s", "ts", "aac", "m4a") } ?: defaultExt

    private fun downloadSegment(url: String, extension: String): Path? {
        DesktopMediaCache.existing(url, extension)?.let { return it }
        return runCatching {
            val target = DesktopMediaCache.pathFor(url, extension)
            val staging = Files.createTempDirectory(target.parent, "seg-")
            val download = staging.resolve("part")
            try {
                val response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", DESKTOP_CANVAS_UA)
                        .header("Accept", "*/*")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofFile(download),
                )
                check(response.statusCode() in 200..299 && Files.size(download) > 0) {
                    "segment HTTP ${response.statusCode()}"
                }
                DesktopMediaCache.existing(url, extension) ?: Files.move(download, target, ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(download)
                Files.deleteIfExists(staging)
            }
        }.getOrNull()
    }

    private const val MAX_HLS_SEGMENTS = 32
}

/** Motion artwork for the details panel and collection covers. */
@Composable
fun DesktopCanvasView(
    url: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackUrl: String? = null,
    maxEdge: Int = DesktopCanvasDecoder.MAX_EDGE,
    publishBackdrop: Boolean = true,
    playbackIdentity: String? = null,
) {
    val clock = if (playbackIdentity == null) MotionVideoSource.Clock.Artwork else MotionVideoSource.Clock.NowPlaying
    DesktopMotionArtwork(
        MotionVideoSource(url, fallbackUrl, maxEdge, clock, playbackIdentity),
        modifier, isPlaying, contentScale, publishBackdrop,
    )
}

private const val DESKTOP_CANVAS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
