package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class DesktopMotionFrameBuffersTest {
    @Test fun reusesThreeBuffersWithoutAllocatingOnEveryFrame() {
        val buffers = DesktopMotionFrameBuffers(48)
        val first = buffers.next()
        val second = buffers.next()
        val third = buffers.next()
        assertNotSame(first, second)
        assertNotSame(second, third)
        assertNotSame(first, third)
        first[0] = 42
        val wrapped = buffers.next()
        assertSame(first, wrapped)
        assertEquals(42, wrapped[0].toInt())
        assertSame(second, buffers.next())
        assertEquals(48, third.size)
    }

    @Test fun rejectsInvalidPoolSizes() {
        assertFailsWith<IllegalArgumentException> { DesktopMotionFrameBuffers(0) }
        assertFailsWith<IllegalArgumentException> { DesktopMotionFrameBuffers(8, 2) }
    }
}
