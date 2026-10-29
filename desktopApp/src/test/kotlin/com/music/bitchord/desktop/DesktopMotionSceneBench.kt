package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.lang.management.ManagementFactory

/** Application-side frame callback/draw fixture. No display-presentation or hardware-audio claims. */
object DesktopMotionSceneBench {
    @JvmStatic fun main(args: Array<String>) {
        val legacy=args.firstOrNull()=="legacy"
        val frames=mutableStateOf<Painter?>(null)
        val compositions=AtomicInteger()
        val draws=AtomicInteger()
        DesktopSmoothnessTrace.install()
        application {
            Window(onCloseRequest=::exitApplication,title="BitChord motion timing fixture") {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xff202020)).smoothnessTrace()) {
                        MotionFrame(legacy,frames,compositions)
                    }
                }
                LaunchedEffect(Unit) {
                    val bean=ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                    val pixels=ByteArray(720*405*4) { if(it%4==3) 0xff.toByte() else 80 }
                    fun nextFrame() = CountedFrame(DesktopCanvasFrame(org.jetbrains.skia.ImageInfo.makeN32(720,405,org.jetbrains.skia.ColorAlphaType.OPAQUE),pixels), draws)
                    // Warm JIT and allocation/GC behavior, rather than measuring class initialization.
                    repeat(180) { withFrameNanos { frames.value=nextFrame() } }
                    DesktopSmoothnessTrace.scenario(if(legacy) "motion-legacy" else "motion-isolated")
                    val before=compositions.get();val drawsBefore=draws.get();val cpuBefore=bean.processCpuTime
                    val heapBefore=ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
                    var previous=0L
                    repeat(600) { n ->
                        withFrameNanos { time ->
                            if(previous!=0L) DesktopSmoothnessTrace.record("compose_frame_callback_interval",time-previous)
                            previous=time
                            pixels[0]=n.toByte()
                            frames.value=nextFrame()
                        }
                    }
                    delay(100)
                    println("motion_legacy=$legacy compositions=${compositions.get()-before} draws=${draws.get()-drawsBefore} process_cpu_ms=${(bean.processCpuTime-cpuBefore)/1e6} heap_delta=${ManagementFactory.getMemoryMXBean().heapMemoryUsage.used-heapBefore}")
                    DesktopSmoothnessTrace.stop()
                    DesktopSmoothnessTrace.write()
                    exitApplication()
                }
            }
        }
    }
    private class CountedFrame(private val source:Painter,private val draws:AtomicInteger):Painter() {
        override val intrinsicSize = source.intrinsicSize
        override fun DrawScope.onDraw() { draws.incrementAndGet();with(source) { draw(size) } }
    }
    @Composable private fun MotionFrame(legacy: Boolean,frames:State<Painter?>,compositions:AtomicInteger) {
        SideEffect { compositions.incrementAndGet() }
        if(legacy) {
            frames.value?.let { Image(it,null,Modifier.fillMaxSize().padding(24.dp)) }
        } else {
            val ready by remember { derivedStateOf { frames.value != null } }
            val painter=remember { DesktopLiveFramePainter(frames) }
            if(ready) Image(painter,null,Modifier.fillMaxSize().padding(24.dp))
        }
    }
}
