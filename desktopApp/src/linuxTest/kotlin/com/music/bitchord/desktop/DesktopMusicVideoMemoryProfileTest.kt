package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.Song
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import jdk.jfr.Recording
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assume.assumeTrue

/** Opt-in visible-window profile. Uses the production software decoder, session and painter. */
class DesktopMusicVideoMemoryProfileTest {
    interface Allocator : Library {
        fun fopen(path: String, mode: String): Pointer?
        fun malloc_info(options: Int, file: Pointer): Int
        fun fclose(file: Pointer): Int
    }

    private val allocator by lazy { runCatching { Native.load("c", Allocator::class.java) }.getOrNull() }

    private fun allocatorSnapshot(path: File) {
        val libc = allocator ?: return
        val file = libc.fopen(path.absolutePath, "w") ?: return
        try { libc.malloc_info(0, file) } finally { libc.fclose(file) }
    }

    private fun drmResidentKiB(): Long? {
        val entries = File("/proc/self/fdinfo").listFiles() ?: return null
        val clients = linkedMapOf<Pair<String, String>, Long>()
        for (entry in entries) {
            val lines = runCatching { entry.readLines() }.getOrNull() ?: continue
            val client = lines.firstOrNull { it.startsWith("drm-client-id:") } ?: continue
            val device = lines.firstOrNull { it.startsWith("drm-pdev:") }.orEmpty()
            val resident = lines.filter { it.startsWith("drm-resident-") }.sumOf {
                it.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0
            }
            clients[device to client] = resident
        }
        return clients.values.takeIf { it.isNotEmpty() }?.sum()
    }
    @Test
    fun profile1080pAcrossRepeatedWindowLifetimes() {
        val directory = System.getenv("BITCHORD_MV_PROFILE_DIR")
        assumeTrue("Supply BITCHORD_MV_PROFILE_DIR to profile a visible Compose window", directory != null)
        val clip = requireNotNull(System.getenv("BITCHORD_MV_PROFILE_CLIP"))
        val output = File(checkNotNull(directory)).apply { mkdirs() }
        val cycles = System.getenv("BITCHORD_MV_PROFILE_CYCLES")?.toInt() ?: 2
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val track = Song("fixture", "1080p memory fixture", "Local fixture", null, isVideo = true)
        val clock = MutableStateFlow(DesktopPlaybackState(song = track, durationMs = 80_000))
        val decoders = AtomicInteger()
        val decoded = AtomicInteger()
        val submitted = AtomicInteger()
        val session = DesktopVideoSession(scope, clock, { 1f },
            resolve = { id, _ -> InnerTubeXResolver.VideoStream(id, clip, 1920, 1080, "video/mp4", emptyMap(), listOf(1080)) },
            decoderFactory = { job ->
                val decoder = UnifiedVideoEngine.musicDecoder(job)
                object : DesktopVideoDecoder by decoder {
                    private var opened = false
                    override fun open(stream: InnerTubeXResolver.VideoStream) {
                        decoder.open(stream)
                        opened = true
                        decoders.incrementAndGet()
                    }
                    override fun nextFrame(accept: (Long) -> Boolean): VideoFrame? =
                        decoder.nextFrame(accept)?.also { decoded.incrementAndGet() }
                    override fun close() {
                        try { decoder.close() } finally { if (opened) decoders.decrementAndGet() }
                    }
                }
            },
        )
        // The same harness may be run with the pre-change main classes ahead of the new jar.
        @Suppress("UNCHECKED_CAST")
        val frames = session.javaClass.methods.first {
            it.name.startsWith("getFrame") && it.parameterCount == 0
        }.invoke(session) as StateFlow<Pair<String, VideoFrame>?>
        val resources = session.javaClass.methods.firstOrNull { it.name == "resources" }
        val jcmd = File(System.getProperty("java.home"), "bin/jcmd")
        val phases = output.resolve("phases.csv")
        phases.writeText("epoch_ms,cycle,stage,heap_used_bytes,heap_committed_bytes,active_decoders,owned_frames,frame_payload_bytes,decoded,submitted,drm_resident_kib,active_sessions\n")
        fun record(cycle: Int, stage: String) {
            val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
            val frame = frames.value?.second
            val bytes = frame?.let { it.width.toLong() * it.height * 4 } ?: 0L
            phases.appendText("${System.currentTimeMillis()},$cycle,$stage,${heap.used},${heap.committed}," +
                "${decoders.get()},${if (frame == null) 0 else 1},$bytes,${decoded.get()},${submitted.get()},${drmResidentKiB() ?: ""}," +
                "${(resources?.invoke(session) as? MusicVideoResources)?.sessions ?: ""}\n")
            allocatorSnapshot(output.resolve("allocator-$cycle-$stage.xml"))
            if (stage in listOf("idle", "paused", "60s") && jcmd.canExecute()) {
                val command = ProcessBuilder(jcmd.path, ProcessHandle.current().pid().toString(),
                    "VM.native_memory", "summary").redirectErrorStream(true)
                    .redirectOutput(output.resolve("nmt-$cycle-$stage.txt")).start()
                if (!command.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) command.destroyForcibly()
            }
        }
        var result: Result<Unit>? = null
        try {
            Recording().use { recording ->
                recording.enable("jdk.ThreadAllocationStatistics").withPeriod(Duration.ofSeconds(1))
                recording.enable("jdk.ObjectAllocationSample").withStackTrace()
                recording.start()
                output.resolve("pid").writeText(ProcessHandle.current().pid().toString())
                application(exitProcessOnExit = false) {
                    val windowState = rememberWindowState(width = 1220.dp, height = 780.dp)
                    var surface by remember { mutableStateOf(false) }
                    var slot by remember { mutableIntStateOf(0) }
                    Window(state = windowState, title = "BitChord 1080p memory profile", onCloseRequest = ::exitApplication) {
                        Box(Modifier.fillMaxSize().background(Color(0xff213040))) {
                            if (surface) key(slot) {
                                DisposableEffect(Unit) {
                                    session.attach(track.videoId)
                                    onDispose { session.detach(track.videoId) }
                                }
                                val current = frames.collectAsState()
                                val size by remember { derivedStateOf { current.value?.second?.intrinsicSize } }
                                size?.let { dimensions ->
                                    val painter = remember(dimensions) {
                                        VideoSurfacePainter(dimensions, { current.value },
                                            { session.withFrame(track.videoId, it) }) { submitted.incrementAndGet() }
                                    }
                                    Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                }
                            }
                        }
                        LaunchedEffect(Unit) {
                            try {
                                result = runCatching {
                                    output.resolve("render-api").writeText(window.renderApi.toString())
                                    delay(15_000)
                                    repeat(cycles) { index ->
                                        val cycle = index + 1
                                        record(cycle, "idle")
                                        val before = submitted.get()
                                        clock.value = clock.value.copy(isPlaying = true, positionMs = 0,
                                            positionSampledAtNanos = System.nanoTime(), seeks = clock.value.seeks + 1)
                                        windowState.placement = WindowPlacement.Fullscreen
                                        surface = true
                                        withTimeout(10_000) {
                                            while (submitted.get() == before) delay(20)
                                        }
                                        record(cycle, "first_draw")
                                        var sampled = false
                                        session.withFrame(track.videoId) { frame ->
                                            assertEquals(1920, frame.width)
                                            assertEquals(1080, frame.height)
                                            val pixels = checkNotNull(frame.sample(24)).toPixelMap()
                                            val center = pixels[pixels.width / 2, pixels.height / 2]
                                            assertTrue((0 until pixels.height).any { y ->
                                                (0 until pixels.width).any { x -> pixels[x, y] != center }
                                            }, "Video fixture must contain visible color detail")
                                            sampled = true
                                        }
                                        assertTrue(sampled, "No frame in the presentation slot")
                                        delay(60_000)
                                        val paused = videoClockPosition(clock.value, 1f)
                                        clock.value = clock.value.copy(isPlaying = false, positionMs = paused)
                                        record(cycle, "paused")
                                        delay(1000)
                                        clock.value = clock.value.copy(isPlaying = true, positionSampledAtNanos = System.nanoTime())
                                        for (time in listOf(15_000L, 45_000L, 30_000L)) {
                                            clock.value = clock.value.copy(positionMs = time,
                                                positionSampledAtNanos = System.nanoTime(), seeks = clock.value.seeks + 1)
                                            delay(750)
                                        }
                                        windowState.placement = WindowPlacement.Floating
                                        slot++
                                        delay(500)
                                        record(cycle, "windowed")
                                        surface = false
                                        record(cycle, "detach")
                                        for ((wait, stage) in listOf(1000L to "1s", 4000L to "5s", 10_000L to "15s",
                                            15_000L to "30s", 30_000L to "60s")) {
                                            delay(wait)
                                            record(cycle, stage)
                                            assertEquals(0, decoders.get(), stage)
                                            assertEquals(null, frames.value, stage)
                                            (resources?.invoke(session) as? MusicVideoResources)?.let {
                                                assertEquals(MusicVideoResources(0, 0, 0, 0, 0, 0), it, stage)
                                            }
                                        }
                                        assertTrue(submitted.get() - before > 100, "Insufficient rendered frames")
                                    }
                                }
                            } finally { exitApplication() }
                        }
                    }
                }
                recording.stop()
                recording.dump(Path.of(directory, "allocations.jfr"))
            }
            checkNotNull(result) { "Profile window was closed" }.getOrThrow()
        } finally {
            session.close()
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        }
    }
}
