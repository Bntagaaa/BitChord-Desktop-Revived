package com.music.bitchord.desktop

import bitchord.desktopapp.generated.resources.Res
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DesktopFontMetadataTest {
    @Test fun bundledBoldIsClassifiedAsBoldWithoutChangingItsOutlinesOrMetrics() = runBlocking {
        val source = Res.readBytes("font/sf_pro_display_bold.otf")
        val untouched = source.copyOf()
        val fixed = fontBytesWithWeight(source, 700)
        val before = tables(source)
        val after = tables(fixed)
        val os2 = before.getValue("OS/2")
        assertEquals(600, buffer(source).getShort(os2.first + 4).toInt())
        assertEquals(700, buffer(fixed).getShort(os2.first + 4).toInt())
        assertContentEquals(untouched, source, "Never modify the cached upstream font bytes")
        assertEquals(before, after)
        for ((tag, range) in before) {
            if (tag == "head" || tag == "OS/2") continue
            assertContentEquals(source.copyOfRange(range.first, range.last + 1),
                fixed.copyOfRange(range.first, range.last + 1), tag)
        }
        val b = buffer(fixed)
        val count = b.getShort(4).toInt() and 0xffff
        repeat(count) { index ->
            val entry = 12 + index * 16
            val offset = b.getInt(entry + 8)
            val length = b.getInt(entry + 12)
            val data = fixed.copyOfRange(offset, offset + length)
            if (b.getInt(entry) == 0x68656164) buffer(data).putInt(8, 0)
            assertEquals(b.getInt(entry + 4), checksum(data), "SFNT table checksum at $entry")
        }
        assertEquals(0xb1b0afbaL, checksum(fixed).toLong() and 0xffffffffL)
        assertSame(fixed, fontBytesWithWeight(fixed, 700), "Classification must be idempotent")
    }

    @Test fun correctlyClassifiedCutsAreUsedUnchanged() = runBlocking {
        for ((cut, weight) in listOf("regular" to 400, "medium" to 500, "semibold" to 600, "heavy" to 800)) {
            val bytes = Res.readBytes("font/sf_pro_display_$cut.otf")
            assertSame(bytes, fontBytesWithWeight(bytes, weight), cut)
        }
    }

    @Test fun malformedFontDataIsRejected() {
        assertFailsWith<IllegalArgumentException> { fontBytesWithWeight(byteArrayOf(), 700) }
        assertFailsWith<IllegalArgumentException> { fontBytesWithWeight(ByteArray(12), 700) }
    }

    private fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    private fun tables(bytes: ByteArray): Map<String, IntRange> {
        val b = buffer(bytes)
        return (0 until (b.getShort(4).toInt() and 0xffff)).associate { index ->
            val entry = 12 + index * 16
            String(bytes, entry, 4, Charsets.US_ASCII) to
                (b.getInt(entry + 8) until b.getInt(entry + 8) + b.getInt(entry + 12))
        }
    }
    private fun checksum(bytes: ByteArray): Int {
        val padded = bytes.copyOf((bytes.size + 3) / 4 * 4)
        val b = buffer(padded)
        var sum = 0
        while (b.hasRemaining()) sum += b.int
        return sum
    }
}
