package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.ProvideDesktopPresentationLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAutoMixVisualTest {
    private val out = Song("same", "Outgoing", "Artist", "old", queueEntryId = "first")
    private val incoming = Song("same", "Incoming", "Artist", "new", queueEntryId = "second")

    @Test
    fun samplesUsePlayedFramesAndNeverRunPastSubmittedAudio() {
        val visual = DesktopAutoMixVisualFeed()
        visual.arm(out, incoming, now = 1)
        visual.begin(10_000, 4.0, 9.0, 1_000, now = 2_000_000_001)
        val window = assertNotNull(visual.state.value)
        assertEquals(14_600L, window.revealStart)
        assertEquals(17_200L, window.revealEnd)
        visual.sample(14_000, 17_000, true, now = 3_000_000_000)
        assertEquals(0f, visual.state.value!!.progress(4_000_000_000))
        visual.sample(15_000, 15_030, true, now = 4_000_000_000)
        val snapshot = visual.state.value!!
        assertEquals(430f / 2600f, snapshot.progress(5_000_000_000), 0.0001f)
        assertTrue(snapshot.pair.incoming.matches(incoming))
        assertTrue(!snapshot.pair.incoming.matches(out))
        visual.sample(15_020, 15_100, false, now = 5_000_000_000)
        assertEquals(420f / 2600f, visual.state.value!!.progress(8_000_000_000), 0.0001f)
        visual.sample(17_200, 18_000, true, now = 8_000_000_000)
        assertNull(visual.state.value)
    }

    @Test
    fun interruptedPairAndTooShortRevealReleaseTheirResources() {
        var releases = 0
        val visual = DesktopAutoMixVisualFeed { releases++ }
        visual.arm(out, incoming)
        visual.cancelArmed()
        assertNull(visual.state.value)
        visual.arm(out, incoming)
        visual.begin(0, 0.5, 1.0, 48000)
        assertNull(visual.state.value)
        assertEquals(4, releases)
    }

    @Test
    fun skiaRendersAStippledMaskWithExactEndpoints() {
        val outgoing = ImageBitmap(96, 96).also { bitmap -> draw(bitmap) { drawRect(Color.Red) } }
        val mask = assertNotNull(DesktopOrganicDissolve.make(outgoing, 7, fit = false))
        mask.use {
            for (progress in listOf(0f, 0.5f, 1f)) {
                val target = ImageBitmap(96, 96)
                draw(target) {
                    drawRect(Color.Blue)
                    mask.draw(this, progress)
                }
                val pixels = target.toPixelMap()
                val counts = countEndpoints(pixels)
                when (progress) {
                    0f -> assertEquals(96 * 96 to 0, counts)
                    1f -> assertEquals(0 to 96 * 96, counts)
                    else -> {
                        assertTrue(counts.first > 100)
                        assertTrue(counts.second > 100)
                        assertTrue(counts.first + counts.second < 96 * 96)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun coverOverlayRetainsTheCanvasFrameThroughHandoffAndClearsOnCancellation() {
        val host = DesktopPlayerHost
        val previousFeed = host.autoMixVisual
        val previousCanvas = host.autoMixCanvasFrame.value
        val preferences = DesktopPlayerSettings
        val previousArtwork = preferences.automixArtwork.value
        val previousCanvasEnabled = preferences.animatedCanvas.value
        val visual = DesktopAutoMixVisualFeed()
        val song = mutableStateOf(out)
        val visible = mutableStateOf(true)
        val oldFrame = ImageBitmap(96, 96).also { bitmap -> draw(bitmap) { drawRect(Color.Red) } }
        try {
            host.autoMixVisual = visual.state
            preferences.automixArtwork.value = true
            preferences.animatedCanvas.value = false
            visual.arm(out, incoming, now = 1)
            val pair = visual.state.value!!.pair
            host.autoMixCanvasFrame.value = AutoMixCanvasFrame(pair.generation, oldFrame)
            visual.begin(0, 1.0, 6.0, 1_000, now = 2_000_000_001)
            runDesktopComposeUiTest(width = 96, height = 96) {
                setContent {
                    ProvideDesktopPresentationLifecycle(visible.value) {
                        Box(Modifier.fillMaxSize().background(Color.Blue).testTag("canvas-artwork")) {
                            DesktopAutoMixOverlay(song.value, Modifier.matchParentSize(), 96, mini = false, canvasVisible = true)
                        }
                    }
                }
                runOnIdle {
                    song.value = incoming
                    visual.sample(1_000, 6_000, false, now = 3_000_000_000)
                }
                val sleeve = onNodeWithTag("canvas-artwork")
                assertTrue(sleeve.captureToImage().toPixelMap()[32, 32].red > .99f)
                runOnIdle { visual.sample(2_900, 6_000, false, now = 4_000_000_000) }
                val mixed = countEndpoints(sleeve.captureToImage().toPixelMap())
                assertTrue(mixed.first > 100 && mixed.second > 100)
                runOnIdle { visible.value = false }
                assertTrue(sleeve.captureToImage().toPixelMap()[32, 32].blue > .99f)
                runOnIdle {
                    visible.value = true
                    visual.cancel()
                }
                assertTrue(sleeve.captureToImage().toPixelMap()[32, 32].blue > .99f)
            }
        } finally {
            host.autoMixVisual = previousFeed
            host.autoMixCanvasFrame.value = previousCanvas
            preferences.automixArtwork.value = previousArtwork
            preferences.animatedCanvas.value = previousCanvasEnabled
        }
    }

    @Test
    fun measureSoftwareMaskFrameTimesAndResidency() {
        val rounds = System.getenv("BITCHORD_AUTOMIX_ARTWORK_ROUNDS")?.toIntOrNull()
        org.junit.Assume.assumeTrue("Set BITCHORD_AUTOMIX_ARTWORK_ROUNDS to measure the software mask", rounds != null)
        val total = requireNotNull(rounds).coerceIn(1, 200)
        val side = 320
        val outgoing = ImageBitmap(side, side).also { bitmap -> draw(bitmap) { drawRect(Color.Red) } }
        val output = ImageBitmap(side, side)
        val canvas = Canvas(output)
        val scope = CanvasDrawScope()
        val size = Size(side.toFloat(), side.toFloat())
        val density = Density(1f)
        val framesPerReveal = 156
        val draws = LongArray(total * framesPerReveal)
        val resident = java.io.File("/proc/self/status")
        fun reportRss(label: String) {
            if (resident.isFile) println("AUTOMIX_ARTWORK $label " + resident.readLines()
                .filter { it.startsWith("VmRSS:") || it.startsWith("VmHWM:") }.joinToString(" "))
        }
        repeat(total) { generation ->
            val mask = assertNotNull(DesktopOrganicDissolve.make(outgoing, generation.toLong(), fit = false))
            mask.use {
                repeat(framesPerReveal) { frame ->
                    val started = System.nanoTime()
                    scope.draw(density, LayoutDirection.Ltr, canvas, size) {
                        drawRect(Color.Blue)
                        mask.draw(this, frame.toFloat() / (framesPerReveal - 1))
                    }
                    draws[generation * framesPerReveal + frame] = System.nanoTime() - started
                }
            }
            if (generation == 0 || generation == total / 2 || generation == total - 1) {
                reportRss("completed=${generation + 1}")
            }
        }
        draws.sort()
        println("AUTOMIX_ARTWORK frames=${draws.size} width=$side " +
            "median_ms=${draws[draws.size / 2] / 1e6} p95_ms=${draws[(draws.size * .95).toInt()] / 1e6}")
    }

    private fun countEndpoints(pixels: PixelMap): Pair<Int, Int> {
        var red = 0
        var blue = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (pixel.red > 0.99f) red++
            if (pixel.blue > 0.99f) blue++
        }
        return red to blue
    }

    private fun draw(bitmap: ImageBitmap, drawing: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) =
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap),
            Size(bitmap.width.toFloat(), bitmap.height.toFloat()), drawing)
}
