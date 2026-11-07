package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal enum class UpdateFormat(val suffix: String?, val guidance: String) {
    WINDOWS_SETUP("-windows-x64-setup.exe", "BitChord Updater opens in a separate window, waits for BitChord to close, runs the verified setup and automatically reopens the updated app. Complete the setup wizard and any Windows permission prompt. A required Windows reboot postpones relaunch."),
    WINDOWS_PORTABLE("-windows-x64-portable.zip", "BitChord Updater opens in a separate window, waits for BitChord to close, replaces this portable folder with the verified ZIP and automatically reopens the updated app. The original folder is kept as a backup."),
    APPIMAGE("-linux-x86_64.AppImage", "A separate Linux updater replaces this AppImage after BitChord closes and reopens the updated app. Its backup is deleted only after the new window is confirmed; an unconfirmed startup keeps the backup. Progress is shown when Zenity or KDialog is available."),
    DEB("-linux-amd64.deb", "Open the verified package with your system package installer. Authorization is handled by your package manager."),
    RPM("-linux-x86_64.rpm", "Open the verified package with your system package installer. Authorization is handled by your package manager."),
    ARCH(null, "Update through pacman or your AUR helper. BitChord will not replace package-managed files."),
    UNKNOWN(null, "Automatic installation is unavailable for this installation. See the release instructions."),
}

internal data class UpdateInstallation(val format: UpdateFormat, val appImage: Path? = null, val portableRoot: Path? = null) {
    companion object {
        fun detect(): UpdateInstallation {
            val command = System.getProperty("jpackage.app-path")
            val executable = command?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
            val observedPortableRoot = portableRootDirectory(executable)
            val portable = observedPortableRoot != null ||
                System.getProperty("bitchord.distribution") == "portable"
            val image = System.getenv("APPIMAGE")?.takeIf(String::isNotBlank)
            val owner = if (DesktopPlatform.isLinux && image == null && executable != null) ownerOf(executable) else ""
            val classified = classify(System.getProperty("os.name", ""), System.getProperty("os.arch", ""), image,
                portable, System.getProperty("bitchord.distribution") == "installed", owner)
            return if (classified.format == UpdateFormat.WINDOWS_PORTABLE) classified.copy(portableRoot = observedPortableRoot)
                else classified
        }

        internal fun portableRootDirectory(executable: Path?): Path? = executable?.let { path ->
            // The portable marker is inside the actual extracted app directory,
            // not the ZIP's parent and not an installer-owned directory.
            val parent = path.parent ?: return null
            val root = if (parent.fileName?.toString().equals("bin", true)) parent.parent else parent
            root?.toAbsolutePath()?.normalize()?.takeIf {
                Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(it) &&
                    Files.isRegularFile(it.resolve(".bitchord-portable"), java.nio.file.LinkOption.NOFOLLOW_LINKS)
            }
        }

        internal fun portableMarker(executable: Path?): Boolean = portableRootDirectory(executable) != null

        internal fun ownerOf(executable: Path, probe: (List<String>) -> Boolean = ::owns): String {
            val canonical = runCatching { executable.toRealPath() }.getOrDefault(executable)
            return listOf(listOf("pacman", "-Qqo"), listOf("dpkg-query", "-S"), listOf("rpm", "-qf"))
                .firstOrNull { args -> probe(args + canonical.toString()) }?.first().orEmpty()
        }

        private fun owns(arguments: List<String>): Boolean = runCatching {
            val process = ProcessBuilder(arguments).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS); false
            } else process.exitValue() == 0
        }.getOrDefault(false)

        internal fun classify(os: String, arch: String, image: String?, portable: Boolean, installed: Boolean, owner: String): UpdateInstallation {
            if (arch.lowercase() !in setOf("amd64", "x86_64", "x64")) return UpdateInstallation(UpdateFormat.UNKNOWN)
            if (os.startsWith("Windows", true)) return UpdateInstallation(when {
                portable -> UpdateFormat.WINDOWS_PORTABLE
                installed -> UpdateFormat.WINDOWS_SETUP
                else -> UpdateFormat.UNKNOWN
            })
            if (!os.contains("Linux", true)) return UpdateInstallation(UpdateFormat.UNKNOWN)
            if (!image.isNullOrBlank()) return runCatching { UpdateInstallation(UpdateFormat.APPIMAGE, Path.of(image).toAbsolutePath()) }
                .getOrDefault(UpdateInstallation(UpdateFormat.UNKNOWN))
            return UpdateInstallation(when(owner) { "pacman" -> UpdateFormat.ARCH; "dpkg-query" -> UpdateFormat.DEB; "rpm" -> UpdateFormat.RPM; else -> UpdateFormat.UNKNOWN })
        }
    }
}
