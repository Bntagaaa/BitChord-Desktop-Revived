package com.music.bitchord.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import java.awt.EventQueue
import java.awt.Robot
import java.awt.event.WindowEvent
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlin.test.*

/** Opt-in real AWT window lifecycle under X11/Xvfb; no account/network/playback session created. */
class DesktopPipNativeTest {
    @Test fun `native floating window opens resizes pins closes and restores without duplicate peers`() {
        if (System.getenv("BITCHORD_PIP_NATIVE_TEST") != "1") return
        assertFalse(java.awt.GraphicsEnvironment.isHeadless())
        com.music.bitchord.ui.player.PlayerPlatform.install(DesktopPlayerHost)
        val artwork=DesktopPipUiTest.fixtureArtwork()
        val before=DesktopPipSettings.state.value
        val oldTray=DesktopWindowVisibility.keepRunningWhenClosed
        val oldClose=DesktopWindowVisibility.onClosedToTray
        var hidden=0; var restored=0; var failure:Throwable?=null
        val controller=DesktopPipController({true},{hidden++},{restored++},{true})
        val model=DesktopPipState(Song("native","Keep the music close","BitChord · Floating session",artwork.toURI().toString()),
            false,false,false,180000,true,true,false,"native",
            listOf(LyricLine(1000,"A quieter kind of morning"),LyricLine(4000,"Keep the music close"),LyricLine(8000,"Every note finds a place")),false,null)
        val position=PlaybackPosition().apply { report(5000) }
        fun peers()=java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>()
            .filter { it.title == "BitChord · Picture in picture" && it.isDisplayable }
        try {
            DesktopPipSettings.update { it.copy(bounds=DesktopPipBounds(80,80,500,340),pinned=true) }
            controller.show()
            application(exitProcessOnExit=false) {
                Window(onCloseRequest={},visible=false,title="PiP test owner") {}
                DesktopPipWindow(controller,model,position,{},{},{},{})
                LaunchedEffect(Unit) {
                    try {
                        suspend fun await(label:String, predicate:()->Boolean) {
                            repeat(100) { if(predicate())return;delay(30) }
                            error("native PiP timed out: $label")
                        }
                        await("initial mapped frame") { peers().size==1 && peers()[0].isShowing && hidden>0 }
                        repeat(20) { controller.show() }
                        delay(250); assertEquals(1,peers().size)
                        val native=peers().single()
                        if(java.awt.Toolkit.getDefaultToolkit().isAlwaysOnTopSupported) assertTrue(native.isAlwaysOnTop)
                        DesktopPipSettings.update { it.copy(pinned=false) }
                        await("pin disabled") { !native.isAlwaysOnTop }
                        for ((w,h) in listOf(360 to 230,437 to 283,820 to 450,360 to 640)) {
                            native.setBounds(90,90,w,h);delay(400)
                            assertTrue(native.width>=340 && native.height>=210)
                            val file=File("build/reports/pip-rebuild/native-${w}x${h}.png");file.parentFile.mkdirs()
                            ImageIO.write(Robot().createScreenCapture(native.bounds),"png",file)
                        }
                        controller.restore()
                        await("restore disposes floating peer") { peers().isEmpty() }
                        assertEquals(1,restored)
                        DesktopWindowVisibility.keepRunningWhenClosed=true
                        DesktopWindowVisibility.onClosedToTray={controller.show()}
                        assertFalse(DesktopWindowVisibility.onCloseRequest())
                        await("close-to-tray opens floating player") { peers().size==1 && peers()[0].isShowing }
                        peers().single().dispatchEvent(WindowEvent(peers().single(),WindowEvent.WINDOW_CLOSING))
                        await("native close") { peers().isEmpty() && !controller.open.value }
                        controller.show()
                        await("reopen") { peers().size==1 && peers()[0].isShowing }
                        peers().single().dispose()
                        await("unexpected native dispose") { peers().isEmpty() && !controller.open.value }
                        println("PIP_NATIVE PASS mapped/one-peer/free-resize/pin/restore/close-to-tray/native-close/unexpected-dispose")
                    } catch(error:Throwable) { failure=error }
                    finally { controller.dispose(); exitApplication() }
                }
            }
            failure?.let { throw it }
        } finally {
            DesktopWindowVisibility.keepRunningWhenClosed=oldTray
            DesktopWindowVisibility.onClosedToTray=oldClose
            DesktopWindowVisibility.show()
            DesktopPipSettings.update { before };DesktopPipSettings.flush()
            artwork.delete()
        }
    }
}
