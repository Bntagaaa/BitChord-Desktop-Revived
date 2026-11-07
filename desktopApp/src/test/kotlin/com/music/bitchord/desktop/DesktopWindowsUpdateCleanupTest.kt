package com.music.bitchord.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWindowsUpdateCleanupTest {
    @Test fun nestedPayloadCleanupProtectsADelegatedLiveWorker() {
        val root = Files.createTempDirectory("host-cleanup")
        try {
            val op = Files.createDirectory(root.resolve("operation-finished"))
            val payload = Files.createDirectory(op.resolve("payload"))
            val packageFile = Files.writeString(payload.resolve("verified-portable.zip"), "large fixture")
            val log = Files.writeString(op.resolve("install.log"), "retained")
            Files.writeString(op.resolve("completed"), "host-exited")
            val process = ProcessHandle.current()
            val identity = if (DesktopPlatform.isLinux)
                "pid=${process.pid()}\nstart_ticks=${DesktopUpdateProcess.linuxStartTicks()}\n"
                else "pid=${process.pid()}\nstart_millis=${process.info().startInstant().orElseThrow().toEpochMilli()}\n"
            val worker = Files.writeString(payload.resolve("helper.identity"), identity)
            DesktopUpdateDiagnostics.compactCompletedWindowsOperations(root)
            assertTrue(Files.exists(packageFile), "An exited host does not mean its worker/installer has stopped")
            Files.delete(worker)
            DesktopUpdateDiagnostics.compactCompletedWindowsOperations(root)
            assertFalse(Files.exists(packageFile))
            assertTrue(Files.exists(log))
            assertTrue(Files.exists(op.resolve("completed")))
        } finally { root.toFile().deleteRecursively() }
    }
}
