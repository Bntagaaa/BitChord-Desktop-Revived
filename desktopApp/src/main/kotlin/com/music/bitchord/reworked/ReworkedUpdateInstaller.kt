package com.music.bitchord.reworked

import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.UUID

/** Platform handoff written for Reworked. No installer ever writes to a Revived product path. */
internal object ReworkedUpdateInstaller {
    fun target(): Path? {
        if (System.getProperty("bitchord.product") != "reworked") return null
        val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        val raw = if (windows) System.getProperty("jpackage.app-path") ?: ProcessHandle.current().info().command().orElse(null)
            else System.getenv("APPIMAGE")
        val path = raw?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() } ?: return null
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        return if (windows) {
            path.takeIf {
                it.fileName.toString().equals("BitChordReworked.exe", ignoreCase = true) &&
                    runCatching { Files.readString(it.parent.resolve("app/BitChordReworked.cfg")) }
                        .getOrDefault("").contains("-Dbitchord.product=reworked")
            }
        } else {
            path.takeIf {
                it.fileName.toString().startsWith("BitChordReworked") && it.fileName.toString().endsWith(".AppImage") &&
                    Files.isWritable(it.parent)
            }
        }
    }

    suspend fun prepare(download: Path, expectedSha256: String, version: String) {
        val destination = target() ?: error("Install this verified download manually from its folder.")
        check(ReworkedVersion.parse(version) != null) { "Invalid update version." }
        check(ReworkedUpdateMetadata.validDigest(expectedSha256)) { "Missing update checksum." }
        check(!Files.isSymbolicLink(download) && Files.isRegularFile(download, LinkOption.NOFOLLOW_LINKS)) { "The downloaded package is no longer available." }
        check(ReworkedUpdates.sha256(download).equals(expectedSha256, ignoreCase = true)) { "The package changed after verification." }
        val root = DesktopStore.state.resolve("updates")
        Files.createDirectories(root)
        check(!Files.isSymbolicLink(root)) { "The update state directory cannot be a symbolic link." }
        val operation = Files.createTempDirectory(root, "operation-")
        val token = UUID.randomUUID().toString()
        val parent = ProcessHandle.current()
        val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        val oldChecksum = ReworkedUpdates.sha256(destination)
        var staged: Path? = null
        var portableStage: ReworkedPortableStage? = null
        var helperProcess: Process? = null
        var handedOff = false
        try {
            val process = if (windows) {
                val portable = ReworkedUpdates.portableDirectory()
                if (portable != null) {
                    check(destination.parent.toRealPath() == portable.toRealPath()) { "The running launcher is not inside this portable installation." }
                    check(Files.isWritable(portable.parent)) { "The portable application's parent folder is not writable." }
                    portableStage = ReworkedPortableArchive.stage(download, portable.parent, version)
                    check(ReworkedUpdates.sha256(download).equals(expectedSha256, true)) { "The portable archive changed while it was being staged." }
                    DesktopStore.writeText(operation, "staged-files.json", JsonObject(portableStage!!.hashes.mapValues { JsonPrimitive(it.value) }).toString())
                }
                val metadata = buildJsonObject {
                    put("target", destination.toString())
                    put("payload", download.toAbsolutePath().toString())
                    put("sha256", expectedSha256.lowercase())
                    put("oldSha256", oldChecksum)
                    put("version", version)
                    put("pid", parent.pid())
                    put("started", parent.info().startInstant().orElseThrow().toEpochMilli())
                    put("token", token)
                    put("operation", operation.toString())
                    if (portableStage != null) {
                        put("portableRoot", portable!!.toRealPath().toString())
                        put("stagedRoot", portableStage!!.application.toString())
                        put("stagingDirectory", portableStage!!.directory.toString())
                        put("backupRoot", portable.parent.resolve(".BitChordReworked-previous-$token").toString())
                        put("failedRoot", portable.parent.resolve(".BitChordReworked-failed-$token").toString())
                    }
                }
                DesktopStore.writeText(operation, "operation.json", metadata.toString())
                val script = resource(if (portableStage == null) "reworked/update-windows.ps1" else "reworked/update-portable.ps1")
                val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
                ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-EncodedCommand", encoded)
                    .directory(operation.toFile())
                    .redirectErrorStream(true).redirectOutput(operation.resolve("install.log").toFile())
                    .apply { environment()["BITCHORD_REWORKED_UPDATE_OPERATION"] = operation.toString() }.start()
            } else {
                check(Files.getFileStore(destination.parent).usableSpace > Files.size(download) + 64L * 1024 * 1024) {
                    "There is not enough free space beside the AppImage to stage this update."
                }
                val candidate = destination.parent.resolve(".BitChordReworked-next-$token.AppImage")
                val backup = destination.parent.resolve(destination.fileName.toString() + ".reworked-previous-$token")
                check(!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && !Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) { "Update staging names are already in use." }
                staged = candidate
                Files.copy(download, candidate)
                Files.setPosixFilePermissions(candidate, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
                check(ReworkedUpdates.sha256(candidate) == expectedSha256.lowercase()) { "The staged AppImage did not match its checksum." }
                val helper = operation.resolve("worker.sh")
                Files.writeString(helper, resource("reworked/update-appimage.sh"), StandardOpenOption.CREATE_NEW)
                ProcessBuilder("/bin/sh", helper.toString(), destination.toString(), candidate.toString(), backup.toString(),
                    oldChecksum, expectedSha256.lowercase(), parent.pid().toString(), linuxStartTicks(parent.pid()),
                    operation.toString(), token, version)
                    .directory(operation.toFile()).redirectErrorStream(true)
                    .redirectOutput(operation.resolve("install.log").toFile()).start()
            }
            helperProcess = process
            var accepted = false
            for (attempt in 0 until 1800) {
                if (Files.isRegularFile(operation.resolve("accepted"), LinkOption.NOFOLLOW_LINKS)) {
                    accepted = true
                    break
                }
                check(process.isAlive) { "The updater could not start. Details: ${operation.resolve("install.log")}" }
                delay(100)
            }
            check(accepted) { "The updater did not become ready. Your running session was kept open." }
            // The helper now owns its private staged file and waits for this exact process to exit.
            staged = null
            portableStage = null
            handedOff = true
        } finally {
            if (!handedOff) helperProcess?.takeIf { it.isAlive }?.destroyForcibly()
            staged?.let { runCatching { Files.deleteIfExists(it) } }
            portableStage?.let { runCatching { ReworkedPortableArchive.removeStage(it.directory) } }
        }
    }

    /** Call after the replacement's main native window is shown. The helper keeps its backup. */
    fun confirmStartup() {
        val path = System.getenv("BITCHORD_REWORKED_UPDATE_ACK")?.let { runCatching { Path.of(it) }.getOrNull() } ?: return
        val token = System.getenv("BITCHORD_REWORKED_UPDATE_TOKEN") ?: return
        val version = System.getenv("BITCHORD_REWORKED_UPDATE_VERSION") ?: return
        if (version != ReworkedUpdates.currentVersion || runCatching { UUID.fromString(token) }.isFailure) return
        runCatching {
            val expectedRoot = DesktopStore.state.resolve("updates").toRealPath()
            val directory = path.parent.toRealPath()
            check(directory.parent == expectedRoot && directory.fileName.toString().startsWith("operation-"))
            check(path.fileName.toString() == "ready" && !Files.isSymbolicLink(path))
            Files.writeString(path, "$token\n$version\n", StandardOpenOption.CREATE_NEW)
        }
    }

    private fun resource(name: String): String = javaClass.classLoader.getResourceAsStream(name)?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("The update helper is not included in this build.")

    private fun linuxStartTicks(pid: Long): String = Files.readString(Path.of("/proc/$pid/stat"))
        .substringAfterLast(") ").split(' ').filter(String::isNotEmpty)[19]
}
