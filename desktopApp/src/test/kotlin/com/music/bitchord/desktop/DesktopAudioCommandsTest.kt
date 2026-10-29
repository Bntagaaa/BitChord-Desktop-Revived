package com.music.bitchord.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopAudioCommandsTest {
    @Test fun idlePumpWaitsForACommandAndWakesWithoutLosingIt() {
        val commands=DesktopAudioCommands<String>()
        val completed=CountDownLatch(1)
        var received: String?=null
        val worker=Thread { commands.awaitCommand(); received=commands.poll(); completed.countDown() }
        worker.start()
        try {
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(worker.state!=Thread.State.WAITING && System.nanoTime()<until) Thread.yield()
            assertEquals(Thread.State.WAITING,worker.state)
            commands += "play"
            assertTrue(completed.await(2,TimeUnit.SECONDS)); assertEquals("play",received)
            commands += "already queued"
            commands.awaitCommand()
            assertEquals("already queued",commands.poll())
        } finally { worker.interrupt();worker.join(2000) }
    }
    @Test fun shutdownInterruptsAnIdlePump() {
        val commands=DesktopAudioCommands<String>()
        val interrupted=CountDownLatch(1)
        val worker=Thread { try { commands.awaitCommand() } catch (_: InterruptedException) { interrupted.countDown() } }
        worker.start();worker.interrupt()
        assertTrue(interrupted.await(2,TimeUnit.SECONDS));worker.join(2000)
    }
}
