package com.music.bitchord.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android uses the declared Font weight; Skia also reads OS/2.usWeightClass when deciding
 * whether to embolden a glyph. The bundled Bold cut incorrectly declares 600 instead of 700.
 * Asking that face for the lyrics' logical 800 therefore adds fake bold on desktop, even with
 * FontSynthesis.None. Correct the in-memory classification, preserving every glyph and metric.
 * The distributed upstream files stay unchanged; SFNT table and whole-font checksums stay valid.
 */
internal fun fontBytesWithWeight(bytes: ByteArray, weight: Int): ByteArray {
    require(weight in 1..1000)
    val source = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    require(bytes.size >= 12 && source.getInt(0) == 0x4f54544f) { "Expected an OpenType CFF font" }
    val count = source.getShort(4).toInt() and 0xffff
    require(count <= (bytes.size - 12) / 16)
    data class Table(val entry: Int, val offset: Int, val length: Int)
    val tables = (0 until count).associate { index ->
        val entry = 12 + index * 16
        val offset = source.getInt(entry + 8)
        val length = source.getInt(entry + 12)
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        source.getInt(entry) to Table(entry, offset, length)
    }
    val os2 = requireNotNull(tables[0x4f532f32]) { "Missing OS/2 font table" }
    require(os2.length >= 6)
    if ((source.getShort(os2.offset + 4).toInt() and 0xffff) == weight) return bytes
    // Never invalidate an embedded signature to make a font load. Our five bundled cuts are unsigned.
    require(0x44534947 !in tables) { "Cannot reclassify a signed font" }
    val head = requireNotNull(tables[0x68656164]) { "Missing head font table" }
    require(head.length >= 12)
    val adjusted = bytes.copyOf()
    val buffer = ByteBuffer.wrap(adjusted).order(ByteOrder.BIG_ENDIAN)
    buffer.putShort(os2.offset + 4, weight.toShort())
    fun checksum(offset: Int, length: Int): Int {
        var total = 0
        for (word in 0 until length step 4) {
            var value = 0
            for (byte in 0..3) {
                value = (value shl 8) or if (word + byte < length) adjusted[offset + word + byte].toInt() and 0xff else 0
            }
            total += value
        }
        return total
    }
    buffer.putInt(os2.entry + 4, checksum(os2.offset, os2.length))
    buffer.putInt(head.offset + 8, 0)
    buffer.putInt(head.entry + 4, checksum(head.offset, head.length))
    buffer.putInt(head.offset + 8, (0xb1b0afbaL - (checksum(0, adjusted.size).toLong() and 0xffffffffL)).toInt())
    return adjusted
}
