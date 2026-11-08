package com.music.bitchord.reworked

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger
import java.net.URI

internal const val REWORKED_REPOSITORY = "Bntagaaa/BitChord-Desktop-Revived"
internal const val REWORKED_RELEASES = "https://github.com/$REWORKED_REPOSITORY/releases"
internal const val MAX_UPDATE_BYTES = 4L * 1024 * 1024 * 1024

internal data class ReworkedVersion(val core: List<BigInteger>, val preview: List<String>) : Comparable<ReworkedVersion> {
    override fun compareTo(other: ReworkedVersion): Int {
        core.zip(other.core).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
        if (preview.isEmpty() != other.preview.isEmpty()) return if (preview.isEmpty()) 1 else -1
        preview.zip(other.preview).forEach { (a, b) ->
            if (a == b) return@forEach
            val an = a.toBigIntegerOrNull(); val bn = b.toBigIntegerOrNull()
            return when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
        }
        return preview.size.compareTo(other.preview.size)
    }

    companion object {
        fun parse(value: String): ReworkedVersion? {
            if (value.length > 256) return null
            val match = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$")
                .matchEntire(value) ?: return null
            val pre = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.').orEmpty()
            if (pre.any { it.isEmpty() || (it.all(Char::isDigit) && it.length > 1 && it[0] == '0') }) return null
            val build = match.groupValues[5]
            if (build.isNotEmpty() && build.split('.').any(String::isEmpty)) return null
            return ReworkedVersion(match.groupValues.subList(1, 4).map(::BigInteger), pre)
        }
    }
}

internal enum class ReworkedUpdateFormat(val suffix: String) {
    WINDOWS_SETUP("-windows-x64-setup.exe"),
    WINDOWS_PORTABLE("-windows-x64-portable.zip"),
    LINUX_APPIMAGE("-linux-x86_64.AppImage"),
}

internal data class ReworkedReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)
internal data class ReworkedRelease(
    val tag: String,
    val version: String,
    val notes: String,
    val asset: ReworkedReleaseAsset?,
    val checksums: ReworkedReleaseAsset?,
) {
    val releaseUrl: String get() = "$REWORKED_RELEASES/tag/$tag"
}

/** Strict product namespace: an ordinary Revived/mobile release can never be an update candidate. */
internal object ReworkedUpdateMetadata {
    fun select(json: String, current: String, format: ReworkedUpdateFormat, previews: Boolean): ReworkedRelease? {
        val running = ReworkedVersion.parse(current) ?: error("This development version cannot be compared with releases.")
        return Json.parseToJsonElement(json).jsonArray.mapNotNull { item ->
            val release = item as? JsonObject ?: return@mapNotNull null
            if (release["draft"]?.jsonPrimitive?.booleanOrNull != false) return@mapNotNull null
            val prerelease = release["prerelease"]?.jsonPrimitive?.booleanOrNull ?: return@mapNotNull null
            if (prerelease && !previews) return@mapNotNull null
            val tag = release.text("tag_name") ?: return@mapNotNull null
            if (!tag.startsWith("reworked-v")) return@mapNotNull null
            val version = tag.removePrefix("reworked-v")
            val parsed = ReworkedVersion.parse(version) ?: return@mapNotNull null
            val productRevision = parsed.preview.size == 2 && parsed.preview[0] == "reworked" && parsed.preview[1].all(Char::isDigit)
            if (parsed <= running || (!previews && parsed.preview.isNotEmpty() && !productRevision)) return@mapNotNull null
            val assets = release["assets"]?.jsonArray.orEmpty().mapNotNull { value ->
                val asset = value as? JsonObject ?: return@mapNotNull null
                val name = asset.text("name") ?: return@mapNotNull null
                val url = asset.text("browser_download_url") ?: return@mapNotNull null
                val size = asset["size"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                if (asset.text("state") != "uploaded" || !validAssetUrl(url, tag, name) || size !in 1..MAX_UPDATE_BYTES) {
                    return@mapNotNull null
                }
                val digest = asset.text("digest")?.removePrefix("sha256:")?.lowercase()?.takeIf(::validDigest)
                ReworkedReleaseAsset(name, url, size, digest)
            }
            ReworkedRelease(
                tag, version, release.text("body").orEmpty(),
                assets.singleOrNull { it.name == "BitChordReworked-$version${format.suffix}" },
                assets.singleOrNull { it.name == "SHA256SUMS.txt" && it.size <= 1024 * 1024 },
            )
        }.maxByOrNull { ReworkedVersion.parse(it.version)!! }
    }

    fun validDigest(value: String): Boolean = value.matches(Regex("[0-9a-fA-F]{64}"))

    fun checksum(document: String, filename: String): String? = document.lineSequence().mapNotNull { line ->
        val match = Regex("^([0-9a-fA-F]{64})[ \\t]+\\*?(.+)$").matchEntire(line.trim()) ?: return@mapNotNull null
        if (match.groupValues[2] == filename) match.groupValues[1].lowercase() else null
    }.toList().singleOrNull()

    fun validAssetUrl(url: String, tag: String, filename: String): Boolean {
        if (!filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*"))) return false
        if (!tag.startsWith("reworked-v") || ReworkedVersion.parse(tag.removePrefix("reworked-v")) == null) return false
        return url == "https://github.com/$REWORKED_REPOSITORY/releases/download/$tag/$filename"
    }

    fun validDownloadRedirect(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }.getOrDefault(false)

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
}
