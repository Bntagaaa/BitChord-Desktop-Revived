package com.music.bitchord.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopRestoreGcTest {
    @Test fun restoreDuringBlockingCleanupCancelsTheLaterCollection() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val entered=CountDownLatch(1);val finish=CountDownLatch(1);val completed=CountDownLatch(1)
        val visible=AtomicBoolean(false);val collections=AtomicInteger()
        val trimmer=DesktopWindowMemoryTrimmer(scope,trim={entered.countDown();finish.await(2,TimeUnit.SECONDS);completed.countDown()},collect={collections.incrementAndGet()},visible=visible::get,delayMillis=1)
        try {
            trimmer.hidden()
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            visible.set(true);trimmer.shown();finish.countDown()
            assertTrue(completed.await(2,TimeUnit.SECONDS));delay(50)
            assertEquals(0,collections.get())
            visible.set(false);trimmer.hidden()
            withTimeout(2000) { while(collections.get()==0) delay(5) }
            assertEquals(1,collections.get())
        } finally { finish.countDown();scope.cancel() }
    }
}
