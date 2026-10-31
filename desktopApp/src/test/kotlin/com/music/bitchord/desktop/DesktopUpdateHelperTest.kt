package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.test.*

/** Synthetic process/protocol tests; real packaged GUI validation lives in packaging/tests. */
internal class UpdaterHelperFixture : AutoCloseable {
    val directory: Path = Files.createTempDirectory("updater path with spaces ")
    val operation: Path = Files.createDirectory(directory.resolve("operation"))
    val current: Path = directory.resolve("current.AppImage")
    val staged: Path = directory.resolve("staged.AppImage")
    val backup: Path = directory.resolve("previous.AppImage")
    val ready: Path = operation.resolve("window-ready")
    val token = "a".repeat(32)
    val original = "#!/bin/sh\nprintf old > \"\$0.old-launched\"\nexit 0\n"
    private val helper = directory.resolve("helper.sh")
    private var process: Process? = null
    val acknowledge = """
        ticks=${'$'}(awk '{print ${'$'}22}' /proc/${'$'}${'$'}/stat)
        printf 'token=%s\npid=%s\nstart_ticks=%s\nversion=%s\nstate=gui-ready\n' "${'$'}BITCHORD_UPDATE_TOKEN" "${'$'}${'$'}" "${'$'}ticks" "${'$'}BITCHORD_UPDATE_EXPECTED_VERSION" > "${'$'}BITCHORD_UPDATE_READY_FILE"
    """.trimIndent()
    init {
        Files.writeString(current, original); current.toFile().setExecutable(true)
        Files.writeString(operation.resolve("request.token"), token)
        javaClass.getResourceAsStream("/updater/appimage-update.sh")!!.use { Files.copy(it, helper) }
    }
    fun candidate(body: String) { Files.writeString(staged, "#!/bin/sh\n$body\n"); staged.toFile().setExecutable(true) }
    fun start(parent: Long = 2147483647L, hash: String = DesktopUpdateDownloader.sha256(staged), polls: Int = 40, environment: Map<String, String> = emptyMap()): Process {
        val builder = ProcessBuilder("/usr/bin/setsid", "/bin/sh", helper.toString(), parent.toString(), current.toString(), staged.toString(), backup.toString(),
            hash, ready.toString(), token, "1.8.5", DesktopUpdateProcess.linuxStartTicks(parent) ?: "0", DesktopUpdateDownloader.sha256(current), "40", polls.toString(), "0.05")
            .redirectErrorStream(true).redirectOutput(operation.resolve("install.log").toFile())
        builder.environment().putAll(environment)
        return builder.start().also { process = it }
    }
    fun run(hash: String = DesktopUpdateDownloader.sha256(staged), polls: Int = 40, environment: Map<String, String> = emptyMap()): Int {
        val helper = start(hash = hash, polls = polls, environment = environment)
        assertTrue(helper.waitFor(10, TimeUnit.SECONDS), "Helper timed out: ${log()}")
        return helper.exitValue()
    }
    fun log(): String = Files.readString(operation.resolve("install.log"))
    override fun close() {
        // Only terminate this fixture's candidate session; never kill an unrelated process.
        val launched = Regex("candidate-started runtime_pid=(\\d+)").find(runCatching { log() }.getOrDefault(""))?.groupValues?.get(1)?.toLong()
        if (launched != null) {
            ProcessHandle.allProcesses().use { handles -> handles.filter { handle ->
                runCatching { Files.readString(Path.of("/proc/${handle.pid()}/stat")).substringAfterLast(") ").split(' ')[3] == launched.toString() }.getOrDefault(false)
            }.forEach { it.destroy() } }
        }
        process?.let { if (it.isAlive) { it.destroy(); it.waitFor(2, TimeUnit.SECONDS) } }
        directory.toFile().deleteRecursively()
    }
}

