package com.music.bitchord.desktop

import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.channels.Channel
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import com.sun.management.GarbageCollectionNotificationInfo

/** Opt-in application-side timings. These are NOT display presentation or input-to-photon times. */
internal object DesktopSmoothnessTrace {
    val enabled: Boolean = System.getProperty("bitchord.smoothness.output") != null
    private val metrics by lazy { ConcurrentHashMap<String, Samples>() }
    private val lastDraw = AtomicLong()
    private val edtThread = AtomicLong(-1)
    private val runtime by lazy { ArrayList<LongArray>() }
    private val pendingInput = AtomicLong()
    private val pendingNavigation = AtomicLong()
    internal val frameRequests by lazy { Channel<Unit>(Channel.CONFLATED) }
    private val started = AtomicBoolean()
    private val accepting = AtomicBoolean(true)
    private val worker by lazy { Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"BitChord-Perf-Probe").apply { isDaemon=true } } }
    @Volatile private var scenario = "app"
    @Volatile private var shown = true
    private const val LIMIT = 20_000
    private class Samples {
        private val values = LongArray(LIMIT)
        private var count = 0L
        @Synchronized fun add(value: Long) { values[(count++ % LIMIT).toInt()] = value }
        @Synchronized fun snapshot() = values.copyOf(minOf(count,LIMIT.toLong()).toInt()).toList()
    }
    fun record(name: String, nanos: Long) {
        if(enabled && accepting.get() && nanos >= 0) metrics.getOrPut("$scenario/$name") { Samples() }.add(nanos)
    }
    fun scenario(name: String) { scenario=name.replace(Regex("[^a-zA-Z0-9_-]"),"_"); lastDraw.set(0); pendingInput.set(0); pendingNavigation.set(0) }
    fun visibility(visible: Boolean) {
        if(enabled) { shown=visible; lastDraw.set(0); pendingInput.set(0); pendingNavigation.set(0) }
    }
    fun navigation() {
        if(enabled) { pendingNavigation.set(System.nanoTime()); frameRequests.trySend(Unit) }
    }
    fun frameCallback() {
        val now=System.nanoTime()
        pendingInput.getAndSet(0).takeIf { it != 0L }?.let { record("input_dispatch_to_frame_callback",now-it) }
        pendingNavigation.getAndSet(0).takeIf { it != 0L }?.let { record("navigation_to_frame_callback",now-it) }
    }
    fun drawn(start: Long) {
        if(!enabled) return
        val end=System.nanoTime()
        record("draw_cpu_scope",end-start)
        val previous=lastDraw.getAndSet(end)
        if(previous != 0L) record("app_draw_interval",end-previous)
    }
    fun install() {
        if(!enabled || !started.compareAndSet(false,true)) return
        Toolkit.getDefaultToolkit().systemEventQueue.push(object: EventQueue() {
            override fun dispatchEvent(event: AWTEvent) {
                edtThread.compareAndSet(-1,Thread.currentThread().threadId())
                val before=System.nanoTime()
                if(event is InputEvent && shown) { pendingInput.compareAndSet(0,before); frameRequests.trySend(Unit) }
                try { super.dispatchEvent(event) } finally { record("awt_dispatch",System.nanoTime()-before) }
            }
        })
        val pending=AtomicBoolean()
        worker.scheduleAtFixedRate({
            if(pending.compareAndSet(false,true)) {
                val requested=System.nanoTime()
                EventQueue.invokeLater { record("edt_queue_lag",System.nanoTime()-requested); pending.set(false) }
            }
        },100,20,TimeUnit.MILLISECONDS)
        val threadBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        if (threadBean?.isThreadAllocatedMemorySupported == true) threadBean.isThreadAllocatedMemoryEnabled = true
        worker.scheduleAtFixedRate({
            val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
            val cpu = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.processCpuTime ?: -1
            val allocated = edtThread.get().takeIf { it > 0 }?.let { id -> runCatching { threadBean?.getThreadAllocatedBytes(id) ?: -1 }.getOrDefault(-1) } ?: -1
            val rss = runCatching {
                Files.readAllLines(Path.of("/proc/self/status")).first { it.startsWith("VmRSS:") }.substringAfter(':').trim().substringBefore(' ').toLong() * 1024
            }.getOrDefault(-1)
            synchronized(runtime) {
                if (runtime.size < 10_000) runtime.add(longArrayOf(System.nanoTime(),cpu,heap.used,heap.committed,rss,allocated))
            }
        },0,1,TimeUnit.SECONDS)
        ManagementFactory.getGarbageCollectorMXBeans().forEach { gc ->
            (gc as? NotificationEmitter)?.addNotificationListener(NotificationListener { notification,_ ->
                if(notification.type == GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) {
                    val info=GarbageCollectionNotificationInfo.from(notification.userData as javax.management.openmbean.CompositeData)
                    record("gc_collection",TimeUnit.MILLISECONDS.toNanos(info.gcInfo.duration))
                }
            },null,null)
        }
        Runtime.getRuntime().addShutdownHook(Thread({ stop(); write() },"BitChord-Perf-Report"))
    }
    fun stop() { accepting.set(false); worker.shutdownNow() }
    fun write() {
        if(!enabled) return
        val output=Path.of(System.getProperty("bitchord.smoothness.output"))
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        val lines=metrics.toSortedMap().map { (name,samples) ->
            val sorted=samples.snapshot().sorted()
            fun percentile(p: Double) = if(sorted.isEmpty()) 0.0 else sorted[((sorted.size-1)*p).toInt()]/1e6
            val long60=sorted.count { it > 16_666_667L };val long120=sorted.count { it > 8_333_333L }
            "$name,${sorted.size},${percentile(.5)},${percentile(.95)},${percentile(.99)},$long60,$long120"
        }
        val rate=runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode.refreshRate }.getOrDefault(0)
        Files.writeString(output.resolveSibling(output.fileName.toString()+".environment.txt"),
            "java=${System.getProperty("java.version")}\nos=${System.getProperty("os.name")}\nreported_refresh_hz=$rate\nsample_window=last $LIMIT values per metric\nframe_times=application callbacks, not display presentation\n")
        val readings = synchronized(runtime) { runtime.map { it.joinToString(",") } }
        Files.write(output.resolveSibling(output.fileName.toString()+".runtime.csv"),
            listOf("monotonic_ns,process_cpu_ns,heap_used_bytes,heap_committed_bytes,rss_bytes,edt_allocated_bytes") + readings)
        Files.write(output,listOf("metric,count,p50_ms,p95_ms,p99_ms,over_16_67ms,over_8_33ms")+lines)
    }
}
