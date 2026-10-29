package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal enum class UpdateFormat(val suffix: String?, val guidance: String) {
    WINDOWS_SETUP("-windows-x64-setup.exe", "Installs with the Windows setup wizard. Windows may ask for permission."),
    WINDOWS_PORTABLE("-windows-x64-portable.zip", "Extract the verified ZIP into a new folder, then launch it. Keep your current folder as a backup."),
    APPIMAGE("-linux-x86_64.AppImage", "Replaces this AppImage after exit and keeps the previous file as a backup."),
    DEB("-linux-amd64.deb", "Open the verified package with your system package installer. Authorization is handled by your package manager."),
    RPM("-linux-x86_64.rpm", "Open the verified package with your system package installer. Authorization is handled by your package manager."),
    ARCH(null, "Update through pacman or your AUR helper. BitChord will not replace package-managed files."),
    UNKNOWN(null, "Automatic installation is unavailable for this installation. See the release instructions."),
}

internal data class UpdateInstallation(val format: UpdateFormat, val appImage: Path? = null) {
    companion object {
        fun detect(): UpdateInstallation {
            val command = System.getProperty("jpackage.app-path")
            val executable = command?.let { runCatching { Path.of(it) }.getOrNull() }
            val portable = executable?.parent?.resolve(".bitchord-portable")?.let(Files::exists) == true ||
                System.getProperty("bitchord.distribution") == "portable"
            val image = System.getenv("APPIMAGE")?.takeIf(String::isNotBlank)
            val owner = if (DesktopPlatform.isLinux && image == null && executable != null) {
                listOf(listOf("pacman", "-Qqo"), listOf("dpkg-query", "-S"), listOf("rpm", "-qf"))
                    .firstOrNull { args -> runCatching {
                        val process = ProcessBuilder(args + executable.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                        if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); false } else process.exitValue() == 0
                    }.getOrDefault(false) }?.first().orEmpty()
            } else ""
            return classify(System.getProperty("os.name", ""), System.getProperty("os.arch", ""), image,
                portable, System.getProperty("bitchord.distribution") == "installed", owner)
        }

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
