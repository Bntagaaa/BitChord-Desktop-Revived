package com.music.bitchord.reworked

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.prefs.Preferences

/** A new, independent desktop profile. Reading the old Revived profile requires an explicit import. */
internal object DesktopStore {
    private val userDirectory get() = Path.of(System.getProperty("user.home"))
    private val windows get() = System.getProperty("os.name").contains("Windows", ignoreCase = true)
    val root: Path by lazy {
        if (OfflineSmoke.active) checkNotNull(OfflineSmoke.profile).resolve("data")
        else if (windows) environment("LOCALAPPDATA", userDirectory.resolve("AppData/Local")).resolve("BitChordReworked")
        else environment("XDG_DATA_HOME", userDirectory.resolve(".local/share")).resolve("bitchord-reworked")
    }
    val cache: Path by lazy {
        if (OfflineSmoke.active) checkNotNull(OfflineSmoke.profile).resolve("cache")
        else if (windows) root.resolve("cache")
        else environment("XDG_CACHE_HOME", userDirectory.resolve(".cache")).resolve("bitchord-reworked")
    }
    val state: Path by lazy {
        if (OfflineSmoke.active) checkNotNull(OfflineSmoke.profile).resolve("state")
        else if (windows) root.resolve("state")
        else environment("XDG_STATE_HOME", userDirectory.resolve(".local/state")).resolve("bitchord-reworked")
    }
    val preferences: Preferences by lazy {
        Preferences.userRoot().node(System.getProperty("bitchord.preferencesNode", "com.music.bitchord.reworked"))
    }

    @Synchronized
    fun readText(name: String): String? = readText(root, name)

    @Synchronized
    fun writeText(name: String, value: String) = writeText(root, name, value)

    @Synchronized
    fun delete(name: String) { Files.deleteIfExists(resolveName(root, name)) }

    internal fun resolveName(directory: Path, name: String): Path {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) && name != "." && name != "..") {
            "Invalid profile file name"
        }
        return directory.resolve(name)
    }

    internal fun readText(directory: Path, name: String): String? {
        val path = resolveName(directory, name)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Invalid profile file" }
        require(Files.size(path) <= MAX_PROFILE_BYTES) { "Profile file is too large" }
        return Files.newInputStream(path).use { input ->
            val bytes = input.readNBytes(MAX_PROFILE_BYTES + 1)
            require(bytes.size <= MAX_PROFILE_BYTES) { "Profile file is too large" }
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    internal fun writeText(directory: Path, name: String, value: String) {
        val destination = resolveName(directory, name)
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_PROFILE_BYTES) { "Profile data is too large" }
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(destination)) { "Profile destination is a symbolic link" }
        val temporary = Files.createTempFile(directory, ".$name-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun environment(name: String, fallback: Path): Path = System.getenv(name)?.takeIf(String::isNotBlank)
        ?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf(Path::isAbsolute) ?: fallback

    private const val MAX_PROFILE_BYTES = 16 * 1024 * 1024
}
