package com.music.bitchord.desktop

import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async

/** The existing shell has drawn and its native window has reached the event queue. */
internal class DesktopStartup {
    private val shellDrawn = AtomicBoolean()
    private val drawn = CompletableDeferred<Unit>()
    private val showing = CompletableDeferred<Unit>()

    fun nativeWindowShowing() {
        DesktopStartupDiagnostics.once(DesktopStartupDiagnostics.Milestone.WINDOW_VISIBLE)
        showing.complete(Unit)
    }

    fun shellDrawn() {
        if (!shellDrawn.compareAndSet(false, true)) return
        DesktopStartupDiagnostics.once(DesktopStartupDiagnostics.Milestone.SKIKO_READY)
        DesktopStartupDiagnostics.once(DesktopStartupDiagnostics.Milestone.FIRST_FRAME)
        // Give the render callback back to Skiko and post behind queued AWT work.
        EventQueue.invokeLater { drawn.complete(Unit) }
    }

    suspend fun awaitInteractive() {
        showing.await()
        drawn.await()
        DesktopStartupDiagnostics.once(DesktopStartupDiagnostics.Milestone.INTERACTIVE)
    }
}

/** A single initialization shared by startup and early consumers, owned by the caller's scope. */
internal class DesktopStartupTask<T>(scope: CoroutineScope, initialize: suspend () -> T) {
    private val operation = scope.async(start = CoroutineStart.LAZY) { initialize() }
    fun start() { operation.start() }
    suspend fun await(): T = operation.await()
}
