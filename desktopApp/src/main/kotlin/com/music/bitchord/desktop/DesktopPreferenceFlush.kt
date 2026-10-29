package com.music.bitchord.desktop

import java.awt.EventQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

/** Values enter Preferences synchronously; only the expensive disk flush is coalesced. */
internal class DesktopPreferenceFlushQueue(
    private val quietMillis: Long = 150,
    private val maximumMillis: Long = 1_500,
    private val flush: (Preferences) -> Unit = { it.flush() },
) : AutoCloseable {
    private class Pending(
        val since: Long,
        var revision: Long = 0,
        var future: ScheduledFuture<*>? = null,
    )

    private val pending = HashMap<Preferences, Pending>()
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "BitChord-Settings-Flush").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun request(node: Preferences) = synchronized(pending) {
        val now = System.nanoTime()
        val slot = pending.getOrPut(node) { Pending(now) }
        slot.future?.cancel(false)
        val revision = ++slot.revision
        val elapsed = TimeUnit.NANOSECONDS.toMillis(now - slot.since)
        val delay = minOf(quietMillis, (maximumMillis - elapsed).coerceAtLeast(0))
        slot.future = worker.schedule({
            val owns = synchronized(pending) {
                if (pending[node] === slot && slot.revision == revision) {
                    pending.remove(node)
                    true
                } else false
            }
            if (owns) safelyFlush(node)
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun safelyFlush(node: Preferences) {
        runCatching { flush(node) }.onFailure {
            DesktopTrackLog.log(
                "settings: disk flush failed; Preferences retains dirty values for its normal retry (${it.javaClass.simpleName})",
            )
        }
    }

    /** For orderly exit and developer benchmarks, never called by a UI interaction. */
    fun flushPending(timeoutMillis: Long = 3_000) {
        worker.submit {
            val nodes = synchronized(pending) {
                pending.values.forEach { it.future?.cancel(false) }
                pending.keys.toList().also { pending.clear() }
            }
            nodes.forEach(::safelyFlush)
        }.get(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        try { flushPending() } finally { worker.shutdown() }
    }
}

internal object DesktopPreferenceFlush {
    private val queue = DesktopPreferenceFlushQueue()
    // Developer-only replay of the Phase 1 disk behavior, guarded by opt-in tracing.
    private val synchronousBaseline = DesktopSmoothnessTrace.enabled &&
        java.lang.Boolean.getBoolean("bitchord.smoothness.syncPreferences")

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread({ runCatching { queue.flushPending() } }, "BitChord-Settings-Exit"),
        )
    }

    fun request(node: Preferences) {
        // Preserve synchronous durability in background credential/migration code.
        if (EventQueue.isDispatchThread() && !synchronousBaseline) queue.request(node)
        else runCatching { node.flush() }
    }

    fun flushPending() = queue.flushPending()
}
