package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.ui.ProvideDesktopPresentationLifecycle
import com.music.bitchord.ui.player.CanvasContentMode
import com.music.bitchord.ui.player.CanvasVideoSpec
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopUnifiedCanvasUiTest {
    @Test
    fun softwareCanvasDrawsAndSamplesOneClipThenRestoresStillArtworkOnHide() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/video/red-h264.mp4")).use { it.readBytes() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val path = "/fixture-${UUID.randomUUID()}.mp4"
        server.createContext(path) { request ->
            request.sendResponseHeaders(200, fixture.size.toLong())
            request.responseBody.use { it.write(fixture) }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}$path"
        try {
            val surfaceVisible = mutableStateOf(true)
            val canvasEnabled = mutableStateOf(true)
            val playing = mutableStateOf(true)
            val capturePx = mutableStateOf(16)
            val source = MotionVideoSource(url, null, DesktopCanvasDecoder.MAX_EDGE,
                MotionVideoSource.Clock.NowPlaying, "Fixture")
            val submitted = AtomicBoolean(false)
            val sampleSize = AtomicReference<IntSize?>()
            val captures = AtomicInteger()
            runDesktopComposeUiTest(width = 160, height = 120) {
                setContent {
                    ProvideDesktopPresentationLifecycle(surfaceVisible.value) {
                        Box(Modifier.fillMaxSize().background(Color.Blue).testTag("artwork")) {
                            if (canvasEnabled.value) DesktopCanvasVideo(
                                CanvasVideoSpec(
                                    canvas = CanvasArtwork(url = url, title = "Fixture", artist = "Test", album = "Test",
                                        source = CanvasSource.SPOTIFY),
                                    isPlaying = playing.value,
                                    contentMode = CanvasContentMode.CROP,
                                    alignPortraitTop = false,
                                    onAspectRatioChanged = {},
                                    portraitRevealBounds = IntSize.Zero,
                                    presentationAlpha = { 1f },
                                    onRenderedChanged = {
                                        submitted.set(it)
                                        if (!it) sampleSize.set(null)
                                    },
                                    onFrameCaptured = {
                                        sampleSize.set(IntSize(it.width, it.height))
                                        captures.incrementAndGet()
                                    },
                                    refreshFrameEveryMs = 1_000,
                                    frameCapturePx = capturePx.value,
                                    onCoverChanged = {},
                                    bottomFade = .5f,
                                    bottomFadeEndPx = null,
                                    pausedForTransition = false,
                                ),
                                Modifier.fillMaxSize().testTag("canvas"),
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { submitted.get() && sampleSize.get() != null }
                mainClock.advanceTimeBy(400)
                assertEquals(IntSize(16, 12), sampleSize.get())
                val active = onNodeWithTag("artwork").captureToImage().toPixelMap()
                assertTrue(active[80, 20].red > .85f)
                assertTrue(active[80, 118].blue > .85f)
                runOnIdle { playing.value = false }
                mainClock.advanceTimeBy(1_200)
                val pausedSamples = captures.get()
                mainClock.advanceTimeBy(3_000)
                assertEquals(pausedSamples, captures.get(), "A paused frame must not be sampled again")
                runOnIdle {
                    playing.value = true
                    surfaceVisible.value = false
                }
                waitUntil(timeoutMillis = 5_000) {
                    !submitted.get() && !UnifiedVideoEngine.hasMotionSession(source)
                }
                assertEquals(null, sampleSize.get())
                onNodeWithTag("canvas").assertDoesNotExist()
                val hidden = onNodeWithTag("artwork").captureToImage().toPixelMap()
                assertTrue(hidden[80, 20].blue > .95f)

                runOnIdle { surfaceVisible.value = true }
                waitUntil(timeoutMillis = 5_000) { submitted.get() && sampleSize.get() != null }
                assertTrue(UnifiedVideoEngine.hasMotionSession(source))
                runOnIdle { canvasEnabled.value = false }
                waitUntil(timeoutMillis = 5_000) {
                    !submitted.get() && !UnifiedVideoEngine.hasMotionSession(source)
                }
                assertEquals(null, sampleSize.get())
                assertTrue(onNodeWithTag("artwork").captureToImage().toPixelMap()[80, 20].blue > .95f)

                val beforeNoCapture = captures.get()
                runOnIdle {
                    capturePx.value = 0
                    canvasEnabled.value = true
                }
                waitUntil(timeoutMillis = 5_000) { submitted.get() }
                mainClock.advanceTimeBy(2_000)
                assertEquals(beforeNoCapture, captures.get(), "Opting out must also skip the first sample")
            }
        } finally {
            server.stop(0)
            DesktopMediaCache.existing(url, "mp4")?.let(Files::deleteIfExists)
        }
    }
}
