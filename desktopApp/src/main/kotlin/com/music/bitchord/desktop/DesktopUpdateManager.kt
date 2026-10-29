package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

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
) {
    val canAutoInstall = DesktopUpdateInstaller.canInstall(installation)
    val state = MutableStateFlow(DesktopUpdateState())
    val dialog = MutableStateFlow(false)
    val autoCheck = MutableStateFlow(persistence.boolean("updates_auto", true))
    val channel = MutableStateFlow(runCatching { DesktopUpdateChecker.Channel.valueOf(persistence.string("updates_channel", "STABLE")) }.getOrDefault(DesktopUpdateChecker.Channel.STABLE))
    val lastCheck = MutableStateFlow(persistence.string("updates_last_success").toLongOrNull() ?: 0)
    private var job: Job? = null
    private var prepared: (() -> Unit)? = null

    fun setAuto(value: Boolean) { persistence.saveBoolean("updates_auto", value); autoCheck.value = value }
    @Synchronized fun setChannel(value: DesktopUpdateChecker.Channel) {
        if (channel.value == value) return
        job?.cancel(); prepared = null
        channel.value = value; persistence.saveString("updates_channel", value.name)
        state.value = DesktopUpdateState()
        check(force = true)
    }
    fun startup() { if (autoCheck.value) check(force = false) }
    @Synchronized fun check(force: Boolean = true) {
        if (job?.isActive == true || state.value.phase == UpdatePhase.INSTALLING) return
        if (!force && System.currentTimeMillis() - lastCheck.value < 6 * 60 * 60 * 1000) return
        state.value = DesktopUpdateState(UpdatePhase.CHECKING)
        val requestedChannel = channel.value
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Only our download directory is cleaned; existing app data is never traversed.
                Files.createDirectories(directory)
                Files.list(directory).use { files -> files.filter { it.fileName.toString().endsWith(".part") }.forEach { Files.deleteIfExists(it) } }
                val release = discover(requestedChannel, installation)
                ensureActive()
                lastCheck.value = System.currentTimeMillis()
                persistence.saveString("updates_last_success", lastCheck.value.toString())
                state.value = DesktopUpdateState(when { release == null -> UpdatePhase.UP_TO_DATE
                    release.asset == null -> UpdatePhase.UNSUPPORTED; else -> UpdatePhase.AVAILABLE }, release)
                if (release != null && !force && persistence.string("updates_dismissed") != release.version) dialog.value = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); state.value = DesktopUpdateState(UpdatePhase.FAILED, error = failure.message ?: "Unable to check for updates.") }
        }.also { it.start() }
    }
    @Synchronized fun download() {
        if (job?.isActive == true || state.value.phase == UpdatePhase.INSTALLING) return
        val release = state.value.release ?: return
        if (release.asset == null) return
        state.value = DesktopUpdateState(UpdatePhase.DOWNLOADING, release)
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var lastProgress = 0L
                val file = downloader.download(release, directory) { received, total ->
                    val now = System.nanoTime()
                    if (received == total || now - lastProgress > 100_000_000) {
                        state.value = DesktopUpdateState(if (received == total) UpdatePhase.VERIFYING else UpdatePhase.DOWNLOADING, release, received, total)
                        lastProgress = now
                    }
                }
                ensureActive()
                state.value = DesktopUpdateState(UpdatePhase.READY, release, file = file, hash = DesktopUpdateDownloader.sha256(file))
            } catch (cancelled: CancellationException) {
                // A channel change owns its own state; cancelled work must not overwrite it.
                if (state.value.release == release && state.value.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING))
                    state.value = DesktopUpdateState(UpdatePhase.CANCELLED, release)
                throw cancelled
            } catch (failure: Exception) { currentCoroutineContext().ensureActive(); state.value = DesktopUpdateState(UpdatePhase.FAILED, release, error = failure.message) }
        }.also { it.start() }
    }
    fun cancel() { job?.cancel() }
    fun later() {
        state.value.release?.let { persistence.saveString("updates_dismissed", it.version) }
        dialog.value = false
    }
    fun showDownload() {
        val file = state.value.file ?: return
        scope.launch {
            try { java.awt.Desktop.getDesktop().open(file.parent.toFile()) }
            catch (failure: Exception) { state.value = state.value.copy(error = "Download saved at $file. Unable to open the file manager: ${failure.message}") }
        }
    }
    @Synchronized fun install(onExit: () -> Unit) {
        if (job?.isActive == true) return
        val ready = state.value
        if (ready.phase != UpdatePhase.READY || ready.file == null || ready.hash == null) return
        state.value = ready.copy(phase = UpdatePhase.INSTALLING)
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                prepared = DesktopUpdateInstaller.prepare(installation, ready.file, ready.hash)
                withContext(Dispatchers.Main) {
                    requireNotNull(prepared).invoke()
                    onExit()
                }
            } catch (failure: Exception) { currentCoroutineContext().ensureActive(); state.value = ready.copy(phase = UpdatePhase.FAILED, error = failure.message) }
        }.also { it.start() }
    }
}

internal object DesktopUpdateManager {
    // Filesystem/package ownership probes run on a worker, never during first composition.
    val service by lazy { DesktopUpdateService(UpdateInstallation.detect()) }
}
