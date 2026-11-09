package com.music.bitchord

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.music.bitchord.ui.DesktopPresentationOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopPresentationLifetimeTest {
    @Test fun hiddenWindowsStopPresentationSubscriptionsWhileUnfocusedWindowsStayStarted() {
        val owner = DesktopPresentationOwner()
        owner.update(visible = true, focused = true)
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
        owner.update(visible = true, focused = false)
        assertEquals(Lifecycle.State.STARTED, owner.lifecycle.currentState)
        owner.update(visible = false, focused = false)
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)
        owner.update(visible = true, focused = true)
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
        owner.close()
        owner.update(visible = true, focused = true)
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    @Test fun windowSubscriptionsStopWhileHiddenAndResumeWithTheLatestServiceValue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = DesktopPresentationOwner()
        try {
            val service = MutableStateFlow(0)
            val received = mutableListOf<Int>()
            val subscription = launch {
                owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    service.collect { received += it }
                }
            }
            owner.update(visible = true, focused = true); runCurrent()
            assertEquals(listOf(0), received)
            owner.update(visible = false, focused = false); runCurrent()
            service.value = 1; runCurrent()
            assertEquals(listOf(0), received)
            assertEquals(1, service.value)
            assertTrue(subscription.isActive)
            owner.update(visible = true, focused = false); runCurrent()
            assertEquals(listOf(0, 1), received)
            owner.close(); runCurrent()
            assertTrue(subscription.isCompleted)
        } finally {
            owner.close()
            Dispatchers.resetMain()
        }
    }
}
