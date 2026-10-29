package com.music.bitchord.desktop

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Disposable, checksummed range spans. No URLs, headers, or account identifiers on disk. */
internal class DesktopAudioCache(
    private val directory: Path,
    private val limitBytes: Long = 256L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Span(val bytes: ByteArray, val total: Long)
    init { runCatching { if (Files.isDirectory(directory)) trim() } }

    @Synchronized fun read(key: String, start: Long): Span? = runCatching {
        val file = path(key, start)
        if (!Files.isRegularFile(file) || Files.size(file) !in 57L..(MAX_SPAN + 56L)) return null
        val span = DataInputStream(Files.newInputStream(file)).use { input ->
            check(input.readInt() == MAGIC)
            check(input.readLong() > now())
            val total = input.readLong(); val size = input.readInt()
            check(size in 1..MAX_SPAN && total >= start + size)
            val hash = ByteArray(32).also(input::readFully)
            val bytes = ByteArray(size).also(input::readFully)
            check(input.read() == -1 && MessageDigest.isEqual(hash, sha(bytes)))
            Span(bytes, total)
        }
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(now()))
        span
    }.getOrElse { runCatching { Files.deleteIfExists(path(key, start)) }; null }

    @Synchronized fun write(key: String, start: Long, bytes: ByteArray, total: Long, expiresAt: Long) {
        if (bytes.isEmpty() || bytes.size > MAX_SPAN || total < start + bytes.size || expiresAt <= now()) return
        runCatching {
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, ".span-", ".tmp")
            try {
                DataOutputStream(Files.newOutputStream(temporary)).use { out ->
                    out.writeInt(MAGIC); out.writeLong(expiresAt); out.writeLong(total); out.writeInt(bytes.size)
                    out.write(sha(bytes)); out.write(bytes)
                }
                try {
                    Files.move(temporary, path(key, start), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary, path(key, start), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally { Files.deleteIfExists(temporary) }
            Files.setLastModifiedTime(path(key, start), java.nio.file.attribute.FileTime.fromMillis(now()))
            trim()
        } // A full/read-only cache never prevents playback.
    }

    @Synchronized fun clear() {
        runCatching { Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } } }
    }

    private fun trim() {
        // Include orphaned atomic-write files after abrupt process exit. The entry cap also bounds
        // filesystem/index overhead for unusually short responses, not just media payload bytes.
        val files = Files.list(directory).use { entries -> entries.filter {
            val name = it.fileName.toString()
            name.endsWith(".span") || (name.startsWith(".span-") && name.endsWith(".tmp"))
        }.toList() }
        var total = files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        var count = files.size
        for (file in files.sortedBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }) {
            if (total <= limitBytes && count <= 1024) break
            val size = runCatching { Files.size(file) }.getOrDefault(0L)
            if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) { total -= size; count-- }
        }
    }

    private fun path(key: String, start: Long): Path {
        require(key.matches(Regex("[a-f0-9]{64}")) && start >= 0)
        return directory.resolve("$key-$start.span")
    }

    companion object {
        const val MAX_SPAN = 512 * 1024
        private const val MAGIC = 0x42434131
        fun identity(value: String): String = sha(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun sha(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        val shared by lazy {
            val root = if (DesktopPlatform.isWindows) {
                System.getenv("LOCALAPPDATA")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
            } else {
                System.getenv("XDG_CACHE_HOME")?.takeIf { Path.of(it).isAbsolute }?.let(Path::of)
                    ?: Path.of(System.getProperty("user.home"), ".cache")
            }
            DesktopAudioCache(root.resolve("bitchord/audio-ranges-v1"))
        }
    }
}
