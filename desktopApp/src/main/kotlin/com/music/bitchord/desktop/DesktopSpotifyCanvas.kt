package com.music.bitchord.desktop

import com.music.bitchord.data.canvas.SpotifyCanvasQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Spotify's Canvas: the short looping clip shown behind a track in their own app.
 *
 * Needs the listener's `sp_dc` cookie. [DesktopSpotifyLibraryAuth] harvests the authenticated
 * bearer and client token from Spotify's real web player inside JavaFX WebEngine, as mobile does.
 */
internal object DesktopSpotifyCanvas {

    private const val SEARCH_URL = "https://api.spotify.com/v1/search"
    private const val ALBUM_TRACKS_URL = "https://api.spotify.com/v1/albums"
    private const val CANVAS_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"
    // Same Pathfinder track-search operation used by the upstream BitChord v1.8.1 release.
    private const val PATHFINDER_SEARCH_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
    private const val PATHFINDER_SEARCH_HASH =
        "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"

    /** spclient gates this path to Spotify's own apps by user agent; the web player's is turned
     * away, so this wears a mobile client's instead. */
    private const val SPOTIFY_APP_UA = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val CANVAS_URL_REGEX = Regex("""https://[^"'\s\x00-\x1F]+\.cnvs\.mp4""")

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private data class TrackHit(val uri: String, val title: String, val artist: String, val album: String?)

    suspend fun search(title: String, artist: String, album: String?): DesktopCanvasArtwork? {
        val tokens = DesktopSpotifyLibraryAuth.tokens() ?: return null
        val token = tokens.bearer
        // Match upstream BitChord's working provider: Spotify's own Pathfinder search is
        // more reliable with this session than /v1/search, which often returns 429 or no
        // results even for tracks that visibly have a Canvas in Spotify Web Player.
        val hit = searchViaPathfinder(title, artist, album, token)
            ?: searchTrack(title, artist, album, token)
            ?: return null
        val canvasUrl = fetchCanvasUrl(hit.uri, token) ?: return null
        return DesktopCanvasArtwork(
            url = canvasUrl,
            title = hit.title,
            artist = hit.artist,
            album = hit.album,
            source = DesktopCanvasSource.SPOTIFY,
        )
    }

    /** A release's canvas, read off its first track — Spotify hangs Canvas off tracks, not
     * releases, so there is no album-level lookup to make directly. */
    suspend fun searchAlbum(album: String, artist: String): DesktopCanvasArtwork? {
        val tokens = DesktopSpotifyLibraryAuth.tokens() ?: return null
        val token = tokens.bearer
        val items = get(
            url("$SEARCH_URL", listOf("q" to "$album $artist", "type" to "album", "limit" to "10")),
            token,
        )?.get("albums")?.jsonObject?.get("items")?.jsonArray ?: return null

        for (item in items) {
            val record = item as? JsonObject ?: continue
            val recordTitle = record.text("name") ?: continue
            val artists = record["artists"]?.jsonArray
                ?.mapNotNull { it.jsonObject.text("name") }
                .orEmpty()
            if (!canvasMatches(recordTitle, artists, album, artist)) continue
            val albumId = record.text("id") ?: continue
            val trackUri = firstTrackUri(albumId, token) ?: continue
            val canvasUrl = fetchCanvasUrl(trackUri, token) ?: continue
            return DesktopCanvasArtwork(
                url = canvasUrl,
                title = recordTitle,
                artist = artists.joinToString(", ").ifBlank { null },
                album = recordTitle,
                source = DesktopCanvasSource.SPOTIFY,
            )
        }
        return null
    }

