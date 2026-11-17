package com.music.bitchord.desktop

import kotlinx.coroutines.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class DesktopYtDlpProcessTest {
    private fun fixture(mode: String, vararg args: String): List<String> {
        val suffix = if (System.getProperty("os.name").contains("Windows")) ".exe" else ""
        val java = Path.of(System.getProperty("java.home"), "bin", "java$suffix").toString()
        val classes = Path.of(YtDlpProcessFixture::class.java.protectionDomain.codeSource.location.toURI()).toString()
        return listOf(java, "-cp", classes, YtDlpProcessFixture::class.java.name, mode) + args
    }

    @Test fun commandUsesArgumentsNotShellAndPreservesPaths() {
        val exe = Path.of("tools with spaces", "yt-dlp.exe")
        val deno = Path.of("tools with spaces", "deno.exe")
        val command = DesktopYtDlpCommand.build(exe, deno, "abcdefghijk", Int.MAX_VALUE)
        assertEquals(exe.toString(), command.first())
        assertTrue("deno:$deno" in command)
        assertTrue("--ignore-config" in command)
        assertTrue("--no-plugin-dirs" in command)
        assertTrue("--no-remote-components" in command)
        assertTrue("--no-cookies-from-browser" in command)
        assertTrue("--no-cookies" in command)
        assertTrue("--skip-download" in command)
        assertEquals("--", command[command.lastIndex - 1])
        assertEquals("https://music.youtube.com/watch?v=abcdefghijk", command.last())
    }

    @Test fun lowQualityAppliesToBothCodecBranches() {
        val command = DesktopYtDlpCommand.build(Path.of("yt-dlp"), Path.of("deno"), "abcdefghijk", 64)
        val selector = command[command.indexOf("--format") + 1]
        assertEquals(2, Regex("abr<=64").findAll(selector).count())
        assertEquals(2, Regex("protocol=https").findAll(selector).count())
    }

    @Test fun invalidIdsAndQualityAreRejected() {
        for (id in listOf("--exec=evil", "abc;pwd", "https://example.com", "short")) {
            assertFailsWith<IllegalArgumentException> {
                DesktopYtDlpCommand.build(Path.of("yt-dlp"), Path.of("deno"), id, 64)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopYtDlpCommand.build(Path.of("yt-dlp"), Path.of("deno"), "abcdefghijk", 0)
        }
    }

    @Test fun cookieConversionPreservesValuesAndHostScope() {
        val jar = DesktopYtDlpFiles.cookieJar("SAPISID=a=b; __Host-ID=x; SAPISID=replaced==")
        assertTrue(jar.startsWith("# Netscape HTTP Cookie File\n"))
        assertTrue(".youtube.com\tTRUE\t/\tTRUE\t0\tSAPISID\treplaced==" in jar)
        assertTrue("music.youtube.com\tFALSE\t/\tTRUE\t0\t__Host-ID\tx" in jar)
        assertFalse("a=b" in jar)
        for (invalid in listOf("ID=x\n.injected", "ID=x\tbad", "bad name=x", "missingEquals")) {
            assertFailsWith<IllegalArgumentException> { DesktopYtDlpFiles.cookieJar(invalid) }
        }
    }

    @Test fun cookiesArePrivateAndDeleted() {
        val directory = DesktopYtDlpFiles.privateDirectory()
        try {
            val cookie = DesktopYtDlpFiles.writeCookies(directory, "SAPISID=secret")
            if (Files.getFileAttributeView(cookie, PosixFileAttributeView::class.java) != null) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(cookie))
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory))
            }
            assertTrue(Files.readString(cookie).contains("SAPISID\tsecret"))
        } finally { DesktopYtDlpFiles.removeDirectory(directory) }
        assertFalse(Files.exists(directory))
    }

    @Test fun subprocessReturnsStdout() = runBlocking {
        val directory = DesktopYtDlpFiles.privateDirectory()
        try {
            assertEquals("{\"ok\":true}", DesktopYtDlpProcess.run(fixture("success"), directory))
        } finally { DesktopYtDlpFiles.removeDirectory(directory) }
    }

    @Test fun bothPipesAreDrainedWithoutDeadlock() = runBlocking {
        val directory = DesktopYtDlpFiles.privateDirectory()
        try {
            assertEquals(48 * 1024, DesktopYtDlpProcess.run(fixture("both-pipes"), directory).length)
        } finally { DesktopYtDlpFiles.removeDirectory(directory) }
    }

    @Test fun excessiveOutputFailsBoundedly() {
        runBlocking {
            val directory = DesktopYtDlpFiles.privateDirectory()
            try {
                assertFailsWith<IOException> {
                    DesktopYtDlpProcess.run(fixture("oversize"), directory, outputLimit = 1024)
                }
            } finally { DesktopYtDlpFiles.removeDirectory(directory) }
        }
    }

    @Test fun stderrNeverLeaksSessionOrSignedUrl() = runBlocking {
        val directory = DesktopYtDlpFiles.privateDirectory()
        try {
            val error = assertFailsWith<IOException> { DesktopYtDlpProcess.run(fixture("fail"), directory) }
            assertFalse(error.message.orEmpty().contains("secret"))
            assertFalse(error.message.orEmpty().contains("googlevideo"))
        } finally { DesktopYtDlpFiles.removeDirectory(directory) }
    }

    @Test fun timeoutKillsChild() = runBlocking {
        val directory = DesktopYtDlpFiles.privateDirectory()
        try {
            val pidFile = directory.resolve("pid")
            val error = assertFailsWith<IOException> {
                DesktopYtDlpProcess.run(fixture("sleep", pidFile.toString()), directory, timeoutMillis = 2000)
            }
            assertTrue(error.message.orEmpty().contains("timed out"))
            val pid = Files.readString(pidFile).toLong()
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally { DesktopYtDlpFiles.removeDirectory(directory) }
    }

    @Test fun cancellationKillsChildAndPropagates() = runBlocking {
        val directory = DesktopYtDlpFiles.privateDirectory()
        try {
            val pidFile = directory.resolve("pid")
            val job = async { DesktopYtDlpProcess.run(fixture("sleep", pidFile.toString()), directory) }
            withTimeout(10000) { while (!Files.exists(pidFile)) delay(20) }
            val pid = Files.readString(pidFile).toLong()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally { DesktopYtDlpFiles.removeDirectory(directory) }
    }
}
