package com.music.bitchord.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Public Apple Music artwork for an artist page. The logo is Apple's own
 * transparent title artwork, not a locally guessed font.
 */
internal data class DesktopAppleArtistArt(
    val heroUrl: String,
    val logoUrl: String?,
    val logoAspect: Float,
    val backgroundColor: Int,
    val textColor: Int,
    val keyColor: Int?,
)

/**
 * Reads the public artist page metadata Apple embeds for its web player.
 *
 * This deliberately needs no Apple Music account or cookies. The YouTube
 * Music artist image remains the immediate fallback while the lookup runs,
 * and also remains the fallback whenever Apple has no matching artwork.
 */
internal object DesktopAppleArtistArtRepository {
    private const val CACHE_SIZE = 64
    private const val HERO_WIDTH = 1200
    private const val LOGO_WIDTH = 1000
    private const val TIMEOUT_MS = 8_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val cache = object : LinkedHashMap<String, DesktopAppleArtistArt>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DesktopAppleArtistArt>?): Boolean =
            size > CACHE_SIZE
    }

    fun cached(artistName: String): DesktopAppleArtistArt? {
        val key = artistName.normalized()
        return synchronized(cache) { cache[key] }
    }

    suspend fun artFor(artistName: String): DesktopAppleArtistArt? {
        val key = artistName.normalized()
        if (key.isBlank()) return null
        synchronized(cache) { cache[key]?.let { return it } }

        val result = withContext(Dispatchers.IO) {
            runCatching { lookup(artistName, key) }.getOrNull()
        } ?: return null

        synchronized(cache) { cache[key] = result }
        return result
    }

    private fun lookup(artistName: String, key: String): DesktopAppleArtistArt? {
        val encodedName = URLEncoder.encode(artistName, Charsets.UTF_8.name())
        val searchUrl =
            "https://itunes.apple.com/search?term=$encodedName&entity=musicArtist&limit=5"
        val searchJson = getText(searchUrl) ?: return null
        val results = runCatching {
            Json.parseToJsonElement(searchJson).asObject()?.get("results").asArray()
        }.getOrNull() ?: return null

        val match = results.firstNotNullOfOrNull { element ->
            val candidate = element.asObject() ?: return@firstNotNullOfOrNull null
            candidate.takeIf { it.string("artistName").normalized() == key }
        } ?: return null

        val artistUrl = match.string("artistLinkUrl").substringBefore('?').takeIf(String::isNotBlank)
            ?: match.int("artistId").takeIf { it > 0 }?.let { "https://music.apple.com/us/artist/$it" }
            ?: return null

        val html = getText(artistUrl) ?: return null
        val script = runCatching {
            Jsoup.parse(html).selectFirst("script#serialized-server-data")
        }.getOrNull() ?: return null
        val serialized = script.data().ifBlank { script.html() }
        if (serialized.isBlank()) return null
        val root = runCatching { Json.parseToJsonElement(serialized) }.getOrNull() ?: return null
        val header = findArtistHeader(root) ?: return null

        val logo = header.objectAt("artistLogo")?.objectAt("dictionary")
        val hero = header.objectAt("artwork")?.objectAt("dictionary")
            ?: header.objectAt("colorBackdropArtwork")?.objectAt("dictionary")
            ?: return null

        val heroWidth = hero.int("width")
        val heroHeight = hero.int("height")
        if (heroWidth <= 0 || heroHeight <= 0) return null

        val heroUrl = fillImageUrl(
            hero.string("url"),
            HERO_WIDTH,
            (HERO_WIDTH.toLong() * heroHeight / heroWidth).toInt(),
            "jpg",
        ) ?: return null

        val logoWidth = logo?.int("width") ?: 0
        val logoHeight = logo?.int("height") ?: 0
        val logoUrl = if (logoWidth > 0 && logoHeight > 0) {
            fillImageUrl(
                logo.string("url"),
                LOGO_WIDTH,
                (LOGO_WIDTH.toLong() * logoHeight / logoWidth).toInt(),
                "png",
            )
        } else {
            null
        }

        val background = parseAppleColor(hero.string("bgColor")) ?: return null
        val textColor = parseAppleColor(hero.string("textColor1")) ?: 0xFFFFFFFF.toInt()
        val keyColor = parseAppleColor(header.string("keyColor"))

        return DesktopAppleArtistArt(
            heroUrl = heroUrl,
            logoUrl = logoUrl,
            logoAspect = if (logoUrl != null) logoWidth.toFloat() / logoHeight else 1f,
            backgroundColor = background,
            textColor = textColor,
            keyColor = keyColor,
        )
    }

    private fun getText(address: String): String? {
        val connection = runCatching { URL(address).openConnection() as HttpURLConnection }.getOrNull()
            ?: return null
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun findArtistHeader(node: JsonElement): JsonObject? = when (node) {
        is JsonObject -> {
            if ("artistLogo" in node) node
            else node.values.firstNotNullOfOrNull(::findArtistHeader)
        }
        is JsonArray -> node.firstNotNullOfOrNull(::findArtistHeader)
        else -> null
    }

    private fun fillImageUrl(template: String, width: Int, height: Int, format: String): String? {
        if (!template.contains("{w}")) return null
        return template
            .replace("{w}", width.toString())
            .replace("{h}", height.toString())
            .replace("{c}", "bb")
            .replace("{f}", format)
    }

    private fun parseAppleColor(value: String): Int? {
        if (!value.matches(Regex("[0-9a-fA-F]{6}"))) return null
        return (0xFF000000L or value.toLong(16)).toInt()
    }

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
    private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.objectAt(key: String): JsonObject? = this[key].asObject()
    private fun JsonObject.string(key: String): String = this[key].asString().orEmpty()
    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private fun String.normalized(): String =
        Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .filter(Char::isLetterOrDigit)
}
