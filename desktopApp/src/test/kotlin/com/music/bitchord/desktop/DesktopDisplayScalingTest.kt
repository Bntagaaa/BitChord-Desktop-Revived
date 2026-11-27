package com.music.bitchord.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.Toolkit
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopDisplayScalingTest {
    @Test
    fun kdeFractionalScaleIsReconstructedFromBothXSettingsValues() {
        // Plasma 6.8 publishes the integer part in WindowScalingFactor and the
        // fractional remainder in UnscaledDPI, in units of 1/1024 DPI.
        val fixtures = listOf(
            Triple(1, 98_304, 1f),
            Triple(1, 122_880, 1.25f),
            Triple(1, 147_456, 1.5f),
            Triple(1, 172_032, 1.75f),
            Triple(2, 98_304, 2f),
            Triple(2, 110_592, 2.25f),
            Triple(2, 122_880, 2.5f),
        )
        for ((windowScale, unscaledDpi, expected) in fixtures) {
            assertEquals(expected, resolveX11WindowScale(windowScale, unscaledDpi, isKde = true))
        }
    }

    @Test
    fun gnomeTextScalingDoesNotBecomeWholeWindowScaling() {
        val scale = resolveX11WindowScale(2, 122_880, isKde = false)
        assertEquals(2f, scale, "GNOME UnscaledDPI contains text scaling, not KDE's fractional remainder")
        assertEquals(Density(2.5f, 1.25f), desktopContentDensity(Density(2f, 1.25f), 125, scale))
    }

    @Test
    fun fractionalX11DensityReplacesTheAwtDensityAndAppliesZoomOnce() {
        // AWT can expose an integer even when KDE is configured to use 150%.
        // Taking max(AWT, XSettings), rounding, or multiplying them gives a wrong size.
        for (awt in listOf(1f, 1.5f, 2f)) {
            assertEquals(Density(1.5f, 1.15f), desktopContentDensity(Density(awt, 1.15f), 100, 1.5f))
            assertEquals(Density(1.875f, 1.15f), desktopContentDensity(Density(awt, 1.15f), 125, 1.5f))
            assertEquals(Density(1.2f, 1.15f), desktopContentDensity(Density(awt, 1.15f), 80, 1.5f))
        }
        assertEquals(Density(1.25f), desktopContentDensity(Density(2f), 125, 1f))
    }

    @Test
    fun keepsTheOriginalFractionalDensityAndFontScaleWhenXSettingsAreUnavailable() {
        for (scale in listOf(1f, 1.25f, 1.5f, 1.75f, 2f)) {
            for (zoom in listOf(80, 100, 125)) {
                assertEquals(Density(scale * (zoom / 100f), 1.2f), desktopContentDensity(Density(scale, 1.2f), zoom))
            }
        }
    }

    @Test
    fun invalidDisplayValuesFallBackSafely() {
        for (invalidScale in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f)) {
            assertEquals(Density(1.25f), desktopContentDensity(Density(invalidScale, invalidScale), 125))
            assertEquals(Density(1.5f), desktopContentDensity(Density(1.5f), 100, invalidScale))
        }
        assertEquals(Density(1.5f), desktopContentDensity(Density(1.5f), 100, 9f))
        assertEquals(Density(1.5f), desktopContentDensity(Density(Float.NaN), 100, 1.5f))

        for (windowScale in listOf(null, -1, 0, 9)) {
            assertNull(resolveX11WindowScale(windowScale, 147_456, isKde = true))
        }
        for (unscaledDpi in listOf(null, -1, 0, Int.MAX_VALUE)) {
            assertEquals(2f, resolveX11WindowScale(2, unscaledDpi, isKde = true))
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun liveScaleChangesResizeLayoutAndKeepMouseHitTestingAligned() = runDesktopComposeUiTest(
        width = 240,
        height = 180,
    ) {
        val x11Scale = mutableStateOf(1f)
        val zoomPercent = mutableStateOf(100)
        var clickCount = 0
        setContent {
            val density = desktopContentDensity(LocalDensity.current, zoomPercent.value, x11Scale.value)
            CompositionLocalProvider(LocalDensity provides density) {
                Box(
                    Modifier.size(40.dp)
                        .testTag("control")
                        .clickable { clickCount++ },
                )
            }
        }
        val control = onNodeWithTag("control")
        // Expected physical sizes of a 40dp control. Include returning to 100%
        // after 200% to catch a stale density on both enlargement and shrinkage.
        val scales = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 1f)
        val expectedWidths = mapOf(
            80 to listOf(32f, 40f, 48f, 56f, 64f, 32f),
            100 to listOf(40f, 50f, 60f, 70f, 80f, 40f),
            125 to listOf(50f, 63f, 75f, 88f, 100f, 50f),
        )
        var expectedClicks = 0
        for ((zoom, widths) in expectedWidths) {
            for ((index, scale) in scales.withIndex()) {
                runOnIdle {
                    x11Scale.value = scale
                    zoomPercent.value = zoom
                }
                val width = widths[index]
                val bounds = control.fetchSemanticsNode().boundsInRoot
                assertEquals(width, bounds.width, "Control width at scale=$scale, zoom=$zoom")
                assertEquals(width, bounds.height, "Control height at scale=$scale, zoom=$zoom")
                // The lower-right corner moves with the layout. A stale hit area
                // would reject this click after the control becomes larger.
                control.performMouseInput { click(Offset(width - 2f, width - 2f)) }
                expectedClicks++
                runOnIdle { assertEquals(expectedClicks, clickCount) }
            }
        }
    }

    @Test
    fun xToolkitReceivesKdeFractionalChangesWhenTheIntegerFactorDoesNotChange() {
        val config = System.getenv("BITCHORD_XSETTINGSD_CONFIG")?.let { Path.of(it) } ?: return
        val daemonPid = requireNotNull(System.getenv("BITCHORD_XSETTINGSD_PID"))
        val originalConfig = Files.readString(config)

        val toolkit = Toolkit.getDefaultToolkit()
        val events = LinkedBlockingQueue<Float>()
        val stop = assertNotNull(toolkit.observeX11WindowScale(isKde = true) { if (it != null) events.offer(it) })
        try {
            assertEquals(1f, events.poll(15, TimeUnit.SECONDS), "Initial XSettings scale")
            for ((windowScale, unscaledDpi, expected) in listOf(
                Triple(1, 122_880, 1.25f),
                Triple(1, 147_456, 1.5f),
                Triple(1, 172_032, 1.75f),
                Triple(2, 98_304, 2f),
                Triple(1, 98_304, 1f),
            )) {
                setXSettings(config, daemonPid, "Gdk/WindowScalingFactor $windowScale\nGdk/UnscaledDPI $unscaledDpi\n")
                assertEquals(expected, events.poll(15, TimeUnit.SECONDS), "Live XSettings scale should become $expected")
            }
        } finally {
            stop()
            setXSettings(config, daemonPid, originalConfig)
        }
    }

    private fun setXSettings(config: Path, daemonPid: String, contents: String) {
        Files.writeString(config, contents)
        assertEquals(0, ProcessBuilder("kill", "-HUP", daemonPid).start().waitFor())
    }
}
