package com.music.bitchord.desktop

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One lazy serial writer. Pending snapshots replace earlier snapshots of the same logical value. */
internal class DesktopPlaybackWriter(private val onFailure: (Throwable) -> Unit = {}) {
    private val lock = Any()
    private val pending = linkedMapOf<String, () -> Unit>()
    private var scheduled = false
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "BitChord-PlaybackStore").apply { isDaemon = true }
    }

    fun submit(key: String, write: () -> Unit) = synchronized(lock) {
        pending[key] = write
        if (!scheduled) {
            scheduled = true
            worker.schedule(::drain, 100, TimeUnit.MILLISECONDS)
        }
    }

    private fun drain() {
        val batch = synchronized(lock) {
            scheduled = false
            pending.values.toList().also { pending.clear() }
        }
        batch.forEach { write -> runCatching(write).onFailure(onFailure) }
    }

    /** Serial barrier: earlier writes finish before the latest pending snapshots are flushed. */
    fun flush() { worker.submit(::drain).get() }
}

internal object DesktopPlaybackWrites {
    private val writer by lazy {
        DesktopPlaybackWriter { DesktopTrackLog.log("playback persistence failed: ${it.javaClass.simpleName}") }
            .also { active ->
                Runtime.getRuntime().addShutdownHook(Thread({ active.flush() }, "BitChord-PlaybackStoreFlush"))
            }
    }
    fun submit(key: String, write: () -> Unit) = writer.submit(key, write)
    fun flush() = writer.flush()
}