class DesktopUpdateHelperTest {
    @Test fun launcherExitDoesNotMeanItsSurvivingSessionFailed() {
        if (!DesktopPlatform.isLinux) return
        UpdaterHelperFixture().use { f ->
            f.candidate("sleep 20 &\nexit 0")
            assertEquals(2, f.run(polls = 8))
            assertTrue(Files.exists(f.backup))
            assertTrue(f.log().contains("candidate still running"))
            assertFalse(Files.exists(Path.of("${f.current}.old-launched")))
        }
    }
    @Test fun actualInstallerLaunchDetachesAndCanAbortBeforeParentExit() = runBlocking {
        if (!DesktopPlatform.isLinux) return@runBlocking
        val dir = Files.createTempDirectory("updater-detachment")
        var prepared: DesktopPreparedUpdate? = null
        try {
            val header = byteArrayOf(0x7f, 69, 76, 70, 2, 1, 1, 0, 65, 73, 2, 0)
            val current = Files.write(dir.resolve("current.AppImage"), header)
            val file = Files.write(dir.resolve("BitChord-1.8.5-linux-x86_64.AppImage"), header)
            prepared = DesktopUpdateInstaller.prepare(UpdateInstallation(UpdateFormat.APPIMAGE, current), file, DesktopUpdateDownloader.sha256(file), "1.8.5")
            prepared.launch()
            val owner = Files.readAllLines(Path.of("$current.update-lock/owner"))
            val helper = owner.first { it.startsWith("pid=") }.substringAfter('=').toLong()
            fun session(pid: Long) = Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(") ").split(' ')[3]
            assertEquals(helper.toString(), session(helper))
            assertNotEquals(session(ProcessHandle.current().pid()), session(helper))
            assertContentEquals(header, Files.readAllBytes(current))
            prepared.abort()
            withTimeout(5000) { while (ProcessHandle.of(helper).map { it.isAlive }.orElse(false)) delay(20) }
            assertContentEquals(header, Files.readAllBytes(current))
            assertTrue(Files.readString(prepared.operation.resolve("install.log")).contains("aborted-before-shutdown"))
        } finally { prepared?.abort(); dir.toFile().deleteRecursively() }
    }
    @Test fun missingAcknowledgmentDoesNotKillOrRollbackRunningCandidate() {
        if (!DesktopPlatform.isLinux) return
        UpdaterHelperFixture().use { f ->
            f.candidate("sleep 20")
            val newHash = DesktopUpdateDownloader.sha256(f.staged)
            assertEquals(2, f.run(polls = 5))
            assertEquals(newHash, DesktopUpdateDownloader.sha256(f.current))
            assertTrue(Files.exists(f.backup)); assertFalse(Files.exists(Path.of("${f.current}.old-launched")))
            assertTrue(f.log().contains("gui-confirmation-pending"))
        }
    }
    @Test fun slowStartupAcknowledgesAndPreservesExecutableMode() {
        if (!DesktopPlatform.isLinux) return
        UpdaterHelperFixture().use { f ->
            f.candidate("sleep 0.2\n${f.acknowledge}\nsleep 20")
            assertEquals(0, f.run()); assertTrue(Files.isExecutable(f.current))
        }
    }
    @Test fun staleMarkerCannotAcknowledgeAnotherOperation() {
        if (!DesktopPlatform.isLinux) return
        UpdaterHelperFixture().use { f ->
            f.candidate(f.acknowledge); Files.writeString(f.ready, "stale")
            assertEquals(1, f.run()); assertEquals(f.original, Files.readString(f.current))
            assertFalse(Files.exists(f.backup)); assertTrue(f.log().contains("stale-readiness"))
        }
    }
    @Test fun wrongTokenCannotAcknowledgeAHealthyCandidate() {
        if (!DesktopPlatform.isLinux) return
        UpdaterHelperFixture().use { f ->
            f.candidate("BITCHORD_UPDATE_TOKEN=wrong\n${f.acknowledge}\nsleep 20")
            assertEquals(2, f.run(polls = 8)); assertTrue(Files.exists(f.backup))
            assertTrue(f.log().contains("no kill, rollback, or duplicate launch"))
        }
    }
    @Test fun liveParentMustExitBeforeReplacementAndAbortLeavesItUntouched() {
        if (!DesktopPlatform.isLinux) return
        UpdaterHelperFixture().use { f ->
            val parent = ProcessBuilder("/bin/sleep", "20").start()
            try {
                f.candidate(f.acknowledge + "\nsleep 20")
                val helper = f.start(parent.pid())
                runBlocking { withTimeout(3000) { while (!Files.exists(f.operation.resolve("armed"))) delay(10) } }
                assertEquals(f.original, Files.readString(f.current)); assertFalse(Files.exists(f.backup))
                Files.writeString(f.operation.resolve("abort"), "abort")
                assertTrue(helper.waitFor(3, TimeUnit.SECONDS)); assertEquals(1, helper.exitValue())
                assertEquals(f.original, Files.readString(f.current)); assertTrue(parent.isAlive)
            } finally { parent.destroy(); parent.waitFor(2, TimeUnit.SECONDS) }
        }
    }
    @Test fun realParentTerminationAllowsReplacement() {
        if (!DesktopPlatform.isLinux) return
        UpdaterHelperFixture().use { f ->
            val parent = ProcessBuilder("/bin/sleep", "20").start()
            try {
                f.candidate(f.acknowledge + "\nsleep 20")
                val helper = f.start(parent.pid())
                runBlocking { withTimeout(3000) { while (!Files.exists(f.operation.resolve("armed"))) delay(10) } }
                parent.destroy(); parent.waitFor(2, TimeUnit.SECONDS)
                assertTrue(helper.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, helper.exitValue())
            } finally { parent.destroy() }
        }
    }
    @Test fun checksumMismatchDoesNotReplaceOrRemoveOriginal() {
        if (!DesktopPlatform.isLinux) return
        UpdaterHelperFixture().use { f ->
            f.candidate(f.acknowledge)
            assertEquals(1, f.run(hash = "0".repeat(64)))
            assertEquals(f.original, Files.readString(f.current)); assertFalse(Files.exists(f.backup))
            assertTrue(Files.exists(f.operation.resolve("completed")))
        }
    }
    @Test fun existingBackupAndSymlinkAreNeverOverwritten() {
        if (!DesktopPlatform.isLinux) return
        UpdaterHelperFixture().use { f ->
            f.candidate(f.acknowledge)
            val unrelated = Files.writeString(f.directory.resolve("unrelated"), "preserve")
            Files.createSymbolicLink(f.backup, unrelated)
            assertEquals(1, f.run()); assertEquals("preserve", Files.readString(unrelated))
            assertEquals(f.original, Files.readString(f.current))
        }
    }
    @Test fun environmentCleanupPreservesDesktopSession() {
        if (!DesktopPlatform.isLinux) return
        UpdaterHelperFixture().use { f ->
            val captured = f.operation.resolve("environment")
            f.candidate("""
                printf '%s|%s|%s|%s|%s|%s|%s' "${'$'}{APPDIR-unset}" "${'$'}{LD_LIBRARY_PATH-unset}" "${'$'}DISPLAY" "${'$'}WAYLAND_DISPLAY" "${'$'}XDG_RUNTIME_DIR" "${'$'}DBUS_SESSION_BUS_ADDRESS" "${'$'}XAUTHORITY" > "$captured"
                ${f.acknowledge}
                sleep 20
            """.trimIndent())
            assertEquals(0, f.run(environment = mapOf("APPDIR" to "/tmp/old-mount", "LD_LIBRARY_PATH" to "/tmp/old-mount/lib",
                "DISPLAY" to ":fixture", "WAYLAND_DISPLAY" to "wayland-fixture", "XDG_RUNTIME_DIR" to "/run/fixture", "DBUS_SESSION_BUS_ADDRESS" to "unix:path=fixture", "XAUTHORITY" to "/fixture/auth")))
            assertEquals("unset|unset|:fixture|wayland-fixture|/run/fixture|unix:path=fixture|/fixture/auth", Files.readString(captured))
        }
    }
    @Test fun installerRejectsScriptsAndRequiresRollbackDiskReserve() {
        if (!DesktopPlatform.isLinux) return
        val dir = Files.createTempDirectory("updater-space")
        try {
            val file = dir.resolve("BitChord-1.8.5-linux-x86_64.AppImage")
            Files.writeString(file, "#!/bin/sh\nexit 0")
            assertFalse(DesktopUpdateInstaller.validExecutable(file, UpdateFormat.APPIMAGE))
            val header = byteArrayOf(0x7f, 69, 76, 70, 2, 1, 1, 0, 65, 73, 2, 0)
            Files.write(file, header)
            val current = dir.resolve("current.AppImage"); Files.write(current, header)
            // Sparse length forces the real free-space check without filling the filesystem.
            java.io.RandomAccessFile(current.toFile(), "rw").use { it.setLength(Files.getFileStore(dir).usableSpace / 2 + 1024 * 1024) }
            val failure = assertFailsWith<IllegalArgumentException> {
                DesktopUpdateInstaller.prepare(UpdateInstallation(UpdateFormat.APPIMAGE, current), file, DesktopUpdateDownloader.sha256(file))
            }
            assertTrue(failure.message.orEmpty().contains("free space"))
            assertTrue(Files.exists(current))
        } finally { dir.toFile().deleteRecursively() }
    }
}
