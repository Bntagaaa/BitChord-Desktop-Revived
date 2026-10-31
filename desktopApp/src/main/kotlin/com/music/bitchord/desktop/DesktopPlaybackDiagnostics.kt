package com.music.bitchord.desktop

import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong

/** Opt in with -Dbitchord.playback.diagnostics=true. No URL/song/account content is logged. */
internal object DesktopPlaybackDiagnostics {
    val enabled = java.lang.Boolean.getBoolean("bitchord.playback.diagnostics")
    val cacheHit = AtomicLong()
    val cacheMiss = AtomicLong()
    val underruns = AtomicLong()
    private val sequence = AtomicLong()
    private val logger by lazy {
        java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(32),
            { task -> Thread(task, "BitChord-PlaybackTiming").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()).apply { allowCoreThreadTimeOut(true) }
    }
    fun report(task: () -> Unit) { logger.execute(task) }
    fun begin(clickedAt: Long): DesktopPlaybackTrace? = if (enabled) DesktopPlaybackTrace(sequence.incrementAndGet(), clickedAt) else null
}

internal class DesktopPlaybackTrace(private val id: Long, private val click: Long) {
    private val points = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val reported = java.util.concurrent.atomic.AtomicBoolean()
    fun mark(stage: String) { points.putIfAbsent(stage, System.nanoTime()) }
    fun submitted(queuedUs: Long) {
        if (!reported.compareAndSet(false, true)) return
        mark("output")
        // Snapshot a handful of timestamps on the audio thread; format and log on a daemon.
        val snapshot = points.toMap()
        DesktopPlaybackDiagnostics.report {
            fun delta(from: String?, to: String): String {
                val end = snapshot[to] ?: return "unavailable"
                val start = if (from == null) click else snapshot[from] ?: return "unavailable"
                return "%.3f".format(java.util.Locale.ROOT, (end - start) / 1e6)
            }
            val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            DesktopTrackLog.log("playback timing id=$id clickEngineMs=${delta(null, "engine")} engineSourceMs=${delta("engine", "source")} " +
                "sourceInputMs=${delta("source", "input")} inputProbeMs=${delta("input", "probe")} probeCodecMs=${delta("probe", "open")} sourceOpenMs=${delta("source", "open")} openPcmMs=${delta("open", "pcm")} pcmOutputMs=${delta("pcm", "output")} " +
                "submissionProxyMs=${delta(null, "output")} queuedOutputUs=$queuedUs " +
                "cacheHits=${DesktopPlaybackDiagnostics.cacheHit.get()} cacheMisses=${DesktopPlaybackDiagnostics.cacheMiss.get()} " +
                "underruns=${DesktopPlaybackDiagnostics.underruns.get()} heapUsedBytes=${Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()} " +
                "processCpuLoad=${os?.processCpuLoad} processCpuTimeNs=${os?.processCpuTime} (submission is not acoustic TTFAA)")
        }
    }
}
