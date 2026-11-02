package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopPcmWriteTest {
    @Test fun aShortWriteNeverDropsOrRepeatsPCMOnEitherDesktopSinkFormat() {
        for (bytes in listOf(2, 4)) {
            val samples = FloatArray(48) { it.toFloat() }
            val heard = mutableListOf<Float>()
            var frames = 0
            val written = writePcmFully(samples, samples.size, 2, bytes, { pending, count ->
                val taken = minOf(count, 6)
                repeat(taken) { heard += pending[it] }
                taken * bytes
            }, { frames += it })
            assertEquals(samples.toList(), heard)
            assertEquals(24, frames)
            assertEquals(samples.size * bytes, written)
        }
    }

    @Test fun rejectedWritesNeverAdvanceTheMusicalClock() {
        for (invalid in listOf(0, -1, 1, 3, 1000)) {
            var frames = 0
            assertFailsWith<IllegalStateException> {
                writePcmFully(FloatArray(20), 20, 2, 2, { _, _ -> invalid }, { frames += it })
            }
            assertEquals(0, frames)
        }
    }
}
