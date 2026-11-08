package com.music.bitchord.reworked

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale

internal enum class ReworkedUpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, INSTALLING, ERROR }
internal data class ReworkedUpdateStatus(
    val phase: ReworkedUpdatePhase = ReworkedUpdatePhase.IDLE,
    val release: ReworkedRelease? = null,
    val downloaded: Path? = null,
    val digest: String? = null,
    val progress: Float = 0f,
    val message: String = "",
)

/** A fresh updater with a separate product namespace and explicit, verified installation handoff. */
internal object ReworkedUpdates {
    val currentVersion: String = System.getProperty("bitchord.version", "1.9.1-reworked.1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(ReworkedUpdateStatus())
    val status: StateFlow<ReworkedUpdateStatus> = _status
    private var activeJob: Job? = null
    private val _previews = MutableStateFlow(DesktopStore.preferences.getBoolean("update.previews", false))
    val previews: StateFlow<Boolean> = _previews

    fun setPreviews(value: Boolean) {
        if (_status.value.phase in setOf(ReworkedUpdatePhase.DOWNLOADING, ReworkedUpdatePhase.INSTALLING)) return
        DesktopStore.preferences.putBoolean("update.previews", value)
        _previews.value = value
        _status.value = ReworkedUpdateStatus()
    }

    fun format(): ReworkedUpdateFormat = when {
        System.getProperty("os.name").contains("Windows", ignoreCase = true) && portableDirectory() != null -> ReworkedUpdateFormat.WINDOWS_PORTABLE
        System.getProperty("os.name").contains("Windows", ignoreCase = true) -> ReworkedUpdateFormat.WINDOWS_SETUP
        else -> ReworkedUpdateFormat.LINUX_APPIMAGE
    }

    fun check() = startWork {
        _status.value = ReworkedUpdateStatus(phase = ReworkedUpdatePhase.CHECKING)
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        check(arch in setOf("amd64", "x86_64")) { "Automatic downloads currently support Windows/Linux x86_64. See official releases for your system." }
        val json = readText("https://api.github.com/repos/$REWORKED_REPOSITORY/releases?per_page=100", metadata = true)
        val release = ReworkedUpdateMetadata.select(json, currentVersion, format(), _previews.value)
        _status.value = if (release == null) {
            ReworkedUpdateStatus(phase = ReworkedUpdatePhase.CURRENT, message = "No newer published Reworked release is available for this channel.")
        } else {
            ReworkedUpdateStatus(phase = ReworkedUpdatePhase.AVAILABLE, release = release,
                message = if (release.asset == null) "This release has no matching package. Open its release page for installation instructions." else "")
        }
    }

    fun download() {
        val release = _status.value.release ?: return
        val asset = release.asset ?: return
        startWork {
            _status.value = ReworkedUpdateStatus(ReworkedUpdatePhase.DOWNLOADING, release)
            val expected = asset.sha256 ?: release.checksums?.let { checksums ->
                ReworkedUpdateMetadata.checksum(readText(checksums.url), asset.name)
            }
            check(expected != null && ReworkedUpdateMetadata.validDigest(expected)) {
                "The release has no valid SHA-256 checksum for this package. Open the release page to inspect it."
            }
            val directory = createUpdateDirectory()
            val partial = directory.resolve("download.part")
            val target = directory.resolve(asset.name)
            var completed = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                val connection = openConnection(asset.url, metadata = false)
                try {
                    val declared = connection.contentLengthLong
                    check(declared < 0 || declared == asset.size) { "The server returned a different package size." }
                    connection.inputStream.use { input ->
                        Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var lastProgress = -1
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                check(received <= asset.size && received <= MAX_UPDATE_BYTES) { "The package exceeded its advertised size." }
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                val percent = ((received * 100L) / asset.size).toInt()
                                if (percent != lastProgress) {
                                    lastProgress = percent
                                    _status.value = _status.value.copy(progress = received.toFloat() / asset.size)
                                }
                            }
                        }
                    }
                } finally { connection.disconnect() }
                check(received == asset.size) { "The download was incomplete." }
                val actual = hex(digest.digest())
                check(actual.equals(expected, ignoreCase = true)) { "The downloaded package did not match its SHA-256 checksum." }
                validateHeader(partial, format())
                try { Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(partial, target) }
                completed = true
                _status.value = ReworkedUpdateStatus(ReworkedUpdatePhase.READY, release, target, actual, 1f,
                    when {
                        !installAvailable() -> "Package verified. Automatic replacement is unavailable for this installation; use your package manager or the release-page instructions."
                        format() == ReworkedUpdateFormat.WINDOWS_PORTABLE -> "Package verified. Update and restart stages the complete portable application and retains your current folder as a recovery backup."
                        else -> "Package verified and ready. Your current session is kept running until installation is prepared."
                    })
            } finally {
                Files.deleteIfExists(partial)
                if (!completed) runCatching { Files.delete(directory) }
            }
        }
    }

    fun cancel() {
        if (_status.value.phase == ReworkedUpdatePhase.INSTALLING) return
        activeJob?.cancel()
    }

    fun install(onExit: () -> Unit) {
        val ready = _status.value
        val file = ready.downloaded ?: return
        val digest = ready.digest ?: return
        val release = ready.release ?: return
        startWork {
            _status.value = ready.copy(phase = ReworkedUpdatePhase.INSTALLING, message = "Preparing the independent updater…")
            ReworkedUpdateInstaller.prepare(file, digest, release.version)
            withContext(Dispatchers.Main) { onExit() }
        }
    }

    fun openReleases() = openUrl(_status.value.release?.releaseUrl ?: REWORKED_RELEASES)
    fun revealDownload() { _status.value.downloaded?.parent?.toFile()?.let { directory -> runCatching { Desktop.getDesktop().open(directory) } } }
    fun installAvailable(): Boolean = ReworkedUpdateInstaller.target() != null

    private fun startWork(work: suspend () -> Unit) {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch {
            try { work() }
            catch (cancelled: CancellationException) {
                _status.value = ReworkedUpdateStatus(phase = ReworkedUpdatePhase.IDLE, message = "Cancelled.")
                throw cancelled
            } catch (failure: Exception) {
                _status.value = _status.value.copy(phase = ReworkedUpdatePhase.ERROR,
                    message = failure.message ?: "The update could not be completed.")
            }
        }
    }

    internal fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        hex(digest.digest())
    }

    internal fun validateHeader(path: Path, format: ReworkedUpdateFormat) {
        val header = Files.newInputStream(path).use { it.readNBytes(4) }
        val valid = when (format) {
            ReworkedUpdateFormat.LINUX_APPIMAGE -> header.contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
            ReworkedUpdateFormat.WINDOWS_SETUP -> header.size == 4 && header[0] == 0x4d.toByte() && header[1] == 0x5a.toByte()
            ReworkedUpdateFormat.WINDOWS_PORTABLE -> header.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        }
        check(valid) { "The download is not the expected executable/archive format." }
    }

    private fun createUpdateDirectory(): Path {
        val parent = DesktopStore.cache.resolve("updates")
        Files.createDirectories(parent)
        check(!Files.isSymbolicLink(parent)) { "The update cache cannot be a symbolic link." }
        check(Files.getFileStore(parent).usableSpace > (_status.value.release?.asset?.size ?: 0L) + 64 * 1024 * 1024) { "There is not enough free disk space for this download." }
        return Files.createTempDirectory(parent, "package-")
    }

    internal fun portableDirectory(): Path? = System.getProperty("bitchord.portable.dir")?.let { runCatching { Path.of(it).toRealPath() }.getOrNull() }
        ?.takeIf { Files.isRegularFile(it.resolve("reworked-portable.marker"), LinkOption.NOFOLLOW_LINKS) }
        ?: System.getProperty("jpackage.app-path")?.let { runCatching { Path.of(it).toRealPath().parent }.getOrNull() }
            ?.takeIf { Files.isRegularFile(it.resolve("reworked-portable.marker"), LinkOption.NOFOLLOW_LINKS) }
        ?: ProcessHandle.current().info().command().orElse(null)?.let { runCatching { Path.of(it).toRealPath().parent }.getOrNull() }
            ?.takeIf { Files.isRegularFile(it.resolve("reworked-portable.marker"), LinkOption.NOFOLLOW_LINKS) }

    private fun readText(url: String, metadata: Boolean = false): String {
        val connection = openConnection(url, metadata)
        return try {
            connection.inputStream.use {
                val bytes = it.readNBytes(4 * 1024 * 1024 + 1)
                check(bytes.size <= 4 * 1024 * 1024) { "Update metadata exceeded its size limit." }
                bytes.toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }

    private fun openConnection(url: String, metadata: Boolean): HttpURLConnection {
        var next = url
        repeat(6) { hop ->
            val allowed = if (metadata) next == "https://api.github.com/repos/$REWORKED_REPOSITORY/releases?per_page=100"
                else ReworkedUpdateMetadata.validDownloadRedirect(next)
            check(allowed) { "Update request left its allowed HTTPS destination." }
            val connection = URI(next).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", if (metadata) "application/vnd.github+json" else "application/octet-stream")
            connection.setRequestProperty("User-Agent", "BitChordReworked/$currentVersion")
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                check(!metadata && location != null && hop < 5) { "Unexpected update redirect." }
                next = URI(next).resolve(location).toString()
            } else {
                if (code != 200) { connection.disconnect(); error("Update server returned HTTP $code. Try again later or open releases.") }
                return connection
            }
        }
        error("Too many update redirects.")
    }

    private fun openUrl(url: String) { runCatching { Desktop.getDesktop().browse(URI(url)) } }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
