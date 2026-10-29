package com.music.bitchord.desktop

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

/** Owns preparation until an external helper is armed. Closing before exit aborts safely. */
internal class DesktopPreparedUpdate(
    val operation: Path,
    private val command: List<String>,
    private val temporary: List<Path>,
) : AutoCloseable {
    private var process: Process? = null
    private var handedOff = false

    suspend fun launch() {
        val builder = ProcessBuilder(command).directory(operation.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(java.io.File(if (DesktopPlatform.isWindows) "NUL" else "/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(operation.resolve("install.log").toFile())).redirectErrorStream(true)
        DesktopUpdateProcess.cleanEnvironment(builder.environment())
        process = builder.start()
        repeat(100) {
            currentCoroutineContext().ensureActive()
            check(process!!.isAlive) { "Update helper did not start. See updater logs: $operation" }
            if (Files.isRegularFile(operation.resolve("armed"), NOFOLLOW_LINKS)) {
                DesktopUpdateDiagnostics.log(operation, "stage=helper-armed helper_pid=${process!!.pid()}")
                return
            }
            delay(50)
        }
        error("Update helper did not acknowledge preparation. See updater logs: $operation")
    }

    /** The normal application exit is now committed; never called on a failed save/shutdown. */
    fun handoff() { handedOff = true }

    fun abort() { handedOff = false; close() }

    override fun close() {
        if (handedOff) return
        runCatching { Files.writeString(operation.resolve("abort"), "abort") }
        temporary.forEach { runCatching { Files.deleteIfExists(it) } }
        DesktopUpdateDiagnostics.log(operation, "stage=preparation-aborted")
        runCatching { Files.writeString(operation.resolve("completed"), "aborted") }
    }
}

/** Bundled helpers, private persistent state, and same-filesystem staging. No user-data edits. */
internal object DesktopUpdateInstaller {
    fun canInstall(installation: UpdateInstallation): Boolean = when (installation.format) {
        UpdateFormat.WINDOWS_SETUP -> true // Interactive installer handoff, not an unattended upgrade.
        UpdateFormat.APPIMAGE -> installation.appImage?.let {
            it.isAbsolute && !Files.isSymbolicLink(it) && Files.isRegularFile(it, NOFOLLOW_LINKS) &&
                Files.isWritable(it) && Files.isWritable(it.parent) && setsid() != null
        } == true
        else -> false
    }

    fun prepare(installation: UpdateInstallation, file: Path, verifiedHash: String, targetVersion: String = DesktopUpdateChecker.currentVersion): DesktopPreparedUpdate {
        require(canInstall(installation)) { "This installation needs a manual update." }
        require(verifiedHash.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid SHA-256." }
        require(!Files.isSymbolicLink(file) && Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Update must be a regular file." }
        require(file.fileName.toString().endsWith(requireNotNull(installation.format.suffix))) { "Package does not match this installation." }
        require(DesktopUpdateDownloader.sha256(file).equals(verifiedHash, true)) { "Verified package changed. Download again." }
        require(validExecutable(file, installation.format)) { "The verified file is not the expected executable package." }
        val operation = DesktopUpdateDiagnostics.createOperation()
        val temporary = mutableListOf<Path>()
        try {
            DesktopUpdateDiagnostics.log(operation, "stage=preparing installed_version=${DesktopUpdateChecker.currentVersion} target_version=$targetVersion format=${installation.format} parent_pid=${ProcessHandle.current().pid()}")
            if (installation.format == UpdateFormat.WINDOWS_SETUP) {
                // Copy into our private operation directory, not a mutable shared cache location.
                val installer = operation.resolve("verified-setup.exe")
                Files.copy(file, installer)
                require(DesktopUpdateDownloader.sha256(installer).equals(verifiedHash, true))
                val helper = operation.resolve("install.ps1")
                copyHelper("windows-update.ps1", helper)
                val windows = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows")
                return DesktopPreparedUpdate(operation, listOf(windows.resolve("System32/WindowsPowerShell/v1.0/powershell.exe").toString(),
                    "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", helper.toString(),
                    "-ParentPid", ProcessHandle.current().pid().toString(), "-Installer", installer.toString(),
                    "-Expected", verifiedHash.lowercase(), "-Operation", operation.toString(),
                    "-ParentStartMillis", (ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()).toString()), listOf(installer))
            }
            val current = requireNotNull(installation.appImage).normalize()
            require('\n' !in current.toString() && '\r' !in current.toString()) { "Use manual replacement for a path containing line breaks." }
            require(validExecutable(current, UpdateFormat.APPIMAGE)) { "Current installation is not an AppImage." }
            val available = Files.getFileStore(current.parent).usableSpace
            require(available > 2 * Files.size(current) + Files.size(file) + 32L * 1024 * 1024) { "Not enough free space to stage the AppImage and retain a rollback backup." }
            val originalHash = DesktopUpdateDownloader.sha256(current)
            val staged = Files.createTempFile(current.parent, ".bitchord-update-", ".AppImage")
            temporary.add(staged)
            Files.copy(file, staged, REPLACE_EXISTING)
            val permissions = Files.getPosixFilePermissions(current) - setOf(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE) +
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            Files.setPosixFilePermissions(staged, permissions)
            FileChannel.open(staged, WRITE).use { it.force(true) }
            require(DesktopUpdateDownloader.sha256(staged).equals(verifiedHash, true))
            val token = UUID.randomUUID().toString().replace("-", "")
            Files.writeString(operation.resolve("request.token"), token)
            val backup = current.resolveSibling(current.fileName.toString() + ".previous-" + token)
            val helper = operation.resolve("install.sh")
            copyHelper("appimage-update.sh", helper)
            DesktopUpdateDiagnostics.log(operation, "stage=verified current=$current staged=$staged backup=$backup sha256=${verifiedHash.lowercase()}")
            return DesktopPreparedUpdate(operation, listOf(requireNotNull(setsid()), "/bin/sh", helper.toString(),
                ProcessHandle.current().pid().toString(), current.toString(), staged.toString(), backup.toString(), verifiedHash.lowercase(),
                operation.resolve("window-ready").toString(), token, targetVersion, requireNotNull(DesktopUpdateProcess.linuxStartTicks()), originalHash), temporary)
        } catch (failure: Throwable) {
            temporary.forEach { runCatching { Files.deleteIfExists(it) } }
            DesktopUpdateDiagnostics.log(operation, "stage=preparation-failed error=${failure.javaClass.simpleName}")
            runCatching { Files.writeString(operation.resolve("completed"), "preparation-failed") }
            throw failure
        }
    }

    internal fun validExecutable(file: Path, format: UpdateFormat): Boolean = runCatching {
        val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(12) }
        when (format) {
            UpdateFormat.APPIMAGE -> bytes.size >= 11 && bytes.take(4) == listOf(0x7f.toByte(), 69.toByte(), 76.toByte(), 70.toByte()) &&
                bytes[8] == 65.toByte() && bytes[9] == 73.toByte() && bytes[10] == 2.toByte()
            UpdateFormat.WINDOWS_SETUP -> bytes.size >= 2 && bytes[0] == 77.toByte() && bytes[1] == 90.toByte()
            else -> false
        }
    }.getOrDefault(false)

    private fun setsid(): String? = listOf("/usr/bin/setsid", "/bin/setsid").firstOrNull { Files.isExecutable(Path.of(it)) }
    private fun copyHelper(name: String, target: Path) {
        requireNotNull(javaClass.getResourceAsStream("/updater/$name")).use { Files.copy(it, target) }
    }
}
