package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, INSTALLING, UP_TO_DATE, CANCELLED, FAILED, UNSUPPORTED }
internal data class DesktopUpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: DesktopUpdateChecker.Release? = null,
    val received: Long = 0, val total: Long = 0,
    val file: Path? = null, val hash: String? = null, val error: String? = null,
)

internal class DesktopUpdateService(
    val installation: UpdateInstallation,
    private val discover: suspend (DesktopUpdateChecker.Channel, UpdateInstallation) -> DesktopUpdateChecker.Release? = DesktopUpdateChecker::discover,
    private val downloader: DesktopUpdateDownloader = DesktopUpdateDownloader(),
    private val persistence: DesktopPersistence = DesktopPersistence(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val directory: Path = Path.of(System.getProperty("user.home"), ".cache", "bitchord", "updates"),
    private val prepare: (UpdateInstallation, Path, String, String) -> DesktopPreparedUpdate = DesktopUpdateInstaller::prepare,
) {
    val canAutoInstall = DesktopUpdateInstaller.canInstall(installation)
    val state = MutableStateFlow(DesktopUpdateState())
    val dialog = MutableStateFlow(false)
    val autoCheck = MutableStateFlow(persistence.boolean("updates_auto", true))
    val channel = MutableStateFlow(runCatching { DesktopUpdateChecker.Channel.valueOf(persistence.string("updates_channel", "STABLE")) }.getOrDefault(DesktopUpdateChecker.Channel.STABLE))
    val lastCheck = MutableStateFlow(persistence.string("updates_last_success").toLongOrNull() ?: 0)
    private var job: Job? = null
    private var generation = 0L
    private val preferenceWrites = Mutex()
    @Volatile private var dismissedVersion = persistence.string("updates_dismissed")
    private val busy get() = state.value.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING)

    private fun persist(write: () -> Unit) { scope.launch(Dispatchers.IO) { preferenceWrites.withLock { write() } } }
    fun setAuto(value: Boolean) { autoCheck.value = value; persist { persistence.saveBoolean("updates_auto", autoCheck.value) } }

    @Synchronized fun setChannel(value: DesktopUpdateChecker.Channel) {
        if (channel.value == value || state.value.phase == UpdatePhase.INSTALLING) return
        generation++
        job?.cancel()
        channel.value = value
        persist { persistence.saveString("updates_channel", channel.value.name) }
        state.value = DesktopUpdateState()
        check(force = true)
    }

    fun startup() { if (autoCheck.value) check(force = false) }

    /** Serialize filesystem work across cancellation, and fence all publications by operation. */
    @Synchronized private fun begin(initial: DesktopUpdateState, work: suspend (Long) -> Unit) {
        val previous = job
        previous?.cancel()
        val request = ++generation
        state.value = initial
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                ensureActive()
                work(request)
            } catch (cancelled: CancellationException) {
                publish(request, initial.copy(phase = if (initial.phase == UpdatePhase.INSTALLING) UpdatePhase.FAILED else UpdatePhase.CANCELLED,
                    error = if (initial.phase == UpdatePhase.INSTALLING) "Update preparation was cancelled; installation was not changed." else null))
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                publish(request, initial.copy(phase = UpdatePhase.FAILED, error = failure.message ?: "Update failed. See updater logs."))
            }
        }.also { it.start() }
    }

    @Synchronized private fun publish(request: Long, value: DesktopUpdateState) {
        if (request == generation) state.value = value
    }

    @Synchronized fun check(force: Boolean = true) {
        if (busy) return
        if (!force && System.currentTimeMillis() - lastCheck.value < 6 * 60 * 60 * 1000) return
        val requestedChannel = channel.value
        begin(DesktopUpdateState(UpdatePhase.CHECKING)) { request ->
            cleanCache()
            val release = discover(requestedChannel, installation)
            currentCoroutineContext().ensureActive()
            synchronized(this) {
                if (request == generation) {
                    lastCheck.value = System.currentTimeMillis()
                    persist { persistence.saveString("updates_last_success", lastCheck.value.toString()) }
                    publish(request, DesktopUpdateState(when {
                        release == null -> UpdatePhase.UP_TO_DATE
                        release.asset == null -> UpdatePhase.UNSUPPORTED
                        else -> UpdatePhase.AVAILABLE
                    }, release))
                    if (release != null && !force && dismissedVersion != release.version) dialog.value = true
                }
            }
        }
    }

    @Synchronized fun download() {
        if (busy) return
        val release = state.value.release ?: return
        if (release.asset == null) return
        begin(DesktopUpdateState(UpdatePhase.DOWNLOADING, release)) { request ->
            cleanCache()
            var lastProgress = 0L
            val file = downloader.download(release, directory) { received, total ->
                val now = System.nanoTime()
                if (received == total || now - lastProgress > 100_000_000) {
                    publish(request, DesktopUpdateState(if (received == total) UpdatePhase.VERIFYING else UpdatePhase.DOWNLOADING, release, received, total))
                    lastProgress = now
                }
            }
            currentCoroutineContext().ensureActive()
            val hash = DesktopUpdateDownloader.sha256(file)
            currentCoroutineContext().ensureActive()
            publish(request, DesktopUpdateState(UpdatePhase.READY, release, file = file, hash = hash))
        }
    }

    @Synchronized fun cancel() {
        if (state.value.phase == UpdatePhase.INSTALLING || !busy) return
        generation++
        job?.cancel()
        state.value = state.value.copy(phase = UpdatePhase.CANCELLED)
    }

    fun later() {
        state.value.release?.let { dismissedVersion = it.version; persist { persistence.saveString("updates_dismissed", dismissedVersion) } }
        dialog.value = false
    }

    fun showDownload() { state.value.file?.parent?.let(::showDirectory) }
    fun showLogs() = showDirectory(DesktopUpdateDiagnostics.directory())
    private fun showDirectory(path: Path) {
        scope.launch {
            runCatching { Files.createDirectories(path); java.awt.Desktop.getDesktop().open(path.toFile()) }
                .onFailure { DesktopTrackLog.log("updater: unable to open directory $path (${it.javaClass.simpleName})") }
        }
    }

    @Synchronized fun install(onExit: suspend () -> Unit) {
        val ready = state.value
        if (ready.phase != UpdatePhase.READY || ready.file == null || ready.hash == null || !canAutoInstall) return
        begin(ready.copy(phase = UpdatePhase.INSTALLING)) { _ ->
            val prepared = prepare(installation, ready.file, ready.hash, requireNotNull(ready.release).version)
            try {
                currentCoroutineContext().ensureActive()
                prepared.launch() // Disk/process work remains off the UI thread.
                prepared.handoff()
                DesktopUpdateDiagnostics.log(prepared.operation, "stage=shutdown-requested")
                withContext(Dispatchers.Main) { onExit() }
                error("Application shutdown did not complete. The update was aborted; see updater logs.")
            } catch (failure: Throwable) {
                DesktopUpdateDiagnostics.log(prepared.operation, "stage=shutdown-or-handoff-failed error=${failure.javaClass.simpleName}")
                throw failure
            } finally { prepared.abort() }
        }
    }

    private fun cleanCache() {
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory)) { "Update cache cannot be a symbolic link." }
        // Never traverse unrelated files or delete a different active operation's partial file.
        // Jobs are joined before this executes; retain three recent download directories.
        Files.list(directory).use { files ->
            files.filter { it.fileName.toString().startsWith("download-") && !Files.isSymbolicLink(it) && Files.isDirectory(it, NOFOLLOW_LINKS) }
                .sorted(compareByDescending<Path> { Files.getLastModifiedTime(it).toMillis() }).skip(3).forEach { old ->
                    runCatching {
                        Files.list(old).use { contents -> contents.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.forEach(Files::deleteIfExists) }
                        Files.deleteIfExists(old)
                    }
                }
        }
    }
}

internal object DesktopUpdateManager {
    // Filesystem/package ownership probes run on a worker, never during first composition.
    val service by lazy { DesktopUpdateService(UpdateInstallation.detect()) }
}
