package com.music.bitchord.desktop

import java.awt.EventQueue
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

/** Real Preferences backend and AWT EDT, isolated fake data. No authentication or media contents. */
object DesktopSmoothnessBench {
    @JvmStatic fun main(args: Array<String>) {
        DesktopSmoothnessTrace.install()
        if(args.firstOrNull()?.startsWith("gc-")==true) { gcProbe(args[0]=="gc-explicit"); return }
        val preferences=Preferences.userRoot().node("com.music.bitchord.smoothness-bench")
        val payload="fixture".repeat(140_000)
        DesktopPreferenceChunks.write(preferences,"large-fixture",payload)
        val bean=ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean::class.java)
        var allocated=0L;var cpu=0L
        repeat(20) { EventQueue.invokeAndWait { DesktopPreferenceChunks.write(preferences,"volume","$it") } }
        DesktopSmoothnessTrace.scenario("settings-slider")
        val start=System.nanoTime()
        repeat(100) { index ->
            EventQueue.invokeAndWait {
                val thread=Thread.currentThread().threadId();val before=bean.getThreadAllocatedBytes(thread);val cpuBefore=bean.currentThreadCpuTime
                val operationStart=System.nanoTime()
                DesktopPreferenceChunks.write(preferences,"volume","$index")
                DesktopSmoothnessTrace.record("settings_write",System.nanoTime()-operationStart)
                allocated+=bean.getThreadAllocatedBytes(thread)-before;cpu+=bean.currentThreadCpuTime-cpuBefore
            }
            Thread.sleep(5)
        }
        println("elapsed_ms=${(System.nanoTime()-start)/1e6} edt_cpu_ms=${cpu/1e6} edt_allocated=$allocated")
        DesktopPreferenceFlush.flushPending()
        DesktopSmoothnessTrace.stop()
        DesktopSmoothnessTrace.write()
        preferences.removeNode()
        System.exit(0)
    }
    private fun gcProbe(explicit:Boolean) {
        EventQueue.invokeAndWait {}
        var garbage:Array<ByteArray>? = Array(96) { ByteArray(1024*1024).also { it.fill(7) } }
        println("gc_fixture_bytes=${garbage!!.sumOf { it.size }}")
        garbage=null
        DesktopSmoothnessTrace.scenario(if(explicit) "gc-explicit" else "gc-natural")
        val before=System.nanoTime()
        if(explicit) System.gc()
        println("gc_call_ms=${(System.nanoTime()-before)/1e6}")
        Thread.sleep(600)
        val heap=ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val rss=runCatching { Files.readAllLines(Path.of("/proc/self/status")).first { it.startsWith("VmRSS:") } }.getOrDefault("RSS unavailable")
        println("gc_explicit=$explicit heap_used=${heap.used} heap_committed=${heap.committed} $rss")
        DesktopSmoothnessTrace.stop()
        DesktopSmoothnessTrace.write()
        System.exit(0)
    }
}
