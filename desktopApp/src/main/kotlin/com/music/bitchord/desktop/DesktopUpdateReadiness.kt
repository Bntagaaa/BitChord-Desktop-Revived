package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.channels.FileChannel

/** Called after a visible, non-minimized Compose window has passed two frame callbacks. */
internal object DesktopUpdateReadiness {
    fun acknowledge(environment: Map<String, String> = System.getenv(), version: String = DesktopUpdateChecker.currentVersion): Boolean {
        val destination = environment["BITCHORD_UPDATE_READY_FILE"] ?: return false
        val token = environment["BITCHORD_UPDATE_TOKEN"] ?: return acknowledgeLegacy(destination, version)
        if (!token.matches(Regex("[a-f0-9]{32}"))) return false
        val ready = runCatching { Path.of(destination) }.getOrNull() ?: return false
        if (!ready.isAbsolute || ready.fileName.toString() != "window-ready" || ready.parent == null) return false
        val operation = ready.parent
        var temporary: Path? = null
        return try {
            require(!Files.isSymbolicLink(operation) && Files.isDirectory(operation, NOFOLLOW_LINKS))
            require(!Files.exists(ready, NOFOLLOW_LINKS)) { "Readiness marker already exists." }
            require(!Files.isSymbolicLink(operation.resolve("request.token")))
            require(Files.size(operation.resolve("request.token")) == 32L && Files.readString(operation.resolve("request.token")) == token) { "Readiness operation does not match." }
            require(environment["BITCHORD_UPDATE_EXPECTED_VERSION"] == version) { "Unexpected application version." }
            val ticks = requireNotNull(DesktopUpdateProcess.linuxStartTicks())
            temporary = Files.createTempFile(operation, ".ready-", ".tmp")
            Files.writeString(temporary, "token=$token\npid=${ProcessHandle.current().pid()}\nstart_ticks=$ticks\nversion=$version\nstate=gui-ready\n")
            FileChannel.open(temporary, WRITE).use { it.force(true) }
            Files.move(temporary, ready, ATOMIC_MOVE)
            DesktopUpdateDiagnostics.log(operation, "stage=gui-ready pid=${ProcessHandle.current().pid()} version=$version")
            true
        } catch (failure: Exception) {
            DesktopUpdateDiagnostics.log(operation, "stage=readiness-write-failed error=${failure.javaClass.simpleName}")
            DesktopTrackLog.log("updater: window is visible but readiness acknowledgment failed (${failure.javaClass.simpleName})")
            false
        } finally { temporary?.let { runCatching { Files.deleteIfExists(it) } } }
    }

    /** Bridge the already-installed 7eaa helper. Invalid modern tokens never downgrade here. */
    private fun acknowledgeLegacy(destination: String, version: String): Boolean {
        val ready = runCatching { Path.of(destination) }.getOrNull() ?: return false
        val operation = ready.parent ?: return false
        if (!ready.isAbsolute || !operation.fileName.toString().startsWith("bitchord-update-helper-") ||
            !ready.fileName.toString().matches(Regex("window-ready-[a-fA-F0-9-]{36}"))) return false
        return try {
            require(!Files.isSymbolicLink(operation) && Files.isDirectory(operation, NOFOLLOW_LINKS))
            require(Files.getOwner(operation) == Files.getOwner(Path.of("/proc/self")))
            val permissions = Files.getPosixFilePermissions(operation)
            require(permissions.none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") })
            // Never overwrite a stale marker/symlink, even on the compatibility path.
            Files.writeString(ready, "window-ready\npid=${ProcessHandle.current().pid()}\nversion=$version\n",
                java.nio.file.StandardOpenOption.CREATE_NEW, WRITE, NOFOLLOW_LINKS)
            DesktopTrackLog.log("updater: visible window acknowledged the legacy installed helper")
            true
        } catch (failure: Exception) {
            DesktopTrackLog.log("updater: legacy readiness acknowledgment failed (${failure.javaClass.simpleName})")
            false
        }
    }
}
