package com.music.bitchord.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Robot
import java.awt.event.WindowEvent
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.*

/** Opt-in real AWT window lifecycle under X11/Xvfb; no account/network/playback session created. */
@OptIn(ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class DesktopPipNativeTest {
    @Test fun `title and lyrics follow song changes while main window is hidden`() {
        if (System.getenv("BITCHORD_PIP_NATIVE_TEST") != "1") return
        com.music.bitchord.ui.player.PlayerPlatform.install(DesktopPlayerHost)
        val before = DesktopPipSettings.state.value
        val mainVisible = mutableStateOf(true)
        val controller = DesktopPipController({ true }, { mainVisible.value = false }, { mainVisible.value = true }, { true })
        val host = DesktopPipHost(controller)
        val lyrics = DesktopLyricsSession()
        val secondLyrics = CompletableDeferred<Unit>()
        val lookups = mutableListOf<String>()
        val songs = listOf("First", "Second", "Third").map { Song(it.lowercase(), "$it song", "$it artist", null) }
        val model = mutableStateOf(DesktopPipState(
            Song("first", "First song", "First artist", null), false, false, false, 180000, true, true, false,
            "first", listOf(LyricLine(1000, "First verse"), LyricLine(8000, "First chorus")), false, null))
        val position = PlaybackPosition().apply { report(5000) }
        var failure: Throwable? = null
        fun pip() = java.awt.Window.getWindows().filterIsInstance<ComposeWindow>()
            .singleOrNull { it.title == "BitChord · Picture in picture" && it.isDisplayable }
        fun text(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.children.flatMap(::text)
        fun renderedText() = pip()?.semanticsOwners?.flatMap { text(it.unmergedRootSemanticsNode) }.orEmpty()
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun click(tag: String) {
            val node = pip()!!.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            val bounds = node.boundsInRoot
            val native = pip()!!
            val point = native.contentPane.locationOnScreen
            val transform = native.graphicsConfiguration.defaultTransform
            Robot().apply {
                mouseMove(point.x + (bounds.center.x / transform.scaleX).roundToInt(),
                    point.y + (bounds.center.y / transform.scaleY).roundToInt())
                mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            }
        }
        fun moveSong(by: Int) {
            val index = songs.indexOfFirst { it.videoId == model.value.song?.videoId }
            model.value = model.value.copy(song = songs[(index + by).coerceIn(songs.indices)])
        }
        try {
            DesktopPipSettings.update { it.copy(lyrics = true, bounds = DesktopPipBounds(80, 80, 500, 340)) }
            application(exitProcessOnExit = false) {
                DesktopPipHostWindow(host, x11WindowScale = null)
                Window(onCloseRequest = {}, visible = mainVisible.value, title = "PiP track test owner") {
                    LaunchedEffect(Unit) {
                        lyrics.follow(snapshotFlow {
                            DesktopLyricsRequest(model.value.song, 180000, true, false, false, emptySet(), emptyList(), null)
                        }, currentTrack = { model.value.song?.videoId }, lookup = { song, _, _ ->
                            lookups += song.videoId
                            if (song.videoId == "second") secondLyrics.await()
                            Result.success(DesktopLyrics("fixture", listOf(
                                LyricLine(1000, "${song.videoId.replaceFirstChar { it.uppercaseChar() }} verse"),
                                LyricLine(8000, "Chorus"))))
                        })
                    }
                    DisposableEffect(Unit) {
                        host.session = DesktopPipSession({
                            val current = lyrics.state
                            model.value.copy(lyricsTrack = current.trackId, lyrics = current.result?.lines,
                                lyricsLoading = current.loading, lyricsError = current.error)
                        }, position, { model.value = model.value.copy(playing = !model.value.playing) }, { moveSong(-1) }, { moveSong(1) }, {})
                        onDispose { host.session = null }
                    }
                }
                LaunchedEffect(Unit) {
                    try {
                        suspend fun await(label: String, predicate: () -> Boolean) {
                            repeat(100) { if (predicate()) return; delay(30) }
                            error("native track change timed out: $label; rendered=${renderedText()}")
                        }
                        controller.show()
                        await("first song in hidden main") { !mainVisible.value && "First song" in renderedText() && "First verse" in renderedText() }
                        click("pip-play")
                        await("pause icon after play") {
                            pip()!!.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                                .any { "Pause" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                        }
                        click("pip-play")
                        await("play icon after pause") {
                            pip()!!.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                                .any { "Play" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                        }
                        val native = pip()
                        model.value = model.value.copy(song = Song("second", "Second song", "Second artist", null))
                        await("second title while lyrics are loading") { "Second song" in renderedText() && "Finding lyrics…" in renderedText() }
                        assertFalse("First verse" in renderedText())
                        secondLyrics.complete(Unit)
                        await("second song and lyrics") { "Second song" in renderedText() && "Second verse" in renderedText() }
                        assertFalse("First song" in renderedText())
                        assertFalse("First verse" in renderedText())
                        assertTrue("Second artist" in renderedText())
                        assertEquals(listOf("first", "second"), lookups)
                        click("pip-next")
                        await("next command updates title and lyrics") { "Third song" in renderedText() && "Third verse" in renderedText() }
                        click("pip-previous")
                        await("previous command updates title and lyrics") { "Second song" in renderedText() && "Second verse" in renderedText() }
                        assertEquals(listOf("first", "second", "third", "second"), lookups)
                        assertSame(native, pip(), "A song change must not recreate the native window")
                        click("pip-action-Return to main window")
                        await("restore from hidden main") { mainVisible.value && pip() == null }
                        mainVisible.value = false
                        delay(100)
                        controller.show()
                        await("reopen from hidden main") { pip()?.isShowing == true && "Second verse" in renderedText() }
                        click("pip-action-Close picture in picture")
                        await("close while main remains hidden") { pip() == null }
                        println("PIP_TRACK_CHANGE PASS hidden-main/title/artist/lyrics-loading/lyrics-ready/next/previous/one-lookup-per-transition/restore/reopen/close")
                    } catch (error: Throwable) { failure = error }
                    finally { controller.dispose(); exitApplication() }
                }
            }
            failure?.let { throw it }
        } finally {
            DesktopPipSettings.update { before }; DesktopPipSettings.flush()
        }
    }

    @Test fun `fractional content scaling preserves minimum controls and native mouse targets`() {
        if (System.getenv("BITCHORD_PIP_NATIVE_TEST") != "1") return
        com.music.bitchord.ui.player.PlayerPlatform.install(DesktopPlayerHost)
        val before = DesktopPipSettings.state.value
        val scale = mutableStateOf<Float?>(1f)
        val controller = DesktopPipController({ true }, {}, {}, { true })
        val position = PlaybackPosition().apply { report(5000) }
        val model = DesktopPipState(
            Song("scale-fixture", "Scale fixture", "BitChord", null),
            false, false, false, 180000, true, true, false, "scale-fixture", null, false, null,
        )
        var plays = 0
        var failure: Throwable? = null
        fun pip() = java.awt.Window.getWindows().filterIsInstance<ComposeWindow>()
            .singleOrNull { it.title == "BitChord · Picture in picture" && it.isDisplayable }
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun node(tag: String) = pip()?.semanticsOwners?.flatMap { nodes(it.unmergedRootSemanticsNode) }
            ?.singleOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        try {
            DesktopPipSettings.update { it.copy(lyrics = false, bounds = DesktopPipBounds(80, 80, 500, 340)) }
            controller.show()
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = {}, visible = false, title = "PiP scale test owner") {}
                DesktopPipWindow(controller, model, position, { plays++ }, {}, {}, {}, x11WindowScale = scale.value)
                LaunchedEffect(Unit) {
                    try {
                        suspend fun await(label: String, predicate: () -> Boolean) {
                            repeat(150) { if (predicate()) return; delay(30) }
                            error("native PiP scaling timed out: $label")
                        }
                        await("mapped frame") { pip()?.isShowing == true && node("pip-play") != null }
                        val native = pip()!!
                        for (factor in listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 1f)) {
                            scale.value = factor
                            val transform = native.graphicsConfiguration.defaultTransform
                            val clientWidth = ceil(320 * factor / transform.scaleX).toInt()
                            val clientHeight = ceil(180 * factor / transform.scaleY).toInt()
                            val insets = native.insets
                            val expectedMinimum = Dimension(
                                maxOf(340, clientWidth + insets.left + insets.right),
                                maxOf(210, clientHeight + insets.top + insets.bottom),
                            )
                            await("minimum at $factor") { native.minimumSize == expectedMinimum }
                            native.size = Dimension(1, 1) // AWT must clamp an undersized resize.
                            await("client at $factor") {
                                native.contentPane.width >= clientWidth && native.contentPane.height >= clientHeight &&
                                    node("pip-action-Close picture in picture")?.boundsInRoot?.let {
                                        kotlin.math.abs(it.width - 28f * factor) < 1.1f
                                    } == true &&
                                    node("pip-root")?.boundsInRoot?.let {
                                        kotlin.math.abs(it.width - native.contentPane.width * transform.scaleX) < 1.1 &&
                                            kotlin.math.abs(it.height - native.contentPane.height * transform.scaleY) < 1.1
                                    } == true
                            }
                            val root = assertNotNull(node("pip-root")).boundsInRoot
                            for (tag in listOf("pip-play", "pip-previous", "pip-next", "pip-seek", "pip-action-Close picture in picture")) {
                                val bounds = assertNotNull(node(tag), "$tag at $factor").boundsInRoot
                                assertTrue(bounds.width > 0f && bounds.height > 0f, "$tag is clipped at $factor")
                                assertTrue(bounds.left >= root.left - .5f && bounds.right <= root.right + .5f,
                                    "$tag exceeds client width at $factor: $bounds / $root")
                                assertTrue(bounds.top >= root.top - .5f && bounds.bottom <= root.bottom + .5f,
                                    "$tag exceeds client height at $factor: $bounds / $root")
                            }
                            val play = assertNotNull(node("pip-play")).boundsInRoot.center
                            val origin = native.contentPane.locationOnScreen
                            val previousPlays = plays
                            Robot().apply {
                                mouseMove(origin.x + (play.x / transform.scaleX).roundToInt(),
                                    origin.y + (play.y / transform.scaleY).roundToInt())
                                mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                                mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                            }
                            await("native click at $factor") { plays == previousPlays + 1 }
                            assertSame(native, pip(), "Changing scale must retain the native PiP window")
                            val evidence = File("build/reports/pip-rebuild/native-scale-${(factor * 100).toInt()}.png")
                            evidence.parentFile.mkdirs()
                            ImageIO.write(Robot().createScreenCapture(native.bounds), "png", evidence)
                        }
                        println("PIP_SCALING PASS 100/125/150/175/200/100 minimum-client/native-clicks/same-peer")
                    } catch (error: Throwable) { failure = error }
                    finally { controller.dispose(); exitApplication() }
                }
            }
            failure?.let { throw it }
        } finally {
            DesktopPipSettings.update { before }
            DesktopPipSettings.flush()
        }
    }

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
