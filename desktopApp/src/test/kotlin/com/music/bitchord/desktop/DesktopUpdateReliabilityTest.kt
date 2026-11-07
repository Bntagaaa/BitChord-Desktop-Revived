package com.music.bitchord.desktop

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopUpdateReliabilityTest {
    private val bytes = ByteArray(128 * 1024) { it.toByte() }
    private fun hash() = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun release(version: String = "1.8.5") = DesktopUpdateChecker.Release(version,
        "https://github.com/${DesktopUpdateChecker.REPOSITORY}/releases/tag/v$version", "", false,
        DesktopUpdateChecker.Asset("BitChord-$version-linux-x86_64.AppImage", "https://github.com/${DesktopUpdateChecker.REPOSITORY}/releases/download/v$version/BitChord-$version-linux-x86_64.AppImage", bytes.size.toLong(), hash()), null)

    @Test fun semanticIdentifiersKeepTheirOrderingAndRejectInvalidVersions() {
        assertTrue(DesktopUpdateChecker.isNewer("1.8.5-x2", "1.8.5-x10"))
        assertTrue(DesktopUpdateChecker.isNewer("1.8.5-beta10", "1.8.5-beta2"))
        for (invalid in listOf("01.8.5", "1.08.5", "1.8.05", "1.8.5.1", "1.8.5-beta.01", "1.8.5-a..b", "1.8.5+"))
            assertNull(DesktopUpdateVersion.parse(invalid), invalid)
        assertEquals(0, DesktopUpdateVersion.parse("1.8.5+abc")!!.compareTo(DesktopUpdateVersion.parse("1.8.5+xyz")!!))
    }
    @Test fun assetsAndManifestsMustBelongToSelectedRelease() = runBlocking {
        val dir = Files.createTempDirectory("updater-origin")
        try {
            val normal = release()
            val bad = normal.copy(asset = normal.asset!!.copy(url = normal.asset.url.replace("download/v1.8.5/", "download/v1.8.4/")))
            var calls = 0
            val downloader = DesktopUpdateDownloader { calls++; ByteArrayInputStream(bytes) }
            assertFailsWith<IllegalArgumentException> { downloader.download(bad, dir) { _, _ -> } }
            val foreignManifest = normal.copy(asset = normal.asset.copy(sha256 = null), manifest = DesktopUpdateChecker.Asset("SHA256SUMS",
                "https://github.com/${DesktopUpdateChecker.REPOSITORY}/releases/download/v1.8.4/SHA256SUMS", 100, null))
            assertFailsWith<IllegalArgumentException> { downloader.download(foreignManifest, dir) { _, _ -> } }
            assertEquals(0, calls)
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun symlinkCacheAndOversizedMetadataDoNotTouchUnrelatedFiles() = runBlocking {
        if (!DesktopPlatform.isLinux) return@runBlocking
        val dir = Files.createTempDirectory("updater-symlink")
        try {
            val outside = Files.createDirectory(dir.resolve("outside"))
            val sentinel = Files.writeString(outside.resolve("preserve"), "user-data")
            val link = Files.createSymbolicLink(dir.resolve("cache"), outside)
            val downloader = DesktopUpdateDownloader { ByteArrayInputStream(bytes) }
            assertFailsWith<IllegalArgumentException> { downloader.download(release(), link) { _, _ -> } }
            val huge = release().let { it.copy(asset = it.asset!!.copy(size = Long.MAX_VALUE)) }
            assertFailsWith<IllegalArgumentException> { downloader.download(huge, outside) { _, _ -> } }
            assertEquals("user-data", Files.readString(sentinel))
            assertEquals(1L, Files.list(outside).use { it.count() })
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun cancelledDownloadCanRetryWithoutOverwritingPreviousVerifiedFiles() = runBlocking {
        val dir = Files.createTempDirectory("updater-retry")
        try {
            val downloader = DesktopUpdateDownloader { ByteArrayInputStream(bytes) }
            val previous = downloader.download(release(), dir) { _, _ -> }
            val job = launch { downloader.download(release(), dir) { _, _ -> cancel() } }
            job.join(); assertTrue(job.isCancelled)
            val next = downloader.download(release(), dir) { _, _ -> }
            assertNotEquals(previous, next)
            assertContentEquals(bytes, Files.readAllBytes(previous)); assertContentEquals(bytes, Files.readAllBytes(next))
            assertFalse(Files.walk(dir).use { it.anyMatch { path -> path.toString().endsWith(".part") } })
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun oldPreviewPreferenceIsIgnoredByDefault() {
        val persistence = DesktopPersistence()
        val saved = persistence.string("updates_channel", "STABLE")
        persistence.saveString("updates_channel", "PREVIEW")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val service = DesktopUpdateService(UpdateInstallation(UpdateFormat.UNKNOWN), persistence = persistence, scope = scope)
            assertEquals(DesktopUpdateChecker.Channel.STABLE, service.channel.value)
        } finally {
            persistence.saveString("updates_channel", saved)
            scope.cancel()
        }
    }

    @Test fun changingChannelWaitsForCancelledWorkAndRejectsStalePublication() = runBlocking {
        val dir = Files.createTempDirectory("updater-generation")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val releaseOld = CompletableDeferred<Unit>()
        val persistence = DesktopPersistence(); val saved = persistence.string("updates_channel", "STABLE")
        persistence.saveString("updates_channel", "STABLE")
        try {
            val service = DesktopUpdateService(UpdateInstallation(UpdateFormat.UNKNOWN), discover = { channel, _ ->
                if (channel == DesktopUpdateChecker.Channel.STABLE) {
                    entered.complete(Unit); withContext(NonCancellable) { releaseOld.await() }; release("1.8.5")
                } else release("1.9.0-beta.1")
            }, scope = scope, directory = dir)
            service.check(); withTimeout(3000) { entered.await() }
            service.setChannel(DesktopUpdateChecker.Channel.PREVIEW)
            assertEquals(UpdatePhase.CHECKING, service.state.value.phase)
            releaseOld.complete(Unit)
            val current = withTimeout(5000) { service.state.first { it.phase == UpdatePhase.AVAILABLE } }
            assertEquals("1.9.0-beta.1", current.release!!.version)
        } finally { releaseOld.complete(Unit); scope.cancel(); persistence.saveString("updates_channel", saved); dir.toFile().deleteRecursively() }
    }
    @Test fun installFailureUnblocksStateAndChannelCannotCancelHandoff() = runBlocking {
        val dir = Files.createTempDirectory("updater-install-state")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val exits = AtomicInteger()
        try {
            // Use a format that supports the injected preparation path on every CI OS.
            // APPIMAGE is intentionally unsupported by canAutoInstall on Windows.
            val windowsRelease = release().let { candidate ->
                val asset = requireNotNull(candidate.asset)
                candidate.copy(asset = asset.copy(
                    name = asset.name.replace("-linux-x86_64.AppImage", "-windows-x64-setup.exe"),
                    url = asset.url.replace("-linux-x86_64.AppImage", "-windows-x64-setup.exe"),
                ))
            }
            val installed = Files.writeString(dir.resolve("current-install.exe"), "fixture")
            val service = DesktopUpdateService(UpdateInstallation(UpdateFormat.WINDOWS_SETUP), discover = { _, _ -> windowsRelease },
                downloader = DesktopUpdateDownloader { ByteArrayInputStream(bytes) }, scope = scope, directory = dir.resolve("cache"),
                prepare = { _, _, _, _ ->
                    entered.countDown(); check(finish.await(10, TimeUnit.SECONDS)); error("fixture preparation failure")
                })
            service.check(); withTimeout(5000) { service.state.first { it.phase == UpdatePhase.AVAILABLE } }
            service.download(); withTimeout(5000) { service.state.first { it.phase == UpdatePhase.READY } }
            service.install { exits.incrementAndGet() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val before = service.channel.value
            service.setChannel(if (before == DesktopUpdateChecker.Channel.STABLE) DesktopUpdateChecker.Channel.PREVIEW else DesktopUpdateChecker.Channel.STABLE)
            service.cancel(); assertEquals(UpdatePhase.INSTALLING, service.state.value.phase); assertEquals(before, service.channel.value)
            finish.countDown()
            val failed = withTimeout(5000) { service.state.first { it.phase == UpdatePhase.FAILED } }
            assertTrue(failed.error.orEmpty().contains("fixture")); assertEquals(0, exits.get())
            assertTrue(Files.exists(installed))
        } finally { finish.countDown(); scope.cancel(); dir.toFile().deleteRecursively() }
    }
    @Test fun packageOwnerQueriesUseCanonicalLauncherAndExactManagerFlags() {
        if (!DesktopPlatform.isLinux) return
        val dir = Files.createTempDirectory("updater-ownership")
        try {
            val launcher = Files.writeString(dir.resolve("BitChord"), "fixture")
            val link = Files.createSymbolicLink(dir.resolve("launcher"), launcher)
            for ((manager, arguments) in listOf("pacman" to listOf("pacman", "-Qqo"), "dpkg-query" to listOf("dpkg-query", "-S"), "rpm" to listOf("rpm", "-qf"))) {
                val calls = mutableListOf<List<String>>()
                val detected = UpdateInstallation.ownerOf(link) { command -> calls += command; command.first() == manager }
                assertEquals(manager, detected); assertEquals(arguments + launcher.toRealPath().toString(), calls.last())
            }
            Files.writeString(dir.resolve(".bitchord-portable"), "")
            assertTrue(UpdateInstallation.portableMarker(launcher))
            val bin = Files.createDirectory(dir.resolve("bin"))
            assertTrue(UpdateInstallation.portableMarker(bin.resolve("BitChord")))
            val separateInstall = Files.createDirectory(dir.resolve("installed"))
            assertFalse(UpdateInstallation.portableMarker(separateInstall.resolve("BitChord.exe")))
            assertEquals(UpdateFormat.APPIMAGE, UpdateInstallation.classify("Linux", "amd64", launcher.toString(), false, false, "pacman").format)
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun readinessRequiresOperationTokenVersionAndCurrentProcessIdentity() {
        if (!DesktopPlatform.isLinux) return
        val dir = Files.createTempDirectory("updater-ready")
        try {
            val ready = dir.resolve("window-ready"); val token = "b".repeat(32)
            val environment = mapOf("BITCHORD_UPDATE_READY_FILE" to ready.toString(), "BITCHORD_UPDATE_TOKEN" to token, "BITCHORD_UPDATE_EXPECTED_VERSION" to "1.8.5")
            assertFalse(DesktopUpdateReadiness.acknowledge(environment, "1.8.5"))
            Files.writeString(dir.resolve("request.token"), token)
            assertFalse(DesktopUpdateReadiness.acknowledge(environment, "1.8.4"))
            assertTrue(DesktopUpdateReadiness.acknowledge(environment, "1.8.5"))
            val marker = Files.readString(ready)
            assertTrue(marker.contains("pid=${ProcessHandle.current().pid()}\n"))
            assertTrue(marker.contains("start_ticks=${DesktopUpdateProcess.linuxStartTicks()}\n"))
            assertFalse(DesktopUpdateReadiness.acknowledge(environment, "1.8.5"))
            assertEquals(marker, Files.readString(ready))
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun redirectsStayOnHttpsOfficialReleaseInfrastructure() {
        val repo = DesktopUpdateChecker.REPOSITORY
        for (uri in listOf("https://github.com/$repo/releases/download/v1.8.5/package.exe", "https://release-assets.githubusercontent.com/asset?signature=fixture", "https://objects.githubusercontent.com/asset"))
            assertTrue(allowedUpdateDownloadUri(java.net.URI(uri)), uri)
        for (uri in listOf("http://github.com/$repo/releases/download/v1/package", "https://github.com/foreign/repo/releases/download/v1/package", "https://attacker.example/asset", "https://user:secret@objects.githubusercontent.com/asset", "https://objects.githubusercontent.com:8443/asset", "https://github.com/$repo/releases/download/v1/package#fragment"))
            assertFalse(allowedUpdateDownloadUri(java.net.URI(uri)))
    }
    @Test fun completedWindowsOperationsDropLargePayloadsButKeepLogsAndActiveOperations() {
        val root = Files.createTempDirectory("updater-compact")
        try {
            val done = Files.createDirectory(root.resolve("operation-finished"))
            Files.writeString(done.resolve("completed"), "installer-reported-success")
            val setup = Files.write(done.resolve("verified-setup.exe"), bytes)
            val zip = Files.write(done.resolve("verified-portable.zip"), bytes)
            val log = Files.writeString(done.resolve("install.log"), "diagnostic detail")
            val pending = Files.createDirectory(root.resolve("operation-pending"))
            val uncompleted = Files.write(pending.resolve("verified-portable.zip"), bytes)
            val live = Files.createDirectory(root.resolve("operation-live"))
            Files.writeString(live.resolve("completed"), "done")
            val identity = if (DesktopPlatform.isLinux)
                "pid=${ProcessHandle.current().pid()}\nstart_ticks=${DesktopUpdateProcess.linuxStartTicks()}\n"
                else "pid=${ProcessHandle.current().pid()}\nstart_millis=${ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()}\n"
            Files.writeString(live.resolve("helper.identity"), identity)
            val held = Files.write(live.resolve("verified-setup.exe"), bytes)
            val unrelated = Files.write(root.resolve("leave-me.exe"), bytes)
            DesktopUpdateDiagnostics.compactCompletedWindowsOperations(root)
            assertFalse(Files.exists(setup))
            assertFalse(Files.exists(zip))
            assertEquals("diagnostic detail", Files.readString(log))
            assertTrue(Files.exists(done.resolve("completed")))
            assertTrue(Files.exists(uncompleted), "Never delete a package from an unfinished operation")
            assertTrue(Files.exists(held), "Never delete an active helper's package")
            assertTrue(Files.exists(unrelated), "Never touch unrelated files")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun diagnosticsRetireOnlyCompletedOrAbandonedOperations() {
        val root = Files.createTempDirectory("updater-log-retention")
        try {
            repeat(25) { index ->
                val entry = Files.createDirectory(root.resolve("operation-$index"))
                Files.writeString(entry.resolve("completed"), "done")
                Files.writeString(entry.resolve("install.log"), "fixture")
            }
            val abandoned = Files.createDirectory(root.resolve("operation-abandoned"))
            Files.writeString(abandoned.resolve("install.log"), "fixture")
            Files.setLastModifiedTime(abandoned, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000))
            val foreign = Files.createDirectory(root.resolve("user-notes")); Files.writeString(foreign.resolve("completed"), "preserve")
            val active = Files.createDirectory(root.resolve("operation-active")); Files.writeString(active.resolve("completed"), "fixture")
            if (DesktopPlatform.isLinux) Files.writeString(active.resolve("helper.identity"), "pid=${ProcessHandle.current().pid()}\nstart_ticks=${DesktopUpdateProcess.linuxStartTicks()}\n")
            else Files.writeString(active.resolve("helper.identity"), "pid=${ProcessHandle.current().pid()}\nstart_millis=${ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()}\n")
            DesktopUpdateDiagnostics.createOperation(root)
            assertFalse(Files.exists(abandoned)); assertTrue(Files.exists(foreign.resolve("completed"))); assertTrue(Files.exists(active))
            assertEquals(22L, Files.list(root).use { it.count() }) // 19 completed + active + new + unrelated.
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun blockedNativeShutdownTimesOutWithoutADeferredApplicationExit() = runBlocking {
        val coordinator = DesktopUpdateShutdownCoordinator()
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1); val finished = CompletableDeferred<Unit>()
        try {
            val attempt = async {
                assertFailsWith<IllegalStateException> {
                    coordinator.finish(100) { entered.complete(Unit); release.await(3, TimeUnit.SECONDS); finished.complete(Unit) }
                }
            }
            withTimeout(3000) { entered.await(); attempt.await() }
            assertFailsWith<IllegalStateException> { coordinator.finish { error("Overlapping shutdown must not execute") } }
            release.countDown(); withTimeout(3000) { finished.await() }
        } finally { release.countDown() }
    }
    @Test fun legacyInstalledHelperCanAcknowledgeOnlyItsPrivateUniqueMarker() {
        if (!DesktopPlatform.isLinux) return
        val operation = Files.createTempDirectory("bitchord-update-helper-")
        try {
            val ready = operation.resolve("window-ready-00000000-0000-4000-8000-000000000000")
            val environment = mapOf("BITCHORD_UPDATE_READY_FILE" to ready.toString())
            assertTrue(DesktopUpdateReadiness.acknowledge(environment, "1.8.5"))
            assertTrue(Files.readString(ready).contains("pid=${ProcessHandle.current().pid()}"))
            assertFalse(DesktopUpdateReadiness.acknowledge(environment, "1.8.5"))
            val other = operation.resolve("window-ready-11111111-1111-4111-8111-111111111111")
            assertFalse(DesktopUpdateReadiness.acknowledge(environment + ("BITCHORD_UPDATE_READY_FILE" to other.toString()) + ("BITCHORD_UPDATE_TOKEN" to "invalid"), "1.8.5"))
            assertFalse(Files.exists(other))
        } finally { operation.toFile().deleteRecursively() }
    }
    @Test fun windowsPortableInPlaceUpdateRequiresMarkerLauncherAndCfg() {
        val base = Files.createTempDirectory("bitchord-portable-guard")
        val root = Files.createDirectory(base.resolve("BitChord"))
        try {
            val launcher = Files.writeString(root.resolve("BitChord.exe"), "fixture")
            val portable = UpdateInstallation(UpdateFormat.WINDOWS_PORTABLE, portableRoot = root)
            assertFalse(DesktopUpdateInstaller.canInstall(portable), "Do not replace a folder without its portable marker")
            Files.writeString(root.resolve(".bitchord-portable"), "")
            assertEquals(root, UpdateInstallation.portableRootDirectory(launcher))
            assertFalse(DesktopUpdateInstaller.canInstall(portable), "Incomplete portable folder cannot update in place")
            Files.createDirectories(root.resolve("app"))
            Files.writeString(root.resolve("app/BitChord.cfg"), "fixture")
            assertTrue(DesktopUpdateInstaller.canInstall(portable))
            assertFalse(DesktopUpdateInstaller.canInstall(UpdateInstallation(UpdateFormat.WINDOWS_PORTABLE)),
                "Do not assume the portable root when only a distribution flag is available")
            assertFalse(DesktopUpdateInstaller.canInstall(portable.copy(portableRoot = base.resolve("other"))))
        } finally { base.toFile().deleteRecursively() }
    }

    @Test fun windowsPortableUpdateRequiresZipHeaderNotExeOrText() {
        val root = Files.createTempDirectory("bitchord-zip-header")
        try {
            val good = Files.write(root.resolve("package.zip"), byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0, 0))
            val bad = Files.write(root.resolve("other.zip"), byteArrayOf(0x4d, 0x5a, 0, 0))
            assertTrue(DesktopUpdateInstaller.validExecutable(good, UpdateFormat.WINDOWS_PORTABLE))
            assertFalse(DesktopUpdateInstaller.validExecutable(bad, UpdateFormat.WINDOWS_PORTABLE))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun windowsUpdaterRejectsCaseInsensitiveInstallRootAndAcceptsSibling() {
        val base = Files.createTempDirectory("bitchord-install-location")
        try {
            val local = base.resolve("Local")
            Files.createDirectories(local)
            assertFailsWith<IllegalArgumentException> { DesktopUpdateDiagnostics.ensureOutsideWindowsInstall(local.resolve("bitchord/updater"), local) }
            assertFailsWith<IllegalArgumentException> { DesktopUpdateDiagnostics.ensureOutsideWindowsInstall(local.resolve("BITCHORD"), local) }
            DesktopUpdateDiagnostics.ensureOutsideWindowsInstall(local.resolve("BitChordUpdater/operations"), local)
        } finally { base.toFile().deleteRecursively() }
    }
    @Test fun windowsUpdateDiskBudgetAccountsForDownloadedAndStagedPackages() {
        val size = 340_000_000L
        assertEquals(size * 5 + 2L * 1024 * 1024 * 1024, DesktopUpdateDiskSpace.requiredBytes(size, true))
        assertEquals(size * 4 + 2L * 1024 * 1024 * 1024, DesktopUpdateDiskSpace.requiredBytes(size, false))
        assertFailsWith<IllegalArgumentException> { DesktopUpdateDiskSpace.requiredBytes(0, true) }
    }
    @Test fun diagnosticDirectoryRespectsAbsoluteXdgStateAndWindowsLocalAppData() {
        val base = Files.createTempDirectory("updater-locations")
        try {
            val state = base.resolve("state"); val home = base.resolve("home"); val local = base.resolve("local")
            assertEquals(state.resolve("bitchord/updater"), DesktopUpdateDiagnostics.directory(mapOf("XDG_STATE_HOME" to state.toString()), home.toString(), false))
            assertEquals(home.resolve(".local/state/bitchord/updater"), DesktopUpdateDiagnostics.directory(mapOf("XDG_STATE_HOME" to "relative"), home.toString(), false))
            assertEquals(local.resolve("BitChordUpdater/operations"), DesktopUpdateDiagnostics.directory(mapOf("LOCALAPPDATA" to local.toString()), home.toString(), true))
        } finally { base.toFile().deleteRecursively() }
    }
}
