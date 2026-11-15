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
        assertSame(first, buffers.next())
        assertEquals(42, buffers.next()[0].let { first[0].toInt() })
        assertEquals(48, third.size)
    }

    @Test fun rejectsInvalidPoolSizes() {
        assertFailsWith<IllegalArgumentException> { DesktopMotionFrameBuffers(0) }
        assertFailsWith<IllegalArgumentException> { DesktopMotionFrameBuffers(8, 2) }
    }
}
