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

class DesktopDisplayScalingTest {
    @Test
    fun x11WindowScaleReplacesTheAwtDensityBeforeApplyingZoom() {
        for (awt in listOf(1f, 2f)) {
            assertEquals(Density(2.5f, 1.15f), desktopContentDensity(Density(awt, 1.15f), 125, 2))
        }
        assertEquals(Density(1.25f), desktopContentDensity(Density(2f), 125, 1))
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
        for (invalidScale in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            assertEquals(Density(1.25f), desktopContentDensity(Density(invalidScale, invalidScale), 125))
        }
        assertEquals(Density(1.5f), desktopContentDensity(Density(1.5f), 100, 0))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun liveScaleChangesResizeLayoutAndKeepMouseHitTestingAligned() = runDesktopComposeUiTest(
        width = 240,
        height = 180,
    ) {
        val x11Scale = mutableStateOf(1)
        var clickCount = 0
        setContent {
            val density = desktopContentDensity(LocalDensity.current, 125, x11Scale.value)
            CompositionLocalProvider(LocalDensity provides density) {
                Box(
                    Modifier.size(40.dp)
                        .testTag("control")
                        .clickable { clickCount++ },
                )
            }
        }
        val control = onNodeWithTag("control")
        assertEquals(50f, control.fetchSemanticsNode().boundsInRoot.width)
        runOnIdle { x11Scale.value = 2 }
        assertEquals(100f, control.fetchSemanticsNode().boundsInRoot.width)
        control.performMouseInput { click(Offset(90f, 90f)) }
        runOnIdle { assertEquals(1, clickCount) }
    }

    @Test
    fun xToolkitReceivesWindowScaleChangesFromXSettings() {
        val config = System.getenv("BITCHORD_XSETTINGSD_CONFIG")?.let { Path.of(it) } ?: return
        val daemonPid = requireNotNull(System.getenv("BITCHORD_XSETTINGSD_PID"))

        val toolkit = Toolkit.getDefaultToolkit()
        val events = LinkedBlockingQueue<Int>()
        val stop = assertNotNull(toolkit.observeX11WindowScale { if (it != null) events.offer(it) })
        try {
            assertEquals(1, events.poll(15, TimeUnit.SECONDS), "Initial XSettings scale")
            setXSettingsScale(config, daemonPid, 2)
            assertEquals(2, events.poll(15, TimeUnit.SECONDS), "Updated XSettings scale")
            setXSettingsScale(config, daemonPid, 1)
            assertEquals(1, events.poll(15, TimeUnit.SECONDS), "Restored XSettings scale")
        } finally {
            stop()
        }
    }

    private fun setXSettingsScale(config: Path, daemonPid: String, scale: Int) {
        Files.writeString(config, "Gdk/WindowScalingFactor $scale\n")
        assertEquals(0, ProcessBuilder("kill", "-HUP", daemonPid).start().waitFor())
    }
}
