package com.music.bitchord.desktop

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

internal class DesktopUpdateDownloader(
    private val open: (String) -> InputStream = ::openUpdateStream,
) {
    suspend fun download(release: DesktopUpdateChecker.Release, directory: Path, progress: (Long, Long) -> Unit): Path = withContext(Dispatchers.IO) {
        val asset = requireNotNull(release.asset) { "No compatible package is available." }
        require(DesktopUpdateChecker.officialAssetUrl(asset.url))
        require(Regex("[A-Za-z0-9._-]+").matches(asset.name) && asset.url.endsWith("/" + asset.name)) { "Unsafe package filename." }
        val hash = asset.sha256 ?: release.manifest?.let { manifest ->
            require(DesktopUpdateChecker.officialAssetUrl(manifest.url))
            val bytes = open(manifest.url).use { it.readNBytes(262145) }
            require(bytes.size <= 262144) { "Checksum manifest is too large." }
            manifestHash(bytes.toString(Charsets.UTF_8), asset.name)
        } ?: error("This release has no trusted SHA-256 checksum. Open the release instructions instead.")
        Files.createDirectories(directory)
        val part = directory.resolve(asset.name + ".part")
        val ready = directory.resolve(asset.name)
        try {
            require(asset.size > 0 && Files.getFileStore(directory).usableSpace > asset.size + 16 * 1024 * 1024) { "Insufficient free space for this update." }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            open(asset.url).use { input ->
                Files.newOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        received += n
                        require(received <= asset.size) { "Package exceeds the published size." }
                        output.write(buffer, 0, n); digest.update(buffer, 0, n)
                        progress(received, asset.size)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            require(received == asset.size) { "Download was interrupted. Please retry." }
            progress(received, asset.size)
            require(digest.digest().joinToString("") { "%02x".format(it) }.equals(hash, true)) { "SHA-256 verification failed. The package was discarded." }
            Files.move(part, ready, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            ready
        } catch (failure: Throwable) {
            Files.deleteIfExists(part); Files.deleteIfExists(ready)
            throw failure
        }
    }

    companion object {
        internal fun manifestHash(text: String, name: String): String? {
            val matches = text.lineSequence().mapNotNull { line ->
                Regex("^([a-fA-F0-9]{64}) [ *](.+)$").matchEntire(line)?.let {
                    if (it.groupValues[2] == name) it.groupValues[1] else null
                }
            }.toList()
            require(matches.size <= 1) { "Ambiguous checksum manifest." }
            return matches.singleOrNull()
        }
        fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Bound redirects to GitHub's HTTPS release CDN; never forward credentials or use shell URLs. */
private fun openUpdateStream(address: String): InputStream {
    var uri = URI(address)
    repeat(6) {
        require(uri.scheme == "https" && uri.port == -1 && uri.userInfo == null &&
            uri.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "Untrusted update redirect." }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000; connection.readTimeout = 5_000
        connection.setRequestProperty("User-Agent", "BitChord-Desktop-Revived-updater")
        val status = connection.responseCode
        if (status in setOf(301,302,303,307,308)) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            require(location != null) { "Missing download redirect." }
            uri = uri.resolve(location)
        } else {
            if (status != 200) { connection.disconnect(); error("Download service returned HTTP $status.") }
            return object : java.io.FilterInputStream(connection.inputStream) {
                override fun close() { try { super.close() } finally { connection.disconnect() } }
            }
        }
    }
    error("Too many download redirects.")
}
