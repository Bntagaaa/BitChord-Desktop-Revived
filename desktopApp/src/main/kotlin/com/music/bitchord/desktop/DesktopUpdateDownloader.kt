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
        require(DesktopUpdateChecker.assetBelongsToRelease(release.url, asset.url)) { "Package does not belong to this release." }
        require(asset.name !in setOf(".", "..") && Regex("[A-Za-z0-9._-]+").matches(asset.name) && asset.url.endsWith("/" + asset.name)) { "Unsafe package filename." }
        val hash = asset.sha256 ?: release.manifest?.let { manifest ->
            require(DesktopUpdateChecker.assetBelongsToRelease(release.url, manifest.url)) { "Checksum manifest does not belong to this release." }
            val bytes = open(manifest.url).use { it.readNBytes(262145) }
            require(bytes.size <= 262144) { "Checksum manifest is too large." }
            manifestHash(bytes.toString(Charsets.UTF_8), asset.name)
        } ?: error("This release has no published SHA-256 checksum. Open the release instructions instead.")
        require(hash.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid published SHA-256." }
        require(asset.size in 1..4L * 1024 * 1024 * 1024) { "Invalid package size (maximum 4 GiB)." }
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory)) { "Update download directory cannot be a symbolic link." }
        val operation = Files.createTempDirectory(directory, "download-")
        val part = operation.resolve(asset.name + ".part")
        val ready = operation.resolve(asset.name)
        try {
            require(Files.getFileStore(directory).usableSpace - 16L * 1024 * 1024 > asset.size) { "Insufficient free space for this update." }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            open(asset.url).use { input ->
                Files.newOutputStream(part, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        currentCoroutineContext().ensureActive()
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
            Files.move(part, ready, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            ready
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(part); Files.deleteIfExists(ready); Files.deleteIfExists(operation) }
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
        fun sha256(path: Path): String = Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
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
        require(allowedUpdateDownloadUri(uri)) { "Untrusted update redirect." }
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

internal fun allowedUpdateDownloadUri(uri: URI): Boolean = uri.scheme == "https" && uri.port == -1 &&
    uri.userInfo == null && uri.fragment == null && when (uri.host) {
        "github.com" -> DesktopUpdateChecker.officialAssetUrl(uri.toString())
        "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> true
        else -> false
    }
