package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** Prepares an external helper before requesting a clean exit. Never edits preferences or caches. */
internal object DesktopUpdateInstaller {
    fun canInstall(installation: UpdateInstallation): Boolean = when(installation.format) {
        UpdateFormat.WINDOWS_SETUP -> true
        UpdateFormat.APPIMAGE -> installation.appImage?.let {
            !Files.isSymbolicLink(it) && Files.isRegularFile(it) && Files.isWritable(it) && Files.isWritable(it.parent)
        } == true
        else -> false
    }

    fun prepare(installation: UpdateInstallation, file: Path, verifiedHash: String): () -> Unit {
        require(canInstall(installation)) { "This installation needs a manual update." }
        require(file.fileName.toString().endsWith(requireNotNull(installation.format.suffix))) { "Package does not match this installation." }
        require(DesktopUpdateDownloader.sha256(file) == verifiedHash) { "Verified package changed. Download again." }
        val helperDirectory = Files.createTempDirectory("bitchord-update-helper-")
        if (installation.format == UpdateFormat.WINDOWS_SETUP) {
            val helper = helperDirectory.resolve("install.ps1")
            copyHelper("windows-update.ps1", helper)
            val windows = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows")
            return { ProcessBuilder(windows.resolve("System32/WindowsPowerShell/v1.0/powershell.exe").toString(),
                "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", helper.toString(),
                ProcessHandle.current().pid().toString(), file.toString(), verifiedHash).start(); Unit }
        }
        val current = requireNotNull(installation.appImage)
        val staged = Files.createTempFile(current.parent, ".bitchord-update-", ".AppImage")
        try {
            Files.copy(file, staged, StandardCopyOption.REPLACE_EXISTING)
            Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(current) + PosixFilePermission.OWNER_EXECUTE)
            require(DesktopUpdateDownloader.sha256(staged) == verifiedHash)
            val backup = current.resolveSibling(current.fileName.toString() + ".previous-" + System.currentTimeMillis())
            val helper = helperDirectory.resolve("install.sh")
            copyHelper("appimage-update.sh", helper)
            return { ProcessBuilder("/bin/sh", helper.toString(), ProcessHandle.current().pid().toString(),
                current.toString(), staged.toString(), backup.toString(), verifiedHash)
                .redirectOutput(helperDirectory.resolve("install.log").toFile()).redirectErrorStream(true).start(); Unit }
        } catch (failure: Throwable) { Files.deleteIfExists(staged); throw failure }
    }

    private fun copyHelper(name: String, target: Path) {
        requireNotNull(javaClass.getResourceAsStream("/updater/$name")).use { Files.copy(it, target) }
    }
}
