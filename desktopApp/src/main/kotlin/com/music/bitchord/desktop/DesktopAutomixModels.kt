package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.BeatTracker
import com.music.bitchord.playback.smart.VocalTracker
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class DesktopAutomixModel(val name: String, val size: Long, val sha256: String)

internal sealed interface DesktopAutomixModelState {
    data object NotInstalled : DesktopAutomixModelState
    data class Downloading(val completed: Long, val total: Long) : DesktopAutomixModelState
    data object Ready : DesktopAutomixModelState
    data class Failed(val reason: String) : DesktopAutomixModelState
}

internal object DesktopAutomixModels {
    // Keep this immutable when bumping the app version. New weights get a new model directory.
    private const val SOURCE = "https://github.com/kushagrasinghx/BitChord/releases/download/v1.10/model_"

    val manifest = listOf(
        DesktopAutomixModel(BeatTracker.MODEL_ASSET, 23_138_816,
            "7801f9c1adfd5ff193f37ececa3320bf1fda1ddee2bf7de341cea4c25ffd0acd"),
        DesktopAutomixModel(VocalTracker.MODEL_ASSET, 9_054_206,
            "a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb"),
    )

    val manager by lazy {
        val userHome = Path.of(System.getProperty("user.home"))
        val base = if (DesktopPlatform.isWindows) {
            System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: userHome.resolve("AppData/Local")
        } else {
            System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: userHome.resolve(".local/share")
        }
        DesktopAutomixModelManager(
            base.resolve("bitchord/automix-models/final0-umxhq-v1"),
            DesktopAnalysisRuntime.legacyHome(),
            manifest,
        ) { model -> URI.create(SOURCE + model.name) }
    }
}

internal class DesktopAutomixModelManager(
    private val directory: Path,
    private val legacyDirectory: Path,
    private val models: List<DesktopAutomixModel>,
    private val source: (DesktopAutomixModel) -> URI,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DesktopAutomixModelState>(DesktopAutomixModelState.NotInstalled)
    val state = _state.asStateFlow()
    private var checked = false
    private var working = false
    private var downloadRequested = false

    @Synchronized
    fun inspect() {
        if (!checked && !working) start()
    }

    @Synchronized
    fun download() {
        if (state.value == DesktopAutomixModelState.Ready) return
        downloadRequested = true
        if (!working) start()
    }

    @Synchronized
    fun delete() {
        if (working) return
        working = true
        _state.value = DesktopAutomixModelState.NotInstalled
        scope.launch {
            runOperation {
                for (model in models) {
                    Files.deleteIfExists(directory.resolve(model.name))
                    Files.deleteIfExists(partial(model))
                    Files.deleteIfExists(legacyDirectory.resolve(model.name))
                }
                DesktopAutomixModelState.NotInstalled
            }
        }
    }

    fun path(name: String): String {
        val path = directory.resolve(name)
        if (state.value != DesktopAutomixModelState.Ready || !Files.isRegularFile(path)) {
            throw FileNotFoundException("AutoMix model is unavailable: $name")
        }
        return path.toAbsolutePath().toString()
    }

    private fun start() {
        working = true
        scope.launch {
            runOperation {
                val missing = models.filterNot(::installedOrMigrated)
                val fetch = synchronized(this@DesktopAutomixModelManager) {
                    downloadRequested.also { downloadRequested = false }
                }
                if (missing.isEmpty()) DesktopAutomixModelState.Ready
                else if (fetch) {
                    val total = models.sumOf { it.size }
                    var completed = total - missing.sumOf { it.size }
                    _state.value = DesktopAutomixModelState.Downloading(completed, total)
                    for (model in missing) {
                        fetch(model) {
                            _state.value = DesktopAutomixModelState.Downloading(completed + it, total)
                        }
                        completed += model.size
                    }
                    DesktopAutomixModelState.Ready
                } else DesktopAutomixModelState.NotInstalled
            }
        }
    }

    private fun runOperation(operation: () -> DesktopAutomixModelState) {
        val outcome = try {
            Files.createDirectories(directory)
            FileChannel.open(directory.resolve(".lock"), CREATE, WRITE).use { channel ->
                (channel.tryLock() ?: throw IOException("AutoMix models are in use by another BitChord process"))
                    .use { operation() }
            }
        } catch (failure: Exception) {
            DesktopTrackLog.log("automix models: ${failure.message}")
            DesktopAutomixModelState.Failed(failure.message ?: failure.javaClass.simpleName)
        }
        synchronized(this) {
            checked = true
            working = false
            _state.value = outcome
            if (downloadRequested && outcome == DesktopAutomixModelState.NotInstalled) start()
            else downloadRequested = false
        }
    }

    private fun installedOrMigrated(model: DesktopAutomixModel): Boolean {
        val target = directory.resolve(model.name)
        if (matches(target, model)) return true
        val old = legacyDirectory.resolve(model.name)
        if (!matches(old, model)) return false
        val migration = Files.createTempFile(directory, "migration-", ".part")
        try {
            Files.copy(old, migration, REPLACE_EXISTING)
            install(migration, model)
            return true
        } finally {
            Files.deleteIfExists(migration)
        }
    }

    private fun partial(model: DesktopAutomixModel): Path = directory.resolve("${model.name}.part")

    private fun fetch(model: DesktopAutomixModel, progress: (Long) -> Unit) {
        val part = partial(model)
        var offset = if (Files.isRegularFile(part)) Files.size(part) else 0L
        if (offset == model.size && matches(part, model)) {
            install(part, model)
            progress(model.size)
            return
        }
        if (offset >= model.size) {
            Files.deleteIfExists(part)
            offset = 0
        }
        val connection = source(model).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        try {
            val status = connection.responseCode
            when (status) {
                HttpURLConnection.HTTP_OK -> offset = 0
                HttpURLConnection.HTTP_PARTIAL -> {
                    val expected = "bytes $offset-${model.size - 1}/${model.size}"
                    if (connection.getHeaderField("Content-Range") != expected) {
                        Files.deleteIfExists(part)
                        throw IOException("Invalid download range for ${model.name}")
                    }
                }
                else -> throw IOException("HTTP $status downloading ${model.name}")
            }
            var read = offset
            var reported = offset
            progress(read)
            val writeMode = if (offset > 0) APPEND else TRUNCATE_EXISTING
            connection.inputStream.use { input ->
                Files.newOutputStream(part, CREATE, WRITE, writeMode).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (read + count > model.size) {
                            throw InvalidModelException("${model.name} exceeds its expected size")
                        }
                        output.write(buffer, 0, count)
                        read += count
                        if (read - reported >= 256 * 1024) {
                            reported = read
                            progress(read)
                        }
                    }
                }
            }
            if (read != model.size) throw IOException("Incomplete download of ${model.name}")
            install(part, model)
            progress(model.size)
        } catch (failure: InvalidModelException) {
            Files.deleteIfExists(part)
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun install(part: Path, model: DesktopAutomixModel) {
        if (!matches(part, model)) throw InvalidModelException("${model.name} failed SHA-256 verification")
        Files.move(part, directory.resolve(model.name), REPLACE_EXISTING, ATOMIC_MOVE)
    }

    private fun matches(path: Path, model: DesktopAutomixModel): Boolean {
        if (!Files.isRegularFile(path) || Files.size(path) != model.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest()) == model.sha256
    }

    private class InvalidModelException(message: String) : IOException(message)
}
