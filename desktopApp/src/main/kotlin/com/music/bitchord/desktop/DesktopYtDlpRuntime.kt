package com.music.bitchord.desktop

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Properties

/** Offline extraction of build-time verified binaries. No runtime downloads or self-updates. */
internal object DesktopYtDlpRuntime {
    data class Tools(val extractor: Path, val deno: Path)
    val tools: Tools by lazy { locate() }

    private fun locate(): Tools {
        val extractor = overridePath("bitchord.ytdlp.path", "BITCHORD_YTDLP")
        val deno = overridePath("bitchord.deno.path", "BITCHORD_DENO")
        if (extractor != null && deno != null) return Tools(extractor, deno)
        val manifest = Properties()
        val resource = javaClass.getResourceAsStream("/ytdlp/runtime.properties")
            ?: throw IOException("Bundled YouTube tools are missing. Reinstall BitChord, or set BITCHORD_YTDLP and BITCHORD_DENO to absolute executable paths.")
        resource.use { manifest.load(it) }
        val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        val platform = if (windows) "windows-x86_64" else "linux-x86_64"
        check(System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64")) {
            "The bundled YouTube tools require an x86-64 system"
        }
        check(manifest.getProperty("platform") == platform) { "Incorrect platform in the YouTube tools bundle" }
        val base = if (windows) {
            System.getenv("LOCALAPPDATA")?.takeIf { Path.of(it).isAbsolute }?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
        } else {
            System.getenv("XDG_CACHE_HOME")?.takeIf { Path.of(it).isAbsolute }?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".cache")
        }
        val root = base.resolve("bitchord").resolve("youtube-tools")
        return Tools(extractor ?: unpack("yt-dlp", manifest, root), deno ?: unpack("deno", manifest, root))
    }

    private fun overridePath(property: String, environment: String): Path? {
        val value = System.getProperty(property)?.takeIf(String::isNotBlank)
            ?: System.getenv(environment)?.takeIf(String::isNotBlank) ?: return null
        val path = Path.of(value)
        require(path.isAbsolute && Files.isRegularFile(path) && Files.isExecutable(path)) {
            "$environment must point to an absolute executable file"
        }
        return path
    }

    private fun unpack(tool: String, manifest: Properties, root: Path): Path {
        val name = manifest.getProperty("$tool.file") ?: error("Missing YouTube tool filename")
        val expected = manifest.getProperty("$tool.sha256") ?: error("Missing YouTube tool checksum")
        require(Regex("[a-zA-Z0-9.-]+").matches(name) && Regex("[a-f0-9]{64}").matches(expected)) {
            "Invalid YouTube tools manifest"
        }
        val directory = root.resolve(expected)
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory)) { "YouTube tools directory must not be a symlink" }
        DesktopYtDlpFiles.restrict(directory, directory = true)
        val target = directory.resolve(name)
        FileChannel.open(directory.resolve("install.lock"), CREATE, WRITE).use { channel ->
            val lock = channel.tryLock() ?: throw IOException("YouTube tools are being prepared by another BitChord process. Try again.")
            lock.use {
                if (Files.isRegularFile(target, NOFOLLOW_LINKS) && sha256(target) == expected) {
                    DesktopYtDlpFiles.restrict(target, executable = true)
                    return target
                }
                val temporary = Files.createTempFile(directory, "unpack-", ".tmp")
                try {
                    DesktopYtDlpFiles.restrict(temporary)
                    val input = javaClass.getResourceAsStream("/ytdlp/$name")
                        ?: throw IOException("A bundled YouTube executable is missing")
                    input.use { source ->
                        Files.newOutputStream(temporary).use { output -> source.copyTo(output) }
                    }
                    check(sha256(temporary) == expected) { "YouTube tool checksum verification failed" }
                    DesktopYtDlpFiles.restrict(temporary, executable = true)
                    try {
                        Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(temporary, target, REPLACE_EXISTING)
                    }
                    return target
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
        }
    }

    internal fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
