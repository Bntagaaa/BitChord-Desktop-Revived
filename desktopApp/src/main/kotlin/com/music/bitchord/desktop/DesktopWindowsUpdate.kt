package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A single external Windows updater owns setup/portable installation and relaunch. */
internal object DesktopWindowsUpdate {
    fun prepare(
        installation: UpdateInstallation,
        source: Path,
        hash: String,
        version: String,
        operation: Path,
        temporary: MutableList<Path>,
    ): DesktopPreparedUpdate {
        require(DesktopUpdateVersion.parse(version) != null) { "Invalid target update version." }
        val portable = installation.format == UpdateFormat.WINDOWS_PORTABLE
        require(portable || installation.format == UpdateFormat.WINDOWS_SETUP)
        val launcher = if (portable) {
            requireNotNull(installation.portableRoot).resolve("BitChord.exe")
        } else {
            System.getProperty("jpackage.app-path")?.let(Path::of)
                ?: error("Cannot find the installed BitChord launcher; automatic relaunch is unavailable.")
        }.toAbsolutePath().normalize()
        require(launcher.fileName.toString().equals("BitChord.exe", ignoreCase = true) &&
            Files.isRegularFile(launcher, NOFOLLOW_LINKS) && !Files.isSymbolicLink(launcher)) {
            "The original BitChord launcher is not a regular executable."
        }
        val root = requireNotNull(launcher.parent)
        require(Files.isRegularFile(root.resolve("app/BitChord.cfg"), NOFOLLOW_LINKS)) {
            "The original BitChord launcher configuration is missing."
        }
        require(!operation.toAbsolutePath().normalize().startsWith(root)) {
            "The external updater must not live inside the installation."
        }
        val payload = Files.createDirectory(operation.resolve("payload"))
        val packageFile = payload.resolve(if (portable) "verified-portable.zip" else "verified-setup.exe")
        temporary.add(packageFile)
        Files.copy(source, packageFile)
        require(DesktopUpdateDownloader.sha256(packageFile).equals(hash, ignoreCase = true)) {
            "Staged Windows update changed. Download it again."
        }
        val worker = payload.resolve("worker.ps1")
        resource(if (portable) "windows-portable-update.ps1" else "windows-update.ps1", worker)
        if (!portable) resource("windows-msi-source.ps1", payload.resolve("msi-source.ps1"))
        val host = operation.resolve("windows-updater-host.ps1")
        resource("windows-updater-host.ps1", host)
        val ancestor = launcherIdentity(launcher)
        val token = UUID.randomUUID().toString().replace("-", "")
        val request = operation.resolve("request.json")
        Files.writeString(request, buildJsonObject {
            put("schema", 1)
            put("mode", if (portable) "portable" else "setup")
            put("application", launcher.toString())
            put("version", version)
            put("expected", hash.lowercase())
            put("workerSha256", DesktopUpdateDownloader.sha256(worker))
            put("token", token)
            put("parentPid", ProcessHandle.current().pid())
            put("parentStartMillis", ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli())
            put("launcherPid", ancestor?.first ?: 0L)
            put("launcherStartMillis", ancestor?.second ?: 0L)
        }.toString())
        DesktopUpdateDiagnostics.log(operation,
            "stage=windows-host-prepared mode=${if (portable) "portable" else "setup"} application=$launcher target=$version launcher_pid=${ancestor?.first ?: 0L}")
        val windows = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows")
        return DesktopPreparedUpdate(operation, listOf(
            windows.resolve("System32/WindowsPowerShell/v1.0/powershell.exe").toString(),
            "-NoProfile", "-NonInteractive", "-STA", "-WindowStyle", "Hidden",
            "-ExecutionPolicy", "Bypass", "-File", host.toString(), "-Request", request.toString(),
        ), temporary)
    }

    /** jpackage can keep a native launcher parent around the JVM, for BOTH formats. */
    private fun launcherIdentity(launcher: Path): Pair<Long, Long>? {
        var parent = ProcessHandle.current().parent().orElse(null)
        while (parent != null) {
            val command = parent.info().command().orElse(null)
            val start = parent.info().startInstant().orElse(null)
            if (command != null && start != null && runCatching {
                    Path.of(command).toAbsolutePath().normalize().toString()
                        .equals(launcher.toString(), ignoreCase = true)
                }.getOrDefault(false)) return parent.pid() to start.toEpochMilli()
            parent = parent.parent().orElse(null)
        }
        return null
    }

    private fun resource(name: String, target: Path) {
        requireNotNull(javaClass.getResourceAsStream("/updater/$name")) {
            "This build is missing its bundled Windows updater: $name"
        }.use { Files.copy(it, target) }
    }
}
