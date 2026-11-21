package com.music.bitchord.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.makeGLWithInterface
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.junit.Assume.assumeTrue

/** Opt-in on a Linux Wayland desktop with VA-API. The test owns its own SDL/EGL host. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DesktopGStreamerTextureProbeTest {
    private val rgba8 = 0x8058
    interface Bridge : Library {
        fun probe_gl_loader(): Pointer
        fun probe_open(clip: String): Pointer?
        fun probe_pull(probe: Pointer, width: IntByReference, height: IntByReference): Int
        fun probe_window_size(probe: Pointer, width: IntByReference, height: IntByReference): Int
        fun probe_resize(probe: Pointer): Int
        fun probe_swap(probe: Pointer): Int
        fun probe_finish_frame(probe: Pointer)
        fun probe_close(probe: Pointer)
    }

    @Test
    fun nativeVideoIsClippedAndShadedByCompose() {
        val library = System.getenv("BITCHORD_GSTREAMER_TEXTURE_PROBE")
        assumeTrue("Set BITCHORD_GSTREAMER_TEXTURE_PROBE to run the Wayland texture probe", library != null)
        val bridge = Native.load(library, Bridge::class.java)
        val probe = checkNotNull(bridge.probe_open(requireNotNull(System.getenv("BITCHORD_GSTREAMER_CLIP"))))
        try {
            GLAssembledInterface.createFromNativePointers(0L, Pointer.nativeValue(bridge.probe_gl_loader())).use { gl ->
                DirectContext.makeGLWithInterface(gl).use { context -> runScene(bridge, probe, context) }
            }
        } finally {
            bridge.probe_close(probe)
        }
    }

    private fun runScene(bridge: Bridge, probe: Pointer, context: DirectContext) {
        var frame: Image? = null
        var width = 0
        var height = 0
        var presented = 0
        var resized = false
        val videoWidth = IntByReference()
        val videoHeight = IntByReference()
        val windowWidth = IntByReference()
        val windowHeight = IntByReference()
        val background = Color(0xff203040)
        val deadline = System.nanoTime() + 15_000_000_000L
        FrameRecomposer(Dispatchers.Unconfined).use { recomposer ->
            CanvasLayersComposeScene(recomposer).use { scene ->
                scene.setContent(recomposer.compositionContext) {
                    Box(Modifier.fillMaxSize().background(background)) {
                        Box(Modifier.offset(40.dp, 40.dp).size(480.dp, 360.dp).clip(RoundedCornerShape(54.dp))) {
                            Canvas(Modifier.fillMaxSize()) {
                                frame?.let { texture ->
                                    val source = Rect.makeWH(texture.width.toFloat(), texture.height.toFloat())
                                    val dstWidth = size.height * texture.width / texture.height
                                    val destination = Rect.makeXYWH((size.width - dstWidth) / 2f, 0f, dstWidth, size.height)
                                    drawIntoCanvas {
                                        it.nativeCanvas.drawImageRect(texture, source, destination, SamplingMode.LINEAR, null, true)
                                    }
                                }
                            }
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                                0f to Color.Transparent, .5f to Color.Transparent, 1f to Color.Black,
                            )))
                        }
                    }
                }
                while (System.nanoTime() < deadline) {
                    val inputFbo = bridge.probe_pull(probe, videoWidth, videoHeight)
                    if (inputFbo == -2) break
                    check(inputFbo >= 0) { "Could not import a synchronized GStreamer texture" }
                    if (inputFbo == 0) continue
                    try {
                        check(bridge.probe_window_size(probe, windowWidth, windowHeight) != 0)
                        width = windowWidth.value
                        height = windowHeight.value
                        scene.size = IntSize(width, height)
                        context.resetGLAll()
                        BackendRenderTarget.makeGL(videoWidth.value, videoHeight.value, 1, 0, inputFbo, rgba8).use { input ->
                            wrap(context, input).use { decoded ->
                                decoded.makeImageSnapshot().use { image ->
                                    frame = image
                                    BackendRenderTarget.makeGL(width, height, 1, 8, 0, rgba8).use { output ->
                                        wrap(context, output).use { target ->
                                            recomposer.performFrame(System.nanoTime())
                                            scene.measureAndLayout()
                                            scene.draw(target.canvas.asComposeCanvas())
                                            context.flushAndSubmit(target, false)
                                            if (presented == 5) verifyComposition(target)
                                            if (presented == 45) captureComposition(target)
                                            check(bridge.probe_swap(probe) != 0)
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        frame = null
                        bridge.probe_finish_frame(probe)
                    }
                    presented++
                    if (presented == 30) check(bridge.probe_resize(probe) != 0)
                    if (width > 640 && height > 480) resized = true
                }
            }
        }
        println("compose_gpu_swaps=$presented resized=$resized width=$width height=$height")
        assertTrue(presented >= 60, "Too few GPU frames reached the Compose framebuffer")
        assertTrue(resized, "The SDL/Wayland framebuffer did not resize")
    }

    private fun wrap(context: DirectContext, target: BackendRenderTarget) = checkNotNull(
        Surface.makeFromBackendRenderTarget(context, target, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB),
    )

    private fun captureComposition(surface: Surface) {
        val output = System.getenv("BITCHORD_GSTREAMER_TEXTURE_CAPTURE") ?: return
        Bitmap().use { bitmap ->
            check(bitmap.allocN32Pixels(surface.width, surface.height, true) && surface.readPixels(bitmap, 0, 0))
            Image.makeFromBitmap(bitmap).use { image ->
                checkNotNull(image.encodeToData()).use { data -> File(output).writeBytes(data.bytes) }
            }
        }
    }

    private fun verifyComposition(surface: Surface) {
        fun pixel(x: Int, y: Int): Int = Bitmap().use { bitmap ->
            check(bitmap.allocN32Pixels(1, 1, true) && surface.readPixels(bitmap, x, y))
            bitmap.getColor(0, 0)
        }
        val outside = pixel(20, 20)
        assertTrue(outside == pixel(42, 42), "The rounded corner exposed a video pixel")
        assertTrue(outside != pixel(290, 85), "The decoded texture was not visible")
        val faded = pixel(280, 398)
        assertTrue(faded and 0xffffff < 0x101010, "The bottom gradient did not cover the video")
    }
}
