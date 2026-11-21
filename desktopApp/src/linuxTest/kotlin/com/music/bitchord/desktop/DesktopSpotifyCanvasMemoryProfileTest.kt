package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.ui.ProvideDesktopPresentationLifecycle
import com.music.bitchord.ui.player.CanvasContentMode
import com.music.bitchord.ui.player.CanvasVideoSpec
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import jdk.jfr.Recording
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/** Visible Compose / OpenGL profile of the production Spotify Canvas composable. */
class DesktopSpotifyCanvasMemoryProfileTest {
    @Test
    fun profileCanvasHideAndDisable() {
        val directory = System.getenv("BITCHORD_MV_PROFILE_DIR")
        assumeTrue("Supply a profile directory and local clip", directory != null)
        val output = File(checkNotNull(directory))
        val clip = File(requireNotNull(System.getenv("BITCHORD_MV_PROFILE_CLIP")))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val path = "/canvas-${UUID.randomUUID()}.mp4"
        server.createContext(path) { request ->
            request.sendResponseHeaders(200, clip.length())
            request.responseBody.use { sink -> clip.inputStream().use { it.copyTo(sink) } }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}$path"
        val artwork = CanvasArtwork(url = url, title = "Fixture", artist = "Fixture", album = "Fixture",
            source = CanvasSource.SPOTIFY)
        val source = MotionVideoSource(url, null, DesktopCanvasDecoder.MAX_EDGE, MotionVideoSource.Clock.NowPlaying,
            artwork.title)
        val sessions = UnifiedVideoEngine.javaClass.methods.firstOrNull { it.name.startsWith("hasMotionSession") }
        fun active() = sessions?.invoke(UnifiedVideoEngine, source) as? Boolean

        try {
            Recording().use { recording ->
                recording.enable("jdk.ThreadAllocationStatistics").withPeriod(Duration.ofSeconds(1))
                recording.start()
                output.resolve("pid").writeText(ProcessHandle.current().pid().toString())
                val phases = output.resolve("phases.csv")
                phases.writeText("epoch_ms,cycle,stage,heap_used_bytes,heap_committed_bytes,running_session,backdrop_sample_bytes\n")
                var result: Result<Unit>? = null
                application(exitProcessOnExit = false) {
                    var enabled by remember { mutableStateOf(false) }
                    var visible by remember { mutableStateOf(true) }
                    var rendered by remember { mutableStateOf(false) }
                    var sampleBytes by remember { mutableIntStateOf(0) }
                    fun record(cycle: Int, stage: String) {
                        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
                        phases.appendText("${System.currentTimeMillis()},$cycle,$stage,${heap.used},${heap.committed}," +
                            "${active() ?: ""},$sampleBytes\n")
                    }
                    Window(onCloseRequest = ::exitApplication, title = "BitChord Spotify Canvas memory profile",
                        state = rememberWindowState(width = 1220.dp, height = 780.dp)) {
                        ProvideDesktopPresentationLifecycle(visible) {
                            Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                                if (enabled) DesktopCanvasVideo(CanvasVideoSpec(
                                    canvas = artwork, isPlaying = true, contentMode = CanvasContentMode.FIT_PORTRAIT,
                                    alignPortraitTop = true, onAspectRatioChanged = {}, portraitRevealBounds = IntSize.Zero,
                                    presentationAlpha = { 1f }, onRenderedChanged = {
                                        rendered = it
                                        if (!it) sampleBytes = 0
                                    },
                                    onFrameCaptured = { sampleBytes = it.width * it.height * 4 },
                                    refreshFrameEveryMs = 2_000, frameCapturePx = 128, onCoverChanged = {},
                                    bottomFade = 0.5f, bottomFadeEndPx = null, pausedForTransition = false,
                                ), Modifier.fillMaxSize())
                            }
                        }
                        LaunchedEffect(Unit) {
                            try {
                                result = runCatching {
                                    output.resolve("render-api").writeText(window.renderApi.toString())
                                    delay(10_000)
                                    for (cycle in 1..2) {
                                        record(cycle, "idle")
                                        visible = true
                                        enabled = true
                                        withTimeout(10_000) { while (!rendered || sampleBytes == 0) delay(25) }
                                        delay(10_000)
                                        record(cycle, "playing")
                                        if (cycle == 1) visible = false else enabled = false
                                        for ((wait, stage) in listOf(1000L to "1s", 4000L to "5s",
                                            10_000L to "15s", 15_000L to "30s")) {
                                            delay(wait)
                                            record(cycle, stage)
                                            assertFalse(rendered)
                                            check(active() != true && sampleBytes == 0)
                                        }
                                    }
                                }
                            } finally { exitApplication() }
                        }
                    }
                }
                recording.stop()
                recording.dump(output.resolve("allocations.jfr").toPath())
                checkNotNull(result) { "Profile window was closed" }.getOrThrow()
            }
        } finally {
            server.stop(0)
            DesktopMediaCache.existing(url, "mp4")?.let(Files::deleteIfExists)
        }
    }
}
