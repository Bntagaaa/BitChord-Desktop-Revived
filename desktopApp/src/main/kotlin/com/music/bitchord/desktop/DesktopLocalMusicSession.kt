package com.music.bitchord.desktop

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns one watcher, a session snapshot, and serialized scans for a window. */
internal class DesktopLocalMusicSession<T>(
    private val changes: Flow<Unit>,
    private val startWatching: suspend () -> Unit,
    private val stopWatching: () -> Unit,
    private val scan: suspend () -> T,
) {
    private val scans = Mutex()
    private var cachedRevision: Int? = null
    private var cached: T? = null

    suspend fun observe(revision: Int, publish: (T) -> Unit) = coroutineScope {
        suspend fun refresh(force: Boolean) = scans.withLock {
            val value = if (!force && cachedRevision == revision) {
                @Suppress("UNCHECKED_CAST")
                (cached as T)
            } else {
                scan().also { cached = it; cachedRevision = revision }
            }
            publish(value)
        }
        // Install the subscription before registering or scanning, including events raised
        // during registration. A burst is debounced by the watcher and buffered while scanning.
        val edits = launch(start = CoroutineStart.UNDISPATCHED) { changes.collect { refresh(true) } }
        try {
            startWatching()
            refresh(false)
            edits.join()
        } finally { stopWatching() }
    }
}
