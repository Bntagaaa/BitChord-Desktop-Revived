package com.music.bitchord.desktop

import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Surface

/** Decoder-thread storage. Skia owns copy-on-write when an older snapshot is still being rendered. */
internal class MusicVideoRasterStorage(private val info: ImageInfo, private val rowBytes: Int) : AutoCloseable {
    val bytes = Math.addExact(Math.multiplyExact(rowBytes, info.height), 64)
    private val pixels = Data.makeUninitialized(bytes)
    private val pixmap = try {
        Pixmap.make(info, pixels, rowBytes)
    } catch (failure: Throwable) {
        pixels.close()
        throw failure
    }
    private val surfaces = arrayOfNulls<Surface>(2)
    private var next = 0

    val address: Long get() = pixels.writableData()

    fun snapshot(): Image {
        val index = next
        next = 1 - index
        val surface = surfaces[index] ?: Surface.makeRaster(info).also { surfaces[index] = it }
        // FFmpeg writes into padded scratch. writePixels copies into owned raster storage and
        // forks it if a recorded picture, canvas, or driver upload still owns the old snapshot.
        surface.writePixels(pixmap, 0, 0)
        return surface.makeImageSnapshot()
    }

    override fun close() {
        surfaces.forEach { it?.close() }
        surfaces.fill(null)
        pixmap.close()
        pixels.close()
    }
}
