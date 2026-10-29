package com.music.bitchord.desktop

import java.util.concurrent.ConcurrentLinkedQueue

/** The audio thread sleeps when idle; commands wake it without timer polling or lost wakeups. */
internal class DesktopAudioCommands<T : Any> {
    private val pending = ConcurrentLinkedQueue<T>()
    private val signal = Object()
    operator fun plusAssign(command: T) {
        synchronized(signal) { pending.add(command); signal.notifyAll() }
    }
    fun poll(): T? = pending.poll()
    @Throws(InterruptedException::class)
    fun awaitCommand() {
        synchronized(signal) { while (pending.isEmpty()) signal.wait() }
    }
}
