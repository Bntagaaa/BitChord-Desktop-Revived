// Ported directly from ORIGINAL BitChord v1.9.1 mobile JioSaavnService.
// JVM changes: java.util.Base64 and the original shared OkHttp transport replace Android/Ktor.
package com.music.bitchord.data.jiosaavn

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Serializable
data class RawArtistMapItem(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class RawArtistMap(
    @SerialName("primary_artists") val primaryArtists: List<RawArtistMapItem> = emptyList(),
)

@Serializable
data class RawMoreInfo(
    val album_id: String = "",
    val album: String = "",
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String = "",
    val duration: String = "",
    /**
     * Whether a 320kbps rendition exists, as `"true"`/`"false"`.
     *
     * The catalogue states this per track and it is frequently false. Asking
     * the CDN for `_320` anyway does not produce one.
     */
    @SerialName("320kbps") val has320: String = "",
    val artistMap: RawArtistMap = RawArtistMap(),
) {
    val supports320: Boolean get() = has320.equals("true", ignoreCase = true)
}

/** A decoded CDN URL and the bitrate it will really deliver. */
data class SaavnStream(val url: String, val kbps: Int?)

/**
 * Select the highest JioSaavn CDN rendition without losing URL parameters.
 *
 * The encrypted URL normally names the 96 kbps file and the catalogue states
 * whether the parallel 320 kbps object exists. CDN URLs are not guaranteed to
 * end at `.mp4`: cache-busting or access query parameters may follow it. The
 * old end-anchored match missed those URLs, left `_96.mp4` in place, and still
 * reported 320 upstream. Playback and Downloads consequently displayed 320
 * while both fetched the 96 kbps object.
 */
internal fun selectBestSaavnStream(decryptedUrl: String, supports320: Boolean): SaavnStream? {
    if (decryptedUrl.isBlank()) return null
    val rendition = Regex(
        "_(48|96|160|320)\\.(mp4|aac|mp3)(?=[?#]|$)",
        RegexOption.IGNORE_CASE,
    ).find(decryptedUrl)
        // An unrecognised URL must not be labelled 320 merely because the
        // catalogue says that rendition exists; no rewrite was actually made.
        ?: return SaavnStream(decryptedUrl, null)

    val offered = rendition.groupValues[1].toInt()
    if (!supports320) return SaavnStream(decryptedUrl, offered)

    val extension = rendition.groupValues[2]
    return SaavnStream(
        decryptedUrl.replaceRange(rendition.range, "_320.$extension"),
        320,
    )
}

@Serializable
data class RawSongItem(
    val id: String = "",
    val title: String = "",
    val image: String = "",
    /** JioSaavn sends this as the string `"1"` or `"0"`. */
    @SerialName("explicit_content") val explicitContent: String = "",
    @SerialName("more_info") val moreInfo: RawMoreInfo = RawMoreInfo()
) {
    val isExplicit: Boolean
        get() = explicitContent == "1" || explicitContent.equals("true", ignoreCase = true)
}

@Serializable
data class RawSearchResponse(
    val results: List<RawSongItem> = emptyList()
)

@Serializable
data class RawSongsResponse(
    val songs: List<RawSongItem> = emptyList()
)

/**
 * Put uncensored catalogue rows ahead of their clean duplicates.
 *
 * Top-level so the pure ordering rule remains unit-testable without
 * initialising Android's Base64-backed service singleton on the JVM.
 */
internal fun prioritizeExplicit(songs: List<RawSongItem>): List<RawSongItem> =
    songs.sortedByDescending { it.isExplicit }

object JioSaavnService {
    private const val BASE_URL = "https://www.jiosaavn.com/api.php"
    private val json = Json { isLenient = true; ignoreUnknownKeys = true; explicitNulls = false }
    private val client = Http.client.newBuilder().connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()

    private suspend fun request(call: String, parameters: Map<String, String>): String = withContext(Dispatchers.IO) {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("__call", call).addQueryParameter("_format", "json")
            .addQueryParameter("_marker", "0").addQueryParameter("api_version", "4")
            .addQueryParameter("ctx", "android")
        parameters.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request = Request.Builder().url(url.build()).header("Accept", "application/json")
            .header("User-Agent", "BitChord-Reworked/${System.getProperty("bitchord.version", "1.9.1-reworked.1")}")
            .header("Accept-Language", "en-IN,en;q=0.9").header("Cookie", "explicit_content=1").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "JioSaavn answered HTTP ${response.code}" }
            val bytes = response.body.byteStream().use { it.readNBytes(4 * 1024 * 1024 + 1) }
            require(bytes.size <= 4 * 1024 * 1024) { "JioSaavn response exceeds its size limit" }
            bytes.toString(Charsets.UTF_8)
        }
    }

    suspend fun searchSongs(query: String): List<RawSongItem> =
        json.decodeFromString<RawSearchResponse>(request("search.getResults", mapOf("q" to query, "p" to "1", "n" to "10"))).results

    suspend fun getStreamUrl(saavnSongId: String): SaavnStream? {
        val root = json.parseToJsonElement(request("song.getDetails", mapOf("pids" to saavnSongId))) as? JsonObject ?: return null
        val element = (root["songs"] as? JsonArray)?.firstOrNull() ?: root.values.firstOrNull { it is JsonObject } ?: return null
        val song = json.decodeFromJsonElement(RawSongItem.serializer(), element)
        if (song.moreInfo.encryptedMediaUrl.isBlank()) return null
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("38346591".toByteArray(Charsets.UTF_8), "DES"))
        val url = cipher.doFinal(Base64.getMimeDecoder().decode(song.moreInfo.encryptedMediaUrl)).toString(Charsets.UTF_8).trim()
        return selectBestSaavnStream(url, song.moreInfo.supports320)
    }
}
