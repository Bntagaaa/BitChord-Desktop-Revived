package com.music.bitchord.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import androidx.compose.ui.graphics.asSkiaBitmap
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/** Explicit microbenchmark entry point. Not part of unit tests or the production application. */
object DesktopMemoryProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.firstOrNull() ?: "canvas-scoped"
        require(mode in setOf("canvas-scoped", "canvas-legacy"))
        val count = args.getOrNull(1)?.toInt() ?: 1800
        val info = ImageInfo.makeN32(540, 540, ColorAlphaType.OPAQUE)
        val pixels = ByteArray(540 * 540 * 4) { it.toByte() }
        val retained = arrayOfNulls<Any>(2)
        val surface = Surface.makeRasterN32Premul(540, 540)
        val canvas = surface.canvas
        val destination = Rect.makeWH(540f, 540f)
        fun convert(index: Int) {
            pixels[0] = index.toByte()
            // Reproduce the release path exactly, including its transient Image/Canvas lifetimes.
            retained[index % retained.size] = if (mode == "canvas-legacy") {
                Image.makeRaster(info, pixels.copyOf(), 540 * 4).toComposeImageBitmap().also { bitmap ->
                    Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { canvas.drawImageRect(it, destination) }
                }
            } else DesktopCanvasFrame(info, pixels).also { it.draw(canvas, destination) }
        }
        repeat(200, ::convert)
        val bean = ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean::class.java)
        val thread = Thread.currentThread().threadId()
        val bytesBefore = bean.getThreadAllocatedBytes(thread)
        val cpuBefore = bean.currentThreadCpuTime
        val gcs = ManagementFactory.getGarbageCollectorMXBeans()
        val gcBefore = gcs.sumOf { it.collectionCount }
        val gcMsBefore = gcs.sumOf { it.collectionTime }
        val start = System.nanoTime()
        repeat(count) { index ->
            convert(index)
            if (count >= 18000 && (index + 1) % 1800 == 0) sample("frame-${index + 1}")
        }
        val elapsed = System.nanoTime() - start
        val allocated = bean.getThreadAllocatedBytes(thread) - bytesBefore
        println("mode=$mode frames=$count elapsedMs=${elapsed / 1_000_000} " +
            "cpuMs=${(bean.currentThreadCpuTime - cpuBefore) / 1_000_000} allocatedBytes=$allocated " +
            "gcCount=${gcs.sumOf { it.collectionCount } - gcBefore} gcMs=${gcs.sumOf { it.collectionTime } - gcMsBefore}")
        val proc = Path.of("/proc/self/smaps_rollup")
        if (Files.exists(proc)) Files.readAllLines(proc).filter {
            it.startsWith("Rss:") || it.startsWith("Pss:") || it.startsWith("Private_Dirty:") || it.startsWith("Anonymous:")
        }.forEach(::println)
        if (Files.exists(Path.of("/proc/self/status"))) Files.readAllLines(Path.of("/proc/self/status")).filter {
            it.startsWith("VmHWM:") || it.startsWith("VmRSS:")
        }.forEach(::println)
        surface.close()
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        println("heapUsed=${heap.used} heapCommitted=${heap.committed} retained=${retained.count { it != null }}")
    }
    private fun sample(phase: String) {
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val proc = Path.of("/proc/self/smaps_rollup")
        val resident = if (Files.exists(proc)) Files.readAllLines(proc).filter {
            it.startsWith("Rss:") || it.startsWith("Pss:") || it.startsWith("Private_Dirty:")
        }.joinToString(" ") else "OS residency unavailable"
        val buffers = ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean::class.java)
            .joinToString { "${it.name}=${it.memoryUsed}" }
        println("phase=$phase heapUsed=${heap.used} heapCommitted=${heap.committed} $buffers $resident")
    }

}
