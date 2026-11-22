package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Native
import java.awt.BorderLayout
import java.awt.Canvas
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/** Interactive backend experiment. Set BITCHORD_GSTREAMER_PROBE and BITCHORD_GSTREAMER_CLIP to opt in. */
class DesktopGStreamerOverlayProbeTest {
    @Test
    fun rendersIntoNativeChildInComposeWindow() {
        val executable = System.getenv("BITCHORD_GSTREAMER_PROBE")
        assumeTrue("Set BITCHORD_GSTREAMER_PROBE to run the native window probe", !executable.isNullOrBlank())
        val clip = requireNotNull(System.getenv("BITCHORD_GSTREAMER_CLIP"))
        val mode = System.getenv("BITCHORD_GSTREAMER_MODE") ?: "x11-va"
        val report = File("build/reports/gstreamer-overlay/probe.log").absoluteFile
        report.parentFile.mkdirs()

        var result: Result<Int>? = null
        application(exitProcessOnExit = false) {
            Window(
                title = "BitChord video overlay probe",
                state = rememberWindowState(width = 760.dp, height = 540.dp,
                    position = WindowPosition(Alignment.Center)),
                onCloseRequest = ::exitApplication,
            ) {
                val handle = remember { CompletableDeferred<Long>() }
                LaunchedEffect(handle) {
                    try {
                        result = runCatching {
                            val peer = withTimeout(5_000) { handle.await() }
                            withContext(Dispatchers.IO) {
                                val child = ProcessBuilder(requireNotNull(executable), mode, clip, "1", peer.toULong().toString())
                                    .redirectErrorStream(true)
                                    .redirectOutput(report)
                                    .start()
                                try {
                                    check(child.waitFor(35, TimeUnit.SECONDS)) { "Native probe timed out" }
                                    child.exitValue()
                                } finally {
                                    if (child.isAlive) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                                }
                            }
                        }
                    } finally {
                        exitApplication()
                    }
                }

                Box(Modifier.fillMaxSize().background(Color(0xff202735))) {
                    Box(
                        Modifier.offset(60.dp, 50.dp).size(640.dp, 420.dp)
                            .clip(RoundedCornerShape(42.dp)).background(Color(0xff405070)),
                    ) {
                        SwingPanel(
                            modifier = Modifier.fillMaxSize(),
                            factory = {
                                JPanel(BorderLayout()).apply {
                                    add(object : Canvas() {
                                        override fun addNotify() {
                                            super.addNotify()
                                            runCatching { Native.getComponentID(this) }.fold(
                                                { handle.complete(it) },
                                                { handle.completeExceptionally(it) },
                                            )
                                        }
                                    }, BorderLayout.CENTER)
                                }
                            },
                        )
                        // Hardware video must be visible through this gradient and clipped by the parent.
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(listOf(Color.Transparent, Color(0xd0202735))),
                            ),
                        )
                    }
                }
            }
        }

        assertEquals(0, assertNotNull(result, "Probe window closed before completion").getOrThrow(), report.path)
    }
}
