package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.music.bitchord.data.canvas.CanvasArtwork
import java.awt.image.BufferedImage
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LandscapeArtworkTest {
    @Test fun stillArtworkDrawsDuringFadeAndReturnsWhenCanvasStops() {
        val cover = Files.createTempFile("landscape-cover", ".png").toFile()
        try {
            val red = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until red.height) for (x in 0 until red.width) red.setRGB(x, y, 0xff0000)
            check(ImageIO.write(red, "png", cover))
            val spec = AtomicReference<CanvasVideoSpec>()
            val host = Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) {
                _, method, args ->
                check(method.name == "CanvasVideo") { method.name }
                spec.set(args[0] as CanvasVideoSpec)
                null
            } as PlayerHost
            PlayerPlatform.withHostForTest(host) {
                val loaded = mutableStateOf(false)
                val rendered = mutableStateOf(false)
                runDesktopComposeUiTest(width = 128, height = 128) {
                    setContent {
                        val context = LocalPlatformContext.current
                        val request = remember(context) { ImageRequest.Builder(context).data(cover).build() }
                        LandscapeArtwork(
                            artRequest = request,
                            artLoaded = loaded.value,
                            onArtState = { loaded.value = it is AsyncImagePainter.State.Success },
                            canvas = CanvasArtwork("fixture:animation"),
                            canvasRendered = rendered.value,
                            isPlaying = true,
                            onCanvasRenderedChange = { rendered.value = it },
                            playbackIdentity = "track-id",
                            modifier = Modifier.fillMaxSize().testTag("artwork"),
                        )
                    }
                    waitUntil(timeoutMillis = 5_000) { loaded.value && spec.get() != null }
                    assertEquals("track-id", spec.get().playbackIdentity)
                    assertEquals(0, spec.get().frameCapturePx)
                    fun sampleRed() = onNodeWithTag("artwork").captureToImage().toPixelMap()[64, 64].red
                    assertTrue(sampleRed() > 0.9f)
                    runOnIdle {
                        spec.get().onRenderedChanged(true)
                        spec.get().onCoverChanged(.5f)
                    }
                    assertTrue(sampleRed() > 0.9f, "The fade still draws over the cover")
                    runOnIdle { spec.get().onCoverChanged(1f) }
                    assertTrue(sampleRed() < .8f, "The opaque video no longer needs the cover draw")
                    runOnIdle { spec.get().onRenderedChanged(false) }
                    assertTrue(sampleRed() > 0.9f, "The still cover returns when video is unavailable")
                }
            }
        } finally { Files.deleteIfExists(cover.toPath()) }
    }
}
