package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurredBackdropJvmTest {
    @Test
    fun uniformArtworkColorIsPreserved() {
        val color = 0xFF4162B4.toInt()
        val pixels = IntArray(48 * 20) { color }

        blurBackdropPixels(pixels, 48, 20)

        assertTrue(pixels.all { it == color })
    }

    @Test
    fun blurSoftensBrightDetailsAndSpreadsThemToNeighbors() {
        val width = 48
        val height = 20
        val pixels = IntArray(width * height) { 0xFF000000.toInt() }
        val center = (height / 2) * width + width / 2
        pixels[center] = 0xFFFFFFFF.toInt()

        blurBackdropPixels(pixels, width, height)

        val centerBrightness = pixels[center] and 0xFF
        val neighborBrightness = pixels[center - 1] and 0xFF
        assertTrue("The original white pixel must be softened", centerBrightness < 255)
        assertTrue("Blur should spread light into a neighboring pixel", neighborBrightness > 0)
        assertTrue("Every blurred pixel must remain opaque", pixels.all { (it ushr 24) == 255 })
    }

    @Test
    fun narrowImageIsHandledWithClampedEdges() {
        val pixels = intArrayOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
        )

        blurBackdropPixels(pixels, 2, 2)

        assertEquals(4, pixels.size)
        assertTrue(pixels.all { (it ushr 24) == 255 })
    }
}
