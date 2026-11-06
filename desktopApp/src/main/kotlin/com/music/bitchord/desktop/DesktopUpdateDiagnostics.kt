package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/** Updater-owned state only. No environment dumps, account data or application stdout. */
internal object DesktopUpdateDiagnostics {
    fun directory(environment: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home"), windows: Boolean = DesktopPlatform.isWindows): Path {
        val configured = environment[if (windows) "LOCALAPPDATA" else "XDG_STATE_HOME"]
            ?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf { it.isAbsolute }
        return (configured ?: if (windows) Path.of(home, "AppData", "Local") else Path.of(home, ".local", "state"))
            .let { base -> if (windows) base.resolve("BitChordUpdater").resolve("operations") else base.resolve("bitchord").resolve("updater") }
    }

    /** A case-insensitive MSI installation root must never contain a live updater log. */
    internal fun ensureOutsideWindowsInstall(root: Path, localAppData: Path) {
        val installation = localAppData.toAbsolutePath().normalize().resolve("BitChord")
        val candidate = root.toAbsolutePath().normalize()
        fun under(path: Path, parent: Path): Boolean {
            val parts = (0 until path.nameCount).map { path.getName(it).toString() }
            val prefixes = (0 until parent.nameCount).map { parent.getName(it).toString() }
            return parts.size >= prefixes.size && prefixes.indices.all { parts[it].equals(prefixes[it], ignoreCase = true) } &&
                path.root.toString().equals(parent.root.toString(), ignoreCase = true)
        }
        require(!under(candidate, installation)) { "Updater operation directory must not be inside the BitChord installation." }
        if (Files.exists(root)) {
            val resolved = root.toRealPath()
            val installed = if (Files.exists(installation)) installation.toRealPath() else installation
            require(!under(resolved, installed)) { "Updater operation directory resolves inside the BitChord installation." }
        }
    }

    fun createOperation(root: Path = directory()): Path {
        if (DesktopPlatform.isWindows) {
            val local = System.getenv("LOCALAPPDATA")?.let { runCatching { Path.of(it) }.getOrNull() }
                ?.takeIf { it.isAbsolute } ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
            ensureOutsideWindowsInstall(root, local)
        }
        Files.createDirectories(root)
        if (DesktopPlatform.isWindows) {
            val local = System.getenv("LOCALAPPDATA")?.let { runCatching { Path.of(it) }.getOrNull() }
                ?.takeIf { it.isAbsolute } ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
            ensureOutsideWindowsInstall(root, local)
        }
        require(!Files.isSymbolicLink(root) && Files.isDirectory(root, NOFOLLOW_LINKS)) { "Updater state directory is not a regular directory." }
        val operation = if (DesktopPlatform.isLinux) Files.createTempDirectory(root, "operation-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            else Files.createTempDirectory(root, "operation-")
        // Keep twenty completed operations. Retire abandoned helpers after thirty days only
        // when their PID/start identity is no longer alive. Never traverse foreign directories.
        Files.list(root).use { entries ->
            val owned = entries.filter { it != operation && it.fileName.toString().startsWith("operation-") &&
                !Files.isSymbolicLink(it) && Files.isDirectory(it, NOFOLLOW_LINKS) && !active(it) }
                .sorted(compareByDescending<Path> { Files.getLastModifiedTime(it).toMillis() }).toList()
            val completed = owned.filter { Files.isRegularFile(it.resolve("completed"), NOFOLLOW_LINKS) }
            val abandoned = owned.filter { !Files.exists(it.resolve("completed"), NOFOLLOW_LINKS) &&
                System.currentTimeMillis() - Files.getLastModifiedTime(it).toMillis() > 30L * 24 * 60 * 60 * 1000 }
            (completed.drop(19) + abandoned).forEach { old ->
                runCatching {
                    Files.list(old).use { files -> files.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.forEach(Files::deleteIfExists) }
                    Files.deleteIfExists(old)
                }
            }
        }
        return operation
    }

    private fun active(operation: Path): Boolean = runCatching {
        val identity = operation.resolve("helper.identity")
        if (!Files.exists(identity, NOFOLLOW_LINKS)) return false
        if (Files.isSymbolicLink(identity) || Files.size(identity) > 1024) return true
        val fields = Files.readAllLines(identity).associate { it.substringBefore('=') to it.substringAfter('=') }
        val pid = fields["pid"]?.toLongOrNull() ?: return true
        if (DesktopPlatform.isLinux) DesktopUpdateProcess.linuxStartTicks(pid) == fields["start_ticks"] &&
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        else ProcessHandle.of(pid).filter { it.isAlive }.flatMap { it.info().startInstant() }
            .map { it.toEpochMilli().toString() == fields["start_millis"] }.orElse(false)
    }.getOrDefault(true)

    fun log(operation: Path, message: String) {
        runCatching { Files.writeString(operation.resolve("install.log"), "${Instant.now()} $message\n", CREATE, APPEND) }
    }
}

internal object DesktopUpdateProcess {
    fun linuxStartTicks(pid: Long = ProcessHandle.current().pid()): String? = runCatching {
        Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(") ").trim().split(Regex("\\s+")).let {
            if (it[0] in setOf("Z", "X")) null else it[19]
        }
    }.getOrNull()

    fun cleanEnvironment(environment: MutableMap<String, String>) {
        listOf("APPIMAGE", "APPDIR", "OWD", "ARGV0", "LD_LIBRARY_PATH", "LD_PRELOAD", "LD_AUDIT",
            "BITCHORD_UPDATE_READY_FILE", "BITCHORD_UPDATE_TOKEN", "BITCHORD_UPDATE_EXPECTED_VERSION").forEach(environment::remove)
    }
}
