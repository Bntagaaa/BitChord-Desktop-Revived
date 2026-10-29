package com.music.bitchord.desktop

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopUpdateServiceTest {
    private val content=ByteArray(256 * 1024) { (it % 127).toByte() }
    private fun release(hash: String? = hash(content)) = DesktopUpdateChecker.Release("1.8.4", "https://github.com/${DesktopUpdateChecker.REPOSITORY}/releases/tag/v1.8.4", "notes",false,
        DesktopUpdateChecker.Asset("BitChord-1.8.4-linux-x86_64.AppImage","https://github.com/${DesktopUpdateChecker.REPOSITORY}/releases/download/v1.8.4/BitChord-1.8.4-linux-x86_64.AppImage",content.size.toLong(),hash),null)
    private fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun streamedDownloadVerifiesAndRejectsCorruptionTruncationAndMissingChecksum() = runBlocking {
        val dir=Files.createTempDirectory("updater-test")
        try {
            var progress=0L
            val downloader=DesktopUpdateDownloader { ByteArrayInputStream(content) }
            val ready=downloader.download(release(),dir) { n,_ -> progress=n }
            assertEquals(content.size.toLong(),progress); assertContentEquals(content,Files.readAllBytes(ready))
            assertFailsWith<IllegalArgumentException> { downloader.download(release("0".repeat(64)),dir) {_,_->} }
            assertFalse(Files.exists(ready))
            assertFailsWith<IllegalArgumentException> { DesktopUpdateDownloader { ByteArrayInputStream(content.copyOf(32)) }.download(release(),dir) {_,_->} }
            assertFailsWith<IllegalStateException> { downloader.download(release(null),dir) {_,_->} }
            assertEquals(0L,Files.list(dir).use { it.count() })
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun rejectsTraversalFilenameWithoutTouchingFiles() = runBlocking {
        val dir=Files.createTempDirectory("updater-traversal")
        try {
            val normal=release()
            val malicious=normal.copy(asset=normal.asset!!.copy(name="../outside.zip"))
            assertFailsWith<IllegalArgumentException> {
                DesktopUpdateDownloader { error("Network must not open for unsafe filenames") }.download(malicious,dir) {_,_->}
            }
            assertEquals(0L,Files.list(dir).use { it.count() })
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun manifestMatchesExactFilenameOnly() {
        assertEquals(hash(content),DesktopUpdateDownloader.manifestHash("${hash(content)}  package.zip", "package.zip"))
        assertNull(DesktopUpdateDownloader.manifestHash("${hash(content)}  ../package.zip", "package.zip"))
        assertFails { DesktopUpdateDownloader.manifestHash("${hash(content)}  package.zip\n${hash(content)}  package.zip", "package.zip") }
    }
    @Test fun cancellationRemovesPartialFile() = runBlocking {
        val dir=Files.createTempDirectory("updater-cancel")
        try {
            val job=launch { DesktopUpdateDownloader { ByteArrayInputStream(content) }.download(release(),dir) {_,_-> cancel() } }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(0L,Files.list(dir).use { it.count() })
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun duplicateChecksAreCoalescedAndOfflineIsAnExplicitFailure() = runBlocking {
        val dir=Files.createTempDirectory("updater-state")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val gate=CompletableDeferred<Unit>(); var calls=0
            val service=DesktopUpdateService(UpdateInstallation(UpdateFormat.APPIMAGE),discover={_,_-> calls++;gate.await();release()},scope=scope,directory=dir)
            service.check(); service.check(); service.check()
            gate.complete(Unit)
            withTimeout(5000) { service.state.first { it.phase == UpdatePhase.AVAILABLE } }
            assertEquals(1,calls)
            val offline=DesktopUpdateService(UpdateInstallation(UpdateFormat.UNKNOWN),discover={_,_-> throw java.io.IOException("Offline")},scope=scope,directory=dir)
            offline.check()
            assertEquals("Offline",withTimeout(5000) { offline.state.first { it.phase==UpdatePhase.FAILED } }.error)
        } finally { scope.cancel(); dir.toFile().deleteRecursively() }
    }
    @Test fun downloadStateBecomesReadyAndPortableNeverExtractsOrInstalls() = runBlocking {
        val dir=Files.createTempDirectory("updater-state")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val service=DesktopUpdateService(UpdateInstallation(UpdateFormat.WINDOWS_PORTABLE),discover={_,_->release()},downloader=DesktopUpdateDownloader { ByteArrayInputStream(content) },scope=scope,directory=dir)
            service.check()
            withTimeout(5000) { service.state.first { it.phase==UpdatePhase.AVAILABLE } }
            // The checker job can finish just after publishing its state.
            delay(50)
            service.download(); service.download()
            val ready=withTimeout(5000) { service.state.first { it.phase==UpdatePhase.READY } }
            assertNotNull(ready.file)
            assertFalse(DesktopUpdateInstaller.canInstall(service.installation))
            assertFails { DesktopUpdateInstaller.prepare(service.installation,ready.file,ready.hash!!) }
            Unit
        } finally { scope.cancel();dir.toFile().deleteRecursively() }
    }
    @Test fun appImageHelperRestoresOriginalOnRelaunchFailure() {
        if (!DesktopPlatform.isLinux) return
        val dir=Files.createTempDirectory("updater rollback with spaces")
        try {
            val old=dir.resolve("original.AppImage"); val staged=dir.resolve("new.AppImage"); val backup=dir.resolve("old.backup")
            Files.writeString(old,"#!/bin/sh\nexit 0\n"); old.toFile().setExecutable(true)
            Files.writeString(staged,"#!/bin/sh\nexit 1\n"); staged.toFile().setExecutable(true)
            val original=Files.readAllBytes(old)
            val helper=dir.resolve("helper.sh")
            javaClass.getResourceAsStream("/updater/appimage-update.sh")!!.use { Files.copy(it,helper) }
            val process=ProcessBuilder("/bin/sh",helper.toString(),"2147483647",old.toString(),staged.toString(),backup.toString(),DesktopUpdateDownloader.sha256(staged)).start()
            assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)); assertEquals(1,process.exitValue())
            assertContentEquals(original,Files.readAllBytes(old));assertFalse(Files.exists(backup))
            // Bad checksum cannot rename or remove the original.
            val rejected=ProcessBuilder("/bin/sh",helper.toString(),"2147483647",old.toString(),staged.toString(),backup.toString(),"0".repeat(64)).start()
            assertTrue(rejected.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,rejected.exitValue())
            assertContentEquals(original,Files.readAllBytes(old))
        } finally { dir.toFile().deleteRecursively() }
    }
}
