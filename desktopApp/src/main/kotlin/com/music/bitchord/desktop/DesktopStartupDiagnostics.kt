package com.music.bitchord.desktop

import java.io.File

object DesktopStartupDiagnostics {

    val isEnabled: Boolean by lazy {
        System.getProperty("bitchord.perf.diagnostics")?.equals("true", ignoreCase = true) == true ||
            System.getenv("BITCHORD_PERF_DIAGNOSTICS")?.equals("true", ignoreCase = true) == true ||
            System.getenv("BITCHORD_PERF_DIAGNOSTICS") == "1"
    }

    private val startNano = System.nanoTime()

    enum class Milestone(val label: String) {
        PROCESS_ENTRY("Process entry and bootstrap initialization"),
        WINDOW_CREATED("Window created"),
        FIRST_FRAME("First usable UI frame rendered"),
        ACCOUNT_RESTORED("Account / session restored"),
        LOCAL_MUSIC_SCAN("Local Music scan completed"),
        LOCAL_MUSIC_WATCHER("Local Music watcher registered"),
        DOWNLOAD_VERIFY("Download verification completed"),
        HOME_FEED_REQUESTED("Home feed request started"),
        HOME_FEED_RENDERED("Home feed first content rendered"),
        LIBRARY_LOADED("Library content loaded"),
        ANALYSIS_RUNTIME_INIT("Native analyzer initialization completed"),
    }

    fun record(milestone: Milestone, details: String? = null) {
        if (!isEnabled) return
        val elapsedMs = (System.nanoTime() - startNano) / 1_000_000L
        val memoryInfo = memorySummary()
        val extra = details?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
        val message = "[PERF_DIAGNOSTICS] [+$elapsedMs ms] ${milestone.label}$extra | $memoryInfo"
        DesktopTrackLog.log(message)
        println(message)
    }

    fun memorySummary(): String {
        val runtime = Runtime.getRuntime()
        val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val heapMaxMb = runtime.maxMemory() / (1024 * 1024)
        val rssMb = readProcessRssMb()
        val rssText = if (rssMb != null) ", Process RSS: ${rssMb}MB" else ""
        return "JVM Heap: ${heapUsedMb}MB / ${heapMaxMb}MB$rssText"
    }

    private fun readProcessRssMb(): Long? {
        return runCatching {
            val statm = File("/proc/self/statm")
            if (statm.exists()) {
                val parts = statm.readText().trim().split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val pages = parts[1].toLongOrNull()
                    if (pages != null) {
                        return (pages * 4096) / (1024 * 1024)
                    }
                }
            }
            null
        }.getOrNull()
    }
}
