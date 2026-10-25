package com.music.bitchord.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Watches the local-music folders and says when something in them changed.
 *
 * Android leans on MediaStore, which tells the app when the device's audio collection changes. A
 * desktop has no such index, so this registers the folders with a [WatchService] and reports edits
 * itself — otherwise a track dropped into the folder only ever appears on the next launch.
 *
 * Changes are coalesced: unpacking an album fires one event per file, and re-scanning the library
 * for each of them would walk the tree dozens of times for one copy.
 */
internal object DesktopLocalMusicWatcher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits once, quietly, after a burst of edits has settled. */
    val changes: SharedFlow<Unit> = _changes

    private var job: Job? = null

    /** How long the folders have to be quiet before a change is reported. */
    private const val SETTLE_MS = 1_500L

    /** How deep a watched tree is registered. The scan itself walks four. */
    private const val WATCH_DEPTH = 4

    /** (Re)starts watching whatever [DesktopLocalMusic.roots] currently names. */
    suspend fun restart() {
        val ready = CompletableDeferred<Unit>()
        synchronized(this) {
            val previous = job
            previous?.cancel()
            job = scope.launch {
                try {
                    previous?.join()
                    watch(ready)
                } finally {
                    // Unavailable roots/watch services still allow an ordinary one-shot scan.
                    ready.complete(Unit)
                }
            }
        }
        ready.await()
    }

    @Synchronized
    fun stop() {
        job?.cancel()
    }

    private suspend fun watch(ready: CompletableDeferred<Unit>) {
        val service = runCatching { FileSystems.getDefault().newWatchService() }.getOrNull() ?: return
        try {
            val context = kotlin.coroutines.coroutineContext
            val roots = DesktopLocalMusic.roots().filter(Files::isDirectory)
            val registered = roots.sumOf { register(it, service, WATCH_DEPTH) { context[Job]?.isActive != false } }
            DesktopStartupDiagnostics.mark("local-watcher-register")
            ready.complete(Unit)
            if (registered == 0) return

            var pendingSince = 0L
            while (currentScopeActive()) {
                // Polled rather than blocked on, so cancellation is noticed and a settled burst can
                // be reported without waiting for the next edit to arrive.
                val key = service.poll(250, TimeUnit.MILLISECONDS)
                if (key != null) {
                    var interesting = false
                    key.pollEvents().forEach { event ->
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            interesting = true
                        } else {
                            val changed = event.context() as? Path ?: return@forEach
                            val parent = key.watchable() as? Path
                            val target = parent?.resolve(changed)
                            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && target != null && Files.isDirectory(target)) {
                                val root = roots.firstOrNull { target.startsWith(it) }
                                val depth = root?.let { WATCH_DEPTH - it.relativize(target).nameCount } ?: 0
                                if (depth > 0) register(target, service, depth) { context[Job]?.isActive != false }
                                interesting = true
                            }
                            // A removed directory cannot be stat'ed, but may have held music.
                            if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE ||
                                changed.toString().substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
                            ) interesting = true
                        }
                    }
                    key.reset()
                    if (interesting) pendingSince = (System.nanoTime() / 1_000_000)
                }
                if (pendingSince > 0L && (System.nanoTime() / 1_000_000) - pendingSince >= SETTLE_MS) {
                    pendingSince = 0L
                    _changes.tryEmit(Unit)
                }
            }
        } finally {
            runCatching { service.close() }
        }
    }

    private suspend fun currentScopeActive(): Boolean {
        // A cheap suspension point, so the loop is cancellable between polls.
        delay(1)
        return kotlin.coroutines.coroutineContext[Job]?.isActive != false
    }

    /** Registers [root] and everything under it, returning how many directories were taken. */
    private fun register(root: Path, service: WatchService, depth: Int, active: () -> Boolean): Int {
        var count = 0
        runCatching {
            Files.walkFileTree(
                root,
                emptySet(),
                depth,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!active()) return FileVisitResult.TERMINATE
                        runCatching {
                            dir.register(
                                service,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                            )
                            count++
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
                },
            )
        }
        return count
    }

    private val AUDIO_EXTENSIONS = setOf("aac", "flac", "m4a", "mp3", "ogg", "wav", "webm")
}
