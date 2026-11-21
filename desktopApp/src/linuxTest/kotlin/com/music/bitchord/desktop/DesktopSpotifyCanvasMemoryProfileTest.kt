package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.ProvideDesktopPresentationLifecycle
import com.music.bitchord.ui.player.CanvasContentMode
import com.music.bitchord.ui.player.CanvasVideoSpec
import com.music.bitchord.ui.player.NowPlayingScreen
import com.music.bitchord.ui.player.PlayerHost
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.ui.player.PlayerSettingsSource
import com.music.bitchord.data.settings.PlayerTheme
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
import kotlinx.coroutines.flow.MutableStateFlow
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
        val cover = System.getenv("BITCHORD_CANVAS_PROFILE_COVER")?.let(::File)
        if (cover != null) server.createContext("/cover.png") { request ->
            request.responseHeaders.set("Content-Type", "image/png")
            request.sendResponseHeaders(200, cover.length())
            request.responseBody.use { sink -> cover.inputStream().use { it.copyTo(sink) } }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}$path"
        val artwork = CanvasArtwork(url = url, title = "Fixture", artist = "Fixture", album = "Fixture",
            source = CanvasSource.SPOTIFY)
        val source = MotionVideoSource(url, null, DesktopCanvasDecoder.MAX_EDGE, MotionVideoSource.Clock.NowPlaying,
            artwork.title)
        val sessions = UnifiedVideoEngine.javaClass.methods.firstOrNull { it.name.startsWith("hasMotionSession") }
        fun active() = sessions?.invoke(UnifiedVideoEngine, source) as? Boolean

        val fullscreenSeconds = System.getenv("BITCHORD_CANVAS_FULLSCREEN_SECONDS")?.toIntOrNull()?.takeIf { it >= 15 && it % 15 == 0 }
        try {
            Recording().use { recording ->
                recording.enable("jdk.ThreadAllocationStatistics").withPeriod(Duration.ofSeconds(1))
                recording.enable("jdk.ObjectAllocationSample").withStackTrace()
                recording.start()
                output.resolve("pid").writeText(ProcessHandle.current().pid().toString())
                val phases = output.resolve("phases.csv")
                phases.writeText("epoch_ms,cycle,stage,heap_used_bytes,heap_committed_bytes,running_session,backdrop_sample_bytes\n")
                var result: Result<Unit>? = null
                application(exitProcessOnExit = false) {
                    if (fullscreenSeconds != null) {
                        ProfileFullscreenCanvas(artwork, cover?.let { "http://127.0.0.1:${server.address.port}/cover.png" },
                            fullscreenSeconds, output) {
                            result = it
                            exitApplication()
                        }
                        return@application
                    }
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


@Composable
private fun ProfileFullscreenCanvas(
    artwork: CanvasArtwork,
    coverUrl: String?,
    seconds: Int,
    output: File,
    finish: (Result<Unit>) -> Unit,
) {
    val song = remember(artwork) { Song("canvas-fullscreen", artwork.title.orEmpty(), artwork.artist.orEmpty(), coverUrl) }
    val position = remember { PlaybackPosition() }
    val enabled = remember { MutableStateFlow(false) }
    var playing by remember { mutableStateOf(false) }
    val source = remember(artwork) {
        MotionVideoSource(artwork.url, null, DesktopCanvasDecoder.MAX_EDGE,
            MotionVideoSource.Clock.NowPlaying, artwork.title)
    }
    // The landscape layout uses the clip title in this revision. Other layouts use the song ID.
    val sourceWithTrack = remember(source, song) { source.copy(track = song.videoId) }
    fun active() = UnifiedVideoEngine.hasMotionSession(source) || UnifiedVideoEngine.hasMotionSession(sourceWithTrack)
    val host = remember {
        object : PlayerHost by DesktopPlayerHost {
            override val settings = object : PlayerSettingsSource by DesktopPlayerSettings {
                override val animatedCanvas = enabled
                override val reduceDynamicBlur = MutableStateFlow(false)
                override val playerTheme = MutableStateFlow(PlayerTheme.DEFAULT)
            }
            override fun cachedCanvas(song: Song): CanvasArtwork = artwork
            override suspend fun canvasFor(song: Song): CanvasArtwork = artwork
            @Composable override fun rememberRemoteArtworkUrl(song: Song?): String? = coverUrl
        }.also(PlayerPlatform::install)
    }
    val state = rememberWindowState(placement = WindowPlacement.Fullscreen, width = 1220.dp, height = 780.dp)
    val phases = remember { output.resolve("phases.csv").apply {
        writeText("epoch_ms,stage,heap_used_bytes,heap_committed_bytes,running_session\n")
    } }
    fun record(stage: String) {
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        phases.appendText("${System.currentTimeMillis()},$stage,${heap.used},${heap.committed},${active()}\n")
    }
    Window(state = state, title = "BitChord fullscreen animated artwork profile", onCloseRequest = {
        finish(Result.failure(IllegalStateException("The profile window closed before sampling finished")))
    }) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) {
            DesktopPlayerSheet(true, onDismiss = {}, dragToDismiss = false) { width, height ->
                NowPlayingScreen(song = song, isPlaying = playing, isLoading = false,
                    position = position, durationMs = 400_000, audioVersionSwitching = false,
                    qualityUpgraded = false, queue = listOf(song), queueIndex = 0, hasPrevious = false,
                    hasNext = false, repeatMode = 0, shuffleEnabled = false, autoplayEnabled = false,
                    signedIn = true, accountName = null, likeStatus = LikeStatus.INDIFFERENT,
                    onToggleLike = {}, onPlayPause = {}, onNext = {}, onPrevious = {}, onBlockedControl = {},
                    onSeek = {}, onSeekFraction = {}, onToggleShuffle = {}, onCycleRepeat = {}, onToggleAutoplay = {},
                    onJumpTo = {}, onRemoveFromQueue = {}, onMoveInQueue = { _, _ -> }, onClearQueue = {},
                    onOpenMenu = {}, onOpenAlbum = {}, onOpenArtist = { _, _ -> }, onOpenPlaybackSource = {},
                    onListenTogether = {}, lyrics = null, lyricsSource = null, lyricsProviderStates = emptyMap(),
                    onSelectLyricsProvider = {}, lyricsUnavailable = false, lyricsOffsetOpen = false,
                    onDismissLyricsOffset = {}, windowWidth = width, windowHeight = height)
            }
        }
        LaunchedEffect(playing) {
            while (playing) {
                delay(500)
                position.report(position.positionMs + 500)
            }
        }
        LaunchedEffect(host) {
            finish(runCatching {
                output.resolve("render-api").writeText(window.renderApi.toString())
                playing = true
                delay(15_000)
                record("static")
                enabled.value = true
                withTimeout(15_000) { while (!active()) delay(50) }
                repeat(seconds / 15) {
                    delay(15_000)
                    record("playing-${(it + 1) * 15}s")
                }
                playing = false
                repeat(3) {
                    delay(15_000)
                    record("paused-${(it + 1) * 15}s")
                }
                enabled.value = false
                withTimeout(10_000) { while (active()) delay(50) }
                delay(15_000)
                record("disabled")
            })
        }
    }
}
