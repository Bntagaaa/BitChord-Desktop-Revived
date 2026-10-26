package com.music.bitchord.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Checks BitChord Desktop Revived's published stable GitHub release once at startup.
 * Download opens a browser; no updater process, package-manager command, or
 * installation runs inside the application.
 */
internal object DesktopUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        /** The matching package for this platform, or null if the release has none. */
        val downloadUrl: String?,
        val notes: String?,
    )

    internal enum class PackagePlatform { WINDOWS_X64, LINUX_X64, ARCH_X64, UNSUPPORTED }

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/Bntagaaa/BitChord-Desktop-Revived/releases/latest"

    val currentVersion: String = System.getProperty("bitchord.version") ?: "1.8-beta1"

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }

    /**
     * OS identification is intentionally independent of the desktop environment:
     * CachyOS KDE, GNOME, Niri and other shells all read the same /etc/os-release.
     * Unknown architectures never receive a mismatched x86_64 installer.
     */
    internal fun targetPlatform(osName: String, osRelease: String, architecture: String): PackagePlatform {
        if (architecture.lowercase() !in setOf("amd64", "x86_64", "x64")) return PackagePlatform.UNSUPPORTED
        return when {
            osName.startsWith("Windows", ignoreCase = true) -> PackagePlatform.WINDOWS_X64
            osName.contains("Linux", ignoreCase = true) && isArchFamily(osRelease) -> PackagePlatform.ARCH_X64
            osName.contains("Linux", ignoreCase = true) -> PackagePlatform.LINUX_X64
            else -> PackagePlatform.UNSUPPORTED
        }
    }

    /** Match os-release ID/ID_LIKE tokens rather than arbitrary product-name substrings. */
    private fun isArchFamily(osRelease: String): Boolean {
        val identifiers = osRelease.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("ID=") || it.startsWith("ID_LIKE=") }
            .flatMap { it.substringAfter('=').trim().trim('"', '\'').lowercase().splitToSequence(' ', '\t') }
            .filter(String::isNotBlank)
            .toSet()
        return identifiers.any { it in setOf("arch", "archlinux", "cachyos", "manjaro", "endeavouros", "garuda", "artix") }
    }

    val packagePlatform: PackagePlatform by lazy {
        targetPlatform(
            osName = System.getProperty("os.name").orEmpty(),
            osRelease = runCatching { File("/etc/os-release").readText() }.getOrDefault(""),
            architecture = System.getProperty("os.arch").orEmpty(),
        )
    }

    suspend fun check(): UpdateInfo? = runCatching {
        val response = http.get(LATEST_RELEASE_URL) {
            header("Accept", "application/vnd.github+json")
            header("User-Agent", "BitChord-Desktop-Revived-updater")
        }
        if (!response.status.isSuccess()) return@runCatching null
        val release = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: return@runCatching null
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val latest = tag.removePrefix("v")
        if (!isNewer(latest, currentVersion)) return@runCatching null
        UpdateInfo(
            version = latest,
            releaseUrl = url,
            downloadUrl = installerUrl(release),
            notes = release["body"]?.jsonPrimitive?.contentOrNull,
        )
    }.getOrNull() // Offline, invalid or rate-limited responses must not interrupt playback.

    /**
     * Names are produced by release.yml (and its Arch companion, once merged).
     * Arch packages take precedence on Arch/CachyOS; NEVER fall through to a
     * Debian installer or AppImage when the native package has not been released.
     * The dialog then links to the release page for a manual choice.
     */
    internal fun installerUrl(
        release: JsonObject,
        platform: PackagePlatform = packagePlatform,
    ): String? {
        val suffixes = when (platform) {
            PackagePlatform.WINDOWS_X64 -> listOf("-windows-x64-setup.exe")
            PackagePlatform.LINUX_X64 -> listOf("-linux-x86_64.AppImage", "-linux-amd64.deb")
            PackagePlatform.ARCH_X64 -> listOf("-x86_64.pkg.tar.zst")
            PackagePlatform.UNSUPPORTED -> emptyList()
        }

        val assets = release["assets"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
        for (suffix in suffixes) {
            val asset = assets.firstOrNull { candidate ->
                val name = candidate["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val expectedPackage = platform != PackagePlatform.ARCH_X64 ||
                    name.startsWith("bitchord-desktop-revived-bin-", ignoreCase = true)
                expectedPackage && name.endsWith(suffix, ignoreCase = true) &&
                    candidate["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
            }
            asset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull?.let { return it }
        }
        return null
    }

    private data class ParsedVersion(val parts: List<Int>, val preRelease: Boolean)

    private fun parseVersion(raw: String): ParsedVersion? {
        val base = raw.substringBefore('-')
        val components = base.split('.')
        if (components.isEmpty() || components.any { it.isEmpty() || it.any { ch -> !ch.isDigit() } }) {
            return null
        }
        val parts = components.map { it.toIntOrNull() ?: return null }
        return ParsedVersion(parts, '-' in raw)
    }

    /** Compare numeric components, treating a test/beta as older than its stable release. */
    internal fun isNewer(latest: String, current: String): Boolean {
        val left = parseVersion(latest) ?: return false
        val right = parseVersion(current) ?: return false
        for (i in 0 until maxOf(left.parts.size, right.parts.size)) {
            val a = left.parts.getOrElse(i) { 0 }
            val b = right.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return right.preRelease && !left.preRelease
    }
}
