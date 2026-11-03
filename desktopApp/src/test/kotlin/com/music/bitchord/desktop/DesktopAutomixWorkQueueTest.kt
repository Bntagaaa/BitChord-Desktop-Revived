package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAutomixWorkQueueTest {
    @Test fun currentFirst() {
        val queue = DesktopAutomixWorkQueue<Int>()
        queue.focus("current", "next")
        assertTrue(queue.offer("next", 2))
        assertTrue(queue.offer("current", 1))
        assertEquals("current" to 1, queue.poll())
        assertEquals("next" to 2, queue.poll())
        assertEquals(null, queue.poll())
    }

    @Test fun outdatedTasksAreDiscarded() {
        val queue = DesktopAutomixWorkQueue<Int>()
        queue.focus("a", "b")
        queue.offer("a", 1)
        queue.offer("b", 2)
        queue.focus("c", "d")
        assertFalse(queue.isRelevant("a"))
        assertFalse(queue.offer("a", 3))
        assertEquals(emptyList(), queue.pendingIds())
        assertTrue(queue.offer("d", 4))
        assertTrue(queue.offer("c", 3))
        assertEquals("c" to 3, queue.poll())
        assertEquals("d" to 4, queue.poll())
    }

    @Test fun incomingSongCanBecomeCurrentWithoutLosingItsWork() {
        val queue = DesktopAutomixWorkQueue<Int>()
        queue.focus("a", "b")
        queue.offer("b", 2)
        queue.focus("b", "c")
        assertTrue(queue.isRelevant("b"))
        assertFalse(queue.isRelevant("a"))
        queue.offer("c", 3)
        assertEquals("b" to 2, queue.poll())
        assertEquals("c" to 3, queue.poll())
    }

    @Test fun queueIsBoundedWithoutExplicitFocus() {
        val queue = DesktopAutomixWorkQueue<Int>()
        assertTrue(queue.offer("a", 1))
        assertTrue(queue.offer("b", 2))
        assertFalse(queue.offer("c", 3))
        queue.clear()
        assertTrue(queue.pendingIds().isEmpty())
        assertFalse(queue.isRelevant("a"))
    }
}
