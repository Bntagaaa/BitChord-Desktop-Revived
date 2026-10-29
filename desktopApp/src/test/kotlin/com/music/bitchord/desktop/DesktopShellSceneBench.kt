package com.music.bitchord.desktop

import java.awt.Frame
import java.awt.EventQueue
import java.awt.Robot
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.imageio.ImageIO
import com.music.bitchord.data.model.Song

/** Actual desktop shell with isolated local fixtures. No network, speaker or GPU parity claims. */
object DesktopShellSceneBench {
    @JvmStatic fun main(args:Array<String>) {
        val isolated = requireNotNull(System.getProperty("bitchord.smoothness.fixtureHome")) { "Run the isolated Gradle shellSceneBench task" }
        require(isolated.isNotBlank())
        val home = Path.of(isolated).toAbsolutePath().normalize()
        require(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize() == home)
        require(Path.of(System.getProperty("java.util.prefs.userRoot")).toAbsolutePath().normalize() == home.resolve("prefs"))
        val persistence=DesktopPersistence()
        if(args.firstOrNull()=="reduced") persistence.saveBoolean(DesktopAppearanceSettings.KEY_REDUCE_DYNAMIC_BLUR,true)
        persistence.savePlaylists(listOf(DesktopPlaylist(id="smooth-fixture",title="Fixture Playlist",
            songs=List(400) { Song("fixture-$it","Fixture song $it","Fixture artist",null) })))
        DesktopWelcomeOnboarding.complete(DesktopWelcomeOnboarding.inspectStartup())
        Thread({
            try {
                val until=System.nanoTime()+30_000_000_000L
                var window:Frame?=null
                while(window==null && System.nanoTime()<until) {
                    EventQueue.invokeAndWait { window=Window.getWindows().filterIsInstance<Frame>().firstOrNull { it.isVisible && it.title=="BitChord" } }
                    Thread.sleep(50)
                }
                val frame=requireNotNull(window) { "No desktop window" }
                val robot=Robot().apply { autoDelay=25 }
                fun click(x:Int,y:Int) { robot.mouseMove(frame.x+x,frame.y+y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                fun key(code:Int) { robot.keyPress(code);robot.keyRelease(code) }
                fun ctrl(code:Int) { robot.keyPress(KeyEvent.VK_CONTROL);key(code);robot.keyRelease(KeyEvent.VK_CONTROL) }
                fun type(text:String) { text.forEach { key(KeyEvent.getExtendedKeyCodeForChar(it.uppercaseChar().code)) } }
                Thread.sleep(10_000)
                val output=Path.of(System.getProperty("bitchord.smoothness.output"))
                ImageIO.write(robot.createScreenCapture(frame.bounds),"png",output.resolveSibling(output.fileName.toString()+".home.png").toFile())
                DesktopSmoothnessTrace.scenario("shell-search-typing")
                click(70,135);Thread.sleep(400)
                repeat(3) { ctrl(KeyEvent.VK_A);type("fixturesearchtyping");Thread.sleep(100) }
                ImageIO.write(robot.createScreenCapture(frame.bounds),"png",output.resolveSibling(output.fileName.toString()+".search.png").toFile())
                DesktopSmoothnessTrace.scenario("shell-quick-search")
                ctrl(KeyEvent.VK_K);Thread.sleep(400)
                repeat(3) { ctrl(KeyEvent.VK_A);type("fixturequicksearch");Thread.sleep(100) }
                ImageIO.write(robot.createScreenCapture(frame.bounds),"png",output.resolveSibling(output.fileName.toString()+".quick.png").toFile())
                key(KeyEvent.VK_ESCAPE);Thread.sleep(200)
                DesktopSmoothnessTrace.scenario("shell-playlist-scroll")
                click(80,354);Thread.sleep(400)
                robot.mouseMove(frame.x+600,frame.y+400)
                repeat(16) { robot.mouseWheel(3);Thread.sleep(30) }
                repeat(16) { robot.mouseWheel(-3);Thread.sleep(30) }
                ImageIO.write(robot.createScreenCapture(frame.bounds),"png",output.resolveSibling(output.fileName.toString()+".playlist.png").toFile())
                DesktopSmoothnessTrace.scenario("shell-resize")
                repeat(12) { n -> EventQueue.invokeAndWait { frame.setSize(1050+(n%3)*80,720+(n%2)*60) };Thread.sleep(50) }
                DesktopSmoothnessTrace.scenario("shell-hide-restore")
                EventQueue.invokeAndWait { DesktopWindowVisibility.keepRunningWhenClosed=true;DesktopWindowVisibility.onCloseRequest() }
                Thread.sleep(1200)
                DesktopWindowVisibility.raise();Thread.sleep(400)
                DesktopSmoothnessTrace.stop();DesktopSmoothnessTrace.write()
                System.exit(0)
            } catch(failure:Throwable) { failure.printStackTrace();System.exit(1) }
        },"BitChord-Shell-Scenario").apply { isDaemon=true;start() }
        com.music.bitchord.desktop.main()
    }
}
