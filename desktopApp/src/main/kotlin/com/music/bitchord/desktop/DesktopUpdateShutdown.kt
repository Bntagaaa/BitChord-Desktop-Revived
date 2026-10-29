package com.music.bitchord.desktop

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Native shutdown cannot be interrupted safely. A timeout must not later trigger a surprise exit. */
internal class DesktopUpdateShutdownCoordinator {
    private val running = AtomicBoolean()

    suspend fun finish(timeoutMillis: Long = 30_000, actions: () -> Unit) {
        check(running.compareAndSet(false, true)) { "The previous shutdown attempt is still finishing. Installation was not changed." }
        val completed = CompletableDeferred<Unit>()
        Thread({
            try { actions(); completed.complete(Unit) }
            catch (failure: Throwable) { completed.completeExceptionally(failure) }
            finally { running.set(false) }
        }, "BitChord-Update-Shutdown").apply { isDaemon = true; start() }
        try { withTimeout(timeoutMillis) { completed.await() } }
        catch (timeout: TimeoutCancellationException) {
            throw IllegalStateException("Shutdown did not finish within ${timeoutMillis / 1000} seconds. Installation was not changed; see updater logs and close BitChord manually.", timeout)
        }
    }
}

internal val desktopUpdateShutdown = DesktopUpdateShutdownCoordinator()
