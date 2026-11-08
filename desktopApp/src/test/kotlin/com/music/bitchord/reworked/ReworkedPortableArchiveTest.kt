package com.music.bitchord.reworked

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ReworkedPortableArchiveTest {
    private val version = "1.9.2-reworked.1"

    @Test fun completePortablePackageIsStagedWithoutTouchingAnInstallation() = runBlocking {
        withDirectory { directory ->
            val archive = directory.resolve("portable.zip")
            archive(archive, validFiles())
            val staged = ReworkedPortableArchive.stage(archive, directory, version)
            assertEquals("BitChordReworked", staged.application.fileName.toString())
            assertEquals(4, staged.hashes.size)
            assertEquals(ReworkedUpdates.sha256(staged.application.resolve("BitChordReworked.exe")), staged.hashes["BitChordReworked.exe"])
            assertFalse(Files.exists(directory.resolve("BitChordReworked")), "Staging must not publish or replace the current application")
            ReworkedPortableArchive.removeStage(staged.directory)
            assertFalse(Files.exists(staged.directory))
        }
    }

    @Test fun escapingAndWindowsAliasPathsAreRejected() {
        listOf("../escape", "BitChordReworked/../escape", "BitChordReworked\\escape", "BitChordReworked/C:/escape",
            "OtherProduct/app.exe", "BitChordReworked/app/file.", "BitChordReworked/app/NUL.txt", "BitChordReworked/app//file")
            .forEach { assertFailsWith<IllegalArgumentException>(it) { ReworkedPortableArchive.requireSafeEntry(it) } }
    }

    @Test fun wrongVersionAndDuplicateCasePathsNeverBecomeAStage() = runBlocking {
        withDirectory { directory ->
            val wrong = validFiles().toMutableMap().apply {
                put("BitChordReworked/app/BitChordReworked.cfg", "java-options=-Dbitchord.product=reworked\njava-options=-Dbitchord.version=0.0.0".toByteArray())
            }
            val archive = directory.resolve("wrong.zip")
            archive(archive, wrong)
            assertFailsWith<IllegalArgumentException> { ReworkedPortableArchive.stage(archive, directory, version) }
            archive(directory.resolve("duplicate.zip"), validFiles() + ("BitChordReworked/APP/BitChordReworked.cfg" to byteArrayOf(1)))
            assertFailsWith<IllegalArgumentException> { ReworkedPortableArchive.stage(directory.resolve("duplicate.zip"), directory, version) }
            Files.list(directory).use { paths -> assertTrue(paths.noneMatch { it.fileName.toString().startsWith(".BitChordReworked-next-") }) }
        }
    }

    private fun validFiles(): Map<String, ByteArray> = mapOf(
        "BitChordReworked/BitChordReworked.exe" to byteArrayOf(0x4d, 0x5a, 1, 2),
        "BitChordReworked/runtime/bin/server/jvm.dll" to byteArrayOf(1, 2, 3),
        "BitChordReworked/app/BitChordReworked.cfg" to "java-options=-Dbitchord.product=reworked\njava-options=-Dbitchord.version=$version\n".toByteArray(),
        "BitChordReworked/reworked-portable.marker" to "BitChord Desktop Reworked $version\n".toByteArray(),
    )

    private fun archive(path: Path, files: Map<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip -> files.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }

    private suspend fun withDirectory(block: suspend (Path) -> Unit) {
        val directory = Files.createTempDirectory("reworked-portable-test-")
        try { block(directory) } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
