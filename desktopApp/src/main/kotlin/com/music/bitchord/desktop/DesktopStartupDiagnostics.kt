package com.music.bitchord.desktop

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.BaseTSD.SIZE_T
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.nanoseconds

object DesktopStartupDiagnostics {
    val isEnabled: Boolean by lazy {
        System.getProperty("bitchord.perf.diagnostics")?.equals("true", ignoreCase = true) == true ||
            System.getenv("BITCHORD_PERF_DIAGNOSTICS")?.let { it == "1" || it.equals("true", ignoreCase = true) } == true
    }

    private val startNano = System.nanoTime()
    private val startWallMs = System.currentTimeMillis()
    private val once = ConcurrentHashMap.newKeySet<Milestone>()
    // Capture the timestamp on the calling thread. Process counters and logging can wait on I/O.
    private val reporter by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "BitChord-Startup-Diagnostics").apply { isDaemon = true }
        }
    }

    enum class Milestone(val label: String) {
        LAUNCHER_ENTRY("External launcher invocation"),
        APPIMAGE_ENTRY("AppImage AppRun entered"),
        JVM_START("JVM start"),
        PROCESS_ENTRY("Process entry and bootstrap initialization"),
        BOOTSTRAP_START("Bootstrap started"),
        BOOTSTRAP_COMPLETE("Bootstrap completed"),
        AWT_READY("AWT toolkit ready"),
        WINDOW_CREATED("Window created"),
        WINDOW_VISIBLE("Window visible"),
        SKIKO_READY("Skiko rendering backend available"),
        FIRST_FRAME("First shell draw completed"),
        INTERACTIVE("Shell drawn and AWT input queue reached"),
        PLAYBACK_RESTORED("Playback metadata and queue restored"),
        LOCAL_LIBRARY_RESTORED("Local library restored"),
        AUDIO_READY("Audio processing thread ready"),
        ACCOUNT_RESTORED("Account / session restored"),
        BACKGROUND_STARTED("Background playback host mounted"),
        LOCAL_MUSIC_SCAN("Local Music scan completed"),
        LOCAL_MUSIC_WATCHER("Local Music watcher registered"),
        DOWNLOAD_VERIFY("Download verification completed"),
        HOME_FEED_REQUESTED("Home feed request started"),
        HOME_FEED_RENDERED("Home feed first content rendered"),
        HOME_CACHE_RENDERED("Cached Home content drawn"),
        HOME_FRESH_RENDERED("Fresh Home content drawn"),
        LIBRARY_LOADED("Library content loaded"),
        ANALYSIS_RUNTIME_INIT("Native analyzer initialization completed"),
    }

    /** Called at Kotlin main entry. JVM start predates this clock and comes from RuntimeMXBean. */
    fun processEntry() {
        if (!isEnabled) return
        for ((key, stage) in listOf(
            "BITCHORD_LAUNCH_EPOCH_MS" to Milestone.LAUNCHER_ENTRY,
            "BITCHORD_APPRUN_EPOCH_MS" to Milestone.APPIMAGE_ENTRY,
        )) {
            val timestamp = System.getenv(key)?.toLongOrNull()
            if (timestamp != null && timestamp in (startWallMs - 300_000)..startWallMs) {
                record(stage, elapsedMs = timestamp - startWallMs, observed = false)
            }
        }
        // Sample the RuntimeMXBean on the reporter so class loading here cannot extend bootstrap.
        reporter.execute {
            val jvmStart = ManagementFactory.getRuntimeMXBean().startTime
            record(Milestone.JVM_START, elapsedMs = jvmStart - startWallMs, observed = false)
        }
        record(Milestone.PROCESS_ENTRY)
    }

    fun once(milestone: Milestone) {
        if (isEnabled && once.add(milestone)) record(milestone)
    }

    fun record(
        milestone: Milestone,
        details: String? = null,
        durationNanos: Long? = null,
    ) = record(milestone, details, durationNanos, (System.nanoTime() - startNano).nanoseconds.inWholeMilliseconds)

    private fun record(
        milestone: Milestone,
        details: String? = null,
        durationNanos: Long? = null,
        elapsedMs: Long,
        observed: Boolean = true,
    ) {
        if (!isEnabled) return
        val thread = if (observed) Thread.currentThread().name else "unobserved"
        val ui = if (observed) thread.startsWith("AWT-EventQueue") else null
        val heap = heapSummary()
        val duration = durationNanos?.nanoseconds?.inWholeMilliseconds
        val extra = details?.takeIf(String::isNotBlank)?.let { " - $it" }.orEmpty()
        reporter.execute {
            val rss = readProcessRssMb()
            val cpuMs = ProcessHandle.current().info().totalCpuDuration().orElse(null)?.toMillis()
            val message = "[PERF_DIAGNOSTICS] [%+d ms] %s%s | thread=%s ui_thread=%s duration_ms=%s | %s, sampled_rss_mb=%s, sampled_cpu_ms=%s"
                .format(elapsedMs, milestone.label, extra, thread, ui, duration, heap, rss, cpuMs)
            DesktopTrackLog.log(message)
            println(message)
        }
    }

    fun memorySummary(): String = heapSummary() +
        (readProcessRssMb()?.let { ", Process RSS: ${it}MB" } ?: "")

    private fun heapSummary(): String {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        return "JVM Heap: ${used}MB / ${runtime.maxMemory() / (1024 * 1024)}MB"
    }

    private fun readProcessRssMb(): Long? = runCatching {
        if (DesktopPlatform.isWindows) {
            val counters = ProcessMemoryCounters().apply { cb = size() }
            if (WindowsMemory.api.GetProcessMemoryInfo(Kernel32.INSTANCE.GetCurrentProcess(), counters, counters.size())) {
                counters.workingSetSize.toLong() / (1024 * 1024)
            } else null
        } else {
            // VmRSS already uses KiB; statm needs the host's actual page size.
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.substringAfter(':')?.trim()?.substringBefore(' ')
                    ?.toLongOrNull()?.div(1024)
            }
        }
    }.getOrNull()

    private interface WindowsMemory : StdCallLibrary {
        fun GetProcessMemoryInfo(process: HANDLE, counters: ProcessMemoryCounters, size: Int): Boolean

        companion object {
            val api: WindowsMemory by lazy { Native.load("psapi", WindowsMemory::class.java) }
        }
    }

    @Structure.FieldOrder(
        "cb", "pageFaultCount", "peakWorkingSetSize", "workingSetSize", "quotaPeakPagedPoolUsage",
        "quotaPagedPoolUsage", "quotaPeakNonPagedPoolUsage", "quotaNonPagedPoolUsage", "pagefileUsage",
        "peakPagefileUsage",
    )
    class ProcessMemoryCounters : Structure() {
        @JvmField var cb = 0
        @JvmField var pageFaultCount = 0
        @JvmField var peakWorkingSetSize = SIZE_T()
        @JvmField var workingSetSize = SIZE_T()
        @JvmField var quotaPeakPagedPoolUsage = SIZE_T()
        @JvmField var quotaPagedPoolUsage = SIZE_T()
        @JvmField var quotaPeakNonPagedPoolUsage = SIZE_T()
        @JvmField var quotaNonPagedPoolUsage = SIZE_T()
        @JvmField var pagefileUsage = SIZE_T()
        @JvmField var peakPagefileUsage = SIZE_T()
    }
}