    /**
     * The upstream BitChord v1.8.1 searchTracks persisted query. Spotify Canvas lookup
     * needs a track URI; using the Web Player's own Pathfinder search is more reliable
     * than the public REST search with a browser-session token.
     */
    private fun searchViaPathfinder(title: String, artist: String, album: String?, token: String): TrackHit? {
        val clientToken = DesktopSpotifyLibraryAuth.canvasClientToken() ?: return null
        val searchTerm = listOfNotNull(title, artist, album).joinToString(" ")
        val variables = buildJsonObject {
            put("searchTerm", searchTerm)
            put("offset", 0)
            put("limit", 10)
            put("numberOfTopResults", 5)
            put("includeAudiobooks", false)
            put("includePreReleases", false)
        }.toString()
        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", PATHFINDER_SEARCH_HASH)
            }
        }.toString()
        val requestUrl = url(
            PATHFINDER_SEARCH_URL,
            listOf(
                "operationName" to "searchTracks",
                "variables" to variables,
                "extensions" to extensions,
            ),
        )
        val body = canvasGet(
            requestUrl,
            mapOf(
                "Authorization" to "Bearer $token",
                "Client-Token" to clientToken,
                "App-platform" to "WebPlayer",
                "Accept" to "application/json",
                "User-Agent" to CANVAS_UA,
            ),
        ) ?: run {
            DesktopTrackLog.log("canvas: Spotify Pathfinder track search returned no response")
            return null
        }
        val firstItem = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?.get("data")?.jsonObject
            ?.get("searchV2")?.jsonObject
            ?.get("tracksV2")?.jsonObject
            ?.get("items")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("item")?.jsonObject
            ?.get("data")?.jsonObject
        if (firstItem == null) {
            DesktopTrackLog.log("canvas: Spotify Pathfinder search returned no track for $title")
            return null
        }
        val uri = firstItem.text("uri")
            ?: firstItem.text("id")?.let { "spotify:track:$it" }
            ?: return null
        DesktopTrackLog.log("canvas: Spotify Pathfinder matched $title — $artist")
        // As upstream does, trust Spotify's own ranked result for this combined query;
        // the GraphQL hit shape is not stable enough to safely reparse artist metadata.
        return TrackHit(uri, title, artist, album)
    }

    private fun searchTrack(title: String, artist: String, album: String?, token: String): TrackHit? {
        // YouTube Music sometimes uses a release title that Spotify indexes differently.
        // First try the more specific query, then retry by track + artist without album.
        val queries = listOfNotNull(
            album?.takeIf { it.isNotBlank() }?.let { "$title $artist $it" },
            "$title $artist",
        ).distinct()
        for (query in queries) {
            val items = get(url(SEARCH_URL, listOf("q" to query, "type" to "track", "limit" to "20")), token)
                ?.get("tracks")?.jsonObject?.get("items")?.jsonArray
                ?: continue
            for (item in items) {
                val track = item as? JsonObject ?: continue
                val trackTitle = track.text("name") ?: continue
                val artists = track["artists"]?.jsonArray
                    ?.mapNotNull { it.jsonObject.text("name") }
                    .orEmpty()
                if (!canvasMatches(trackTitle, artists, title, artist)) continue
                val uri = track.text("uri") ?: continue
                return TrackHit(
                    uri = uri,
                    title = trackTitle,
                    artist = artists.joinToString(", ").ifBlank { artist },
                    album = track["album"]?.jsonObject?.text("name"),
                )
            }
        }
        DesktopTrackLog.log("canvas: no exact Spotify track match for $title — $artist")
        return null
    }

    private fun firstTrackUri(albumId: String, token: String): String? =
        get(url("$ALBUM_TRACKS_URL/$albumId/tracks", listOf("limit" to "1")), token)
            ?.get("items")?.jsonArray?.firstOrNull()?.jsonObject?.text("uri")

    // ── Pathfinder: the `canvas` GraphQL query ───────────────────────────

    /** The live `canvas` query hash, read off the web player's own scripts. */
    private val queryHashes = SpotifyCanvasQuery.QueryHashes(fetch = { url -> canvasGet(url) })

    /** The track's canvas, asked for the way the current web player does; `canvaz-cache` is the
     * fallback when the query fails or answers with nothing playable. */
    private fun fetchCanvasUrl(trackUri: String, token: String): String? =
        when (val answer = fetchCanvasViaPathfinder(trackUri, token)) {
            is SpotifyCanvasQuery.Answer.Found -> answer.url
            is SpotifyCanvasQuery.Answer.NoCanvas -> {
                DesktopTrackLog.log("canvas: Pathfinder has no playable canvas (${answer.detail}); trying canvaz-cache")
                fetchCanvasViaCanvaz(trackUri, token)
            }
            is SpotifyCanvasQuery.Answer.Failed -> {
                DesktopTrackLog.log("canvas: Spotify's canvas query failed (${answer.reason}); trying canvaz-cache")
                fetchCanvasViaCanvaz(trackUri, token)
            }
        }

    private fun fetchCanvasViaPathfinder(trackUri: String, token: String, isRetry: Boolean = false): SpotifyCanvasQuery.Answer {
        val hash = queryHashes.canvasHash(forceRefresh = isRetry)
        val response = runCatching {
            val builder = HttpRequest.newBuilder(URI.create(SpotifyCanvasQuery.ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Accept-Language", "en")
                .header("App-platform", "WebPlayer")
                .header("User-Agent", CANVAS_UA)
            authHeaders(token).forEach { (name, value) -> builder.header(name, value) }
            http.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(SpotifyCanvasQuery.requestBody(trackUri, hash))).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }.getOrElse { return SpotifyCanvasQuery.Answer.Failed("request threw: ${it.message}") }
        if (response.statusCode() !in 200..299) return SpotifyCanvasQuery.Answer.Failed("http ${response.statusCode()}")

        val answer = SpotifyCanvasQuery.parse(response.body())
        // A rebuilt web player retires the old hash: look it up again once.
        if (answer is SpotifyCanvasQuery.Answer.Failed && answer.staleHash && !isRetry) {
            return fetchCanvasViaPathfinder(trackUri, token, isRetry = true)
        }
        return answer
    }

    // ── canvaz-cache: protobuf in, protobuf out (fallback) ───────────────

    internal data class CanvasHit(val url: String, val trackUri: String?)

    private fun fetchCanvasViaCanvaz(trackUri: String, token: String): String? {
        val bytes = runCatching {
            val builder = HttpRequest.newBuilder(URI.create(CANVAS_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/protobuf")
                .header("Content-Type", "application/protobuf")
                .header("Accept-Language", "en")
                .header("User-Agent", SPOTIFY_APP_UA)
            authHeaders(token).forEach { (name, value) -> builder.header(name, value) }
            val response = http.send(
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(encodeCanvasRequest(trackUri))).build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            if (response.statusCode() in 200..299) response.body() else null
        }.getOrNull() ?: return null

        // The structured parse first — it can tell this track's own clip apart from another one
        // bundled into the same response. The regex is the fallback, needing only a *.cnvs.mp4 URL
        // to be sitting in the bytes as plain text.
        val hits = decodeCanvasResponse(bytes)
        hits.firstOrNull { it.trackUri == trackUri }?.url?.let { return it }
        hits.firstOrNull()?.url?.let { return it }
        return CANVAS_URL_REGEX.find(String(bytes, Charsets.ISO_8859_1))?.value
    }

    /** `CanvasRequest { repeated Track tracks = 1; Track { string track_uri = 1; } }` */
    internal fun encodeCanvasRequest(trackUri: String): ByteArray {
        val track = ByteArrayOutputStream().apply { writeLengthDelimited(1, trackUri.toByteArray()) }
        return ByteArrayOutputStream().apply { writeLengthDelimited(1, track.toByteArray()) }.toByteArray()
    }

    /**
     * `CanvasResponse { repeated Canvas canvases = 1; }`, `Canvas { id = 1; canvas_url = 2;
     * track_uri = 5; }` — only the fields this needs are read.
     */
    internal fun decodeCanvasResponse(bytes: ByteArray): List<CanvasHit> = runCatching {
        val hits = mutableListOf<CanvasHit>()
        val reader = ProtoReader(bytes)
        while (reader.hasMore()) {
            val tag = reader.readTag() ?: break
            if (tag shr 3 == 1 && tag and 7 == 2) {
                decodeCanvas(reader.readBytes())?.let(hits::add)
            } else {
                reader.skip(tag)
            }
        }
        hits
    }.getOrElse { emptyList() }

    private fun decodeCanvas(bytes: ByteArray): CanvasHit? = runCatching {
        var url: String? = null
        var trackUri: String? = null
        val reader = ProtoReader(bytes)
        while (reader.hasMore()) {
            val tag = reader.readTag() ?: break
            when {
                tag shr 3 == 2 && tag and 7 == 2 -> url = String(reader.readBytes(), StandardCharsets.UTF_8)
                tag shr 3 == 5 && tag and 7 == 2 -> trackUri = String(reader.readBytes(), StandardCharsets.UTF_8)
                else -> reader.skip(tag)
            }
        }
        url?.let { CanvasHit(it, trackUri) }
    }.getOrNull()

    // ── Plumbing ─────────────────────────────────────────────────────────

    /** The client token on top of the bearer: both endpoints turn away a bearer-only request with
     * a 429 that reads exactly like rate limiting. Omitted rather than fatal when minting fails. */
    private fun authHeaders(token: String): Map<String, String> = buildMap {
        put("Authorization", "Bearer $token")
        DesktopSpotifyLibraryAuth.canvasClientToken()?.let { put("Client-Token", it) }
    }

    private fun get(url: String, token: String): JsonObject? =
        canvasGet(url, authHeaders(token))
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun url(base: String, params: List<Pair<String, String>>): String =
        params.joinToString("&", prefix = "$base?") { (name, value) ->
            "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }

    /** A string field, or null when Spotify put something other than a string there. */
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
}

// ── A protobuf reader, small enough not to be worth a dependency ────────────

private class ProtoReader(private val bytes: ByteArray) {
    private var at = 0

    fun hasMore(): Boolean = at < bytes.size

    fun readTag(): Int? = if (hasMore()) readVarint().toInt() else null

    fun readBytes(): ByteArray {
        val length = readVarint().toInt()
        val end = (at + length).coerceAtMost(bytes.size)
        val slice = bytes.copyOfRange(at, end)
        at = end
        return slice
    }

    fun skip(tag: Int) {
        when (tag and 7) {
            0 -> readVarint()
            1 -> at += 8
            2 -> readBytes()
            5 -> at += 4
            else -> at = bytes.size
        }
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (at < bytes.size && shift < 64) {
            val byte = bytes[at++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return result
    }
}

private fun ByteArrayOutputStream.writeLengthDelimited(field: Int, payload: ByteArray) {
    writeVarint((field shl 3 or 2).toLong())
    writeVarint(payload.size.toLong())
    write(payload)
}

private fun ByteArrayOutputStream.writeVarint(value: Long) {
    var remaining = value
    while (true) {
        if (remaining and 0x7FL.inv() == 0L) {
            write(remaining.toInt())
            return
        }
        write((remaining and 0x7F or 0x80).toInt())
        remaining = remaining ushr 7
    }
}

/**
 * Persistent Spotify session cookie. Bearer and client tokens are minted from the real web player
 * by DesktopSpotifyLibraryAuth, matching the mobile v1.8.1 implementation.
 */
internal object DesktopSpotifyToken {
    fun cookie(): String = DesktopPersistence().string(KEY_SPDC).trim()

    fun setCookie(value: String) {
        DesktopPersistence().saveString(KEY_SPDC, value.trim())
    }

    internal const val KEY_SPDC = "spotify_spdc_token"
}
