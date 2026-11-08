package com.music.bitchord.reworked

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.zip.ZipFile

internal data class ReworkedPortableStage(val directory: Path, val application: Path, val hashes: Map<String, String>)

/** Exact package layout and bounded extraction before a portable process is asked to exit. */
internal object ReworkedPortableArchive {
    suspend fun stage(archive: Path, parent: Path, version: String): ReworkedPortableStage {
        require(ReworkedVersion.parse(version) != null)
        val staging = Files.createTempDirectory(parent, ".BitChordReworked-next-")
        try {
            ZipFile(archive.toFile()).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.isNotEmpty() && entries.size <= 100_000) { "The portable archive has an invalid number of entries" }
                val names = HashSet<String>()
                var total = 0L
                entries.forEach { entry ->
                    requireSafeEntry(entry.name)
                    require(names.add(entry.name.trimEnd('/').lowercase(Locale.ROOT))) { "The portable archive contains duplicate Windows paths" }
                    if (!entry.isDirectory) {
                        require(entry.size in 0..MAX_FILE_BYTES) { "A portable archive entry has an invalid size" }
                        total += entry.size
                        require(total <= MAX_EXTRACTED_BYTES) { "The portable archive exceeds the extraction size limit" }
                    }
                }
                require(Files.getFileStore(parent).usableSpace > total + 64L * 1024 * 1024) { "Not enough disk space to stage the portable update" }
                val buffer = ByteArray(64 * 1024)
                for (entry in entries) {
                    currentCoroutineContext().ensureActive()
                    val destination = staging.resolve(entry.name).normalize()
                    require(destination.startsWith(staging)) { "The portable archive contains an escaping path" }
                    if (entry.isDirectory) Files.createDirectories(destination)
                    else {
                        Files.createDirectories(destination.parent)
                        var written = 0L
                        zip.getInputStream(entry).use { input ->
                            Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW).use { output ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    written += read
                                    require(written <= entry.size && written <= MAX_FILE_BYTES) { "A portable archive entry exceeded its declared size" }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        require(written == entry.size) { "A portable archive entry was truncated" }
                    }
                }
            }
            val app = staging.resolve("BitChordReworked")
            val launcher = app.resolve("BitChordReworked.exe")
            val config = app.resolve("app/BitChordReworked.cfg")
            val marker = app.resolve("reworked-portable.marker")
            require(Files.isRegularFile(launcher) && Files.isRegularFile(config) && Files.isRegularFile(marker) &&
                Files.isRegularFile(app.resolve("runtime/bin/server/jvm.dll"))) { "The archive is not a complete standalone Reworked application" }
            val magic = Files.newInputStream(launcher).use { it.readNBytes(2) }
            require(magic.contentEquals(byteArrayOf(0x4d, 0x5a))) { "The portable launcher is not a Windows executable" }
            val configuration = Files.readString(config)
            require(configuration.lineSequence().any { it.trimEnd() == "java-options=-Dbitchord.product=reworked" } &&
                configuration.lineSequence().any { it.trimEnd() == "java-options=-Dbitchord.version=$version" }) {
                "The portable archive does not identify the requested Reworked version"
            }
            require(Files.readString(marker).trim() == "BitChord Desktop Reworked $version") { "The portable version marker does not match" }
            val hashes = linkedMapOf<String, String>()
            Files.walk(app).use { paths ->
                paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                    val relative = app.relativize(file).joinToString("/") { it.toString() }
                    hashes[relative] = ReworkedUpdates.sha256(file)
                }
            }
            return ReworkedPortableStage(staging, app, hashes)
        } catch (failure: Throwable) {
            removeStage(staging)
            throw failure
        }
    }

    internal fun requireSafeEntry(name: String) {
        require(name.startsWith("BitChordReworked/") && '\\' !in name && ':' !in name && '\u0000' !in name) { "The portable archive contains an unexpected path" }
        val parts = name.trimEnd('/').split('/')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." && !it.endsWith('.') && !it.endsWith(' ') }) { "The portable archive contains an unsafe Windows path" }
        require(parts.none { part ->
            val base = part.substringBefore('.').uppercase(Locale.ROOT)
            base in setOf("CON", "PRN", "AUX", "NUL") || base.matches(Regex("COM[0-9]|LPT[0-9]"))
        }) { "The portable archive contains a reserved Windows device name" }
    }

    fun removeStage(directory: Path) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.fileName.toString().startsWith(".BitChordReworked-next-")) return
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_EXTRACTED_BYTES = 8L * 1024 * 1024 * 1024
}
