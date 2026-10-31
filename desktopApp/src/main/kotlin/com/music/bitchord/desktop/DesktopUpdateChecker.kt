package com.music.bitchord.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.*

/** Official release discovery shared by startup, Settings and downloads. */
internal object DesktopUpdateChecker {
    val currentVersion: String = System.getProperty("bitchord.version") ?: "1.8-beta1"
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 10_000 }
    }

    internal fun isNewer(latest: String, current: String): Boolean {
        val left = DesktopUpdateVersion.parse(latest) ?: return false
        val right = DesktopUpdateVersion.parse(current) ?: return false
        return left > right
    }

    enum class Channel { STABLE, PREVIEW }
    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)
    data class Release(val version: String, val url: String, val notes: String, val preview: Boolean,
        val asset: Asset?, val manifest: Asset?)

    suspend fun discover(channel: Channel, installation: UpdateInstallation): Release? {
        check(DesktopUpdateVersion.parse(currentVersion) != null) { "This development version cannot be compared automatically. Use the official release page." }
        val response = http.get("https://api.github.com/repos/$REPOSITORY/releases?per_page=100") {
            header("Accept", "application/vnd.github+json")
            header("User-Agent", "BitChord-Desktop-Revived-updater")
        }
        check(response.status.value !in setOf(403, 429) || response.headers["X-RateLimit-Remaining"] != "0") { "GitHub update rate limit reached. Try again after the limit resets." }
        check(response.status.isSuccess()) { "Update service returned HTTP ${response.status.value}. Try again later." }
        return selectRelease(response.bodyAsText(), channel, installation, currentVersion)
    }

    internal fun selectRelease(body: String, channel: Channel, installation: UpdateInstallation, current: String): Release? {
        val releases = json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            fun value(key: String) = obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
            val tag = value("tag_name")
            val version = tag.removePrefix("v")
            val parsed = DesktopUpdateVersion.parse(version) ?: return@mapNotNull null
            val preview = value("prerelease") == "true" || parsed.pre.isNotEmpty()
            if (value("draft") == "true" || (preview && channel == Channel.STABLE) || !isNewer(version, current)) return@mapNotNull null
            val url = value("html_url")
            if (!officialReleaseUrl(url) || java.net.URI(url).rawPath != "/$REPOSITORY/releases/tag/$tag") return@mapNotNull null
            val assets = obj["assets"]?.jsonArray.orEmpty().mapNotNull { item ->
                val a = item as? JsonObject ?: return@mapNotNull null
                fun field(key: String) = a[key]?.jsonPrimitive?.contentOrNull.orEmpty()
                val name = field("name"); val download = field("browser_download_url")
                if (field("state") != "uploaded" || !assetBelongsToRelease(url, download) ||
                    !download.endsWith("/" + name) || !Regex("[A-Za-z0-9._-]+").matches(name)) return@mapNotNull null
                Asset(name, download, field("size").toLongOrNull() ?: 0,
                    field("digest").removePrefix("sha256:").takeIf { field("digest").startsWith("sha256:") && it.matches(Regex("[a-fA-F0-9]{64}")) })
            }
            val expected = installation.format.suffix?.let { "BitChord-$version$it" }
            val asset = assets.filter { it.name == expected && it.size > 0 }.singleOrNull()
            Release(version, url, value("body").take(32_000), preview, asset,
                assets.filter { it.name == "SHA256SUMS" && it.size in 1..262144 }.singleOrNull())
        }
        // Prefer a release matching this distribution; never cross-fallback to another format.
        return releases.filter { installation.format.suffix == null || it.asset != null }
            .maxByOrNull { DesktopUpdateVersion.parse(it.version)!! }
            ?: releases.maxByOrNull { DesktopUpdateVersion.parse(it.version)!! }
    }

    const val REPOSITORY = "Bntagaaa/BitChord-Desktop-Revived"
    internal fun officialReleaseUrl(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
            uri.rawPath.startsWith("/$REPOSITORY/releases/tag/") && uri.rawPath.substringAfter("/tag/").isNotBlank() &&
            !uri.rawPath.contains("..") && !uri.rawPath.contains('%') && uri.query == null && uri.fragment == null
    }.getOrDefault(false)
    internal fun assetBelongsToRelease(releaseUrl: String, assetUrl: String): Boolean = runCatching {
        officialReleaseUrl(releaseUrl) && officialAssetUrl(assetUrl) &&
            java.net.URI(assetUrl).rawPath.substringBeforeLast('/') == java.net.URI(releaseUrl).rawPath.replace("/releases/tag/", "/releases/download/")
    }.getOrDefault(false)
    internal fun officialAssetUrl(url: String): Boolean = officialReleaseUrl(url.replace("/releases/download/", "/releases/tag/")) &&
        java.net.URI(url).rawPath.startsWith("/$REPOSITORY/releases/download/")
}
