package com.music.bitchord.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DesktopPipUiTest {
    @BeforeTest fun installHost() { com.music.bitchord.ui.player.PlayerPlatform.install(DesktopPlayerHost) }
    private fun state(url: String? = null) = DesktopPipState(
        Song("fixture", "A very long song title that should stay inside the floating window", "BitChord · Studio session", url),
        false,false,false,180000,true,true,false,"fixture",
        listOf(LyricLine(1000,"A quieter kind of morning"),LyricLine(4000,"Keep the music close"),
            LyricLine(8000,"Every note finds a place"),LyricLine(12000,"And the day moves on")),false,null)

    @Test fun `intermediate wide tall and minimum sizes keep transport and lyrics inside bounds`() {
        val artwork = fixtureArtwork()
        try {
            for ((width,height) in listOf(340 to 180,360 to 210,437 to 283,500 to 340,639 to 360,700 to 350,820 to 410,1000 to 300,360 to 620)) {
                runDesktopComposeUiTest(width=width,height=height) {
                    val position = PlaybackPosition().apply { report(5000) }
                    val model = state(artwork.toURI().toString())
                    val blurReduced = mutableStateOf(false)
                    setContent {
                        MaterialTheme(colorScheme=desktopColorScheme(),typography=desktopTypography()) {
                            DesktopPipUi(model,position,DesktopPipPreferences(),blurReduced.value,true,{},{},{},{},{},{},{},{})
                        }
                    }
                    waitUntil(timeoutMillis=15000) { onAllNodesWithTag("pip-cover").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("pip-active-lyric").assertTextEquals("Keep the music close")
                    val root=onNodeWithTag("pip-root").fetchSemanticsNode().boundsInRoot
                    for (tag in listOf("pip-play","pip-previous","pip-next","pip-seek","pip-title","pip-lyrics","pip-cover")) {
                        val bounds=onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                        assertTrue(bounds.left>=root.left-.5f && bounds.right<=root.right+.5f, "$width x $height: $tag width $bounds $root")
                        assertTrue(bounds.top>=root.top-.5f && bounds.bottom<=root.bottom+.5f, "$width x $height: $tag height $bounds $root")
                    }
                    val cover=onNodeWithTag("pip-cover").fetchSemanticsNode().boundsInRoot
                    assertTrue(kotlin.math.abs(cover.width-cover.height)<1f)
                    save(onRoot().captureToImage(),"pip-${width}x${height}.png")
                    if(width==500 && height==340) {
                        runOnIdle { blurReduced.value=true }
                        onNodeWithTag("pip-background-sharp").assertExists()
                        onNodeWithTag("pip-background-prepared").assertDoesNotExist()
                        save(onRoot().captureToImage(),"pip-500x340-no-blur.png")
                    }
                }
            }
        } finally { artwork.delete() }
    }

    @Test fun `controls delegate once locks hold and global blur changes immediately`() = runDesktopComposeUiTest(width=500,height=340) {
        val model=mutableStateOf(state())
        val blur=mutableStateOf(false)
        val preferences=mutableStateOf(DesktopPipPreferences())
        val position=PlaybackPosition().apply { report(5000) }
        var plays=0; var previous=0; var next=0; var seeks=0
        setContent {
            MaterialTheme {
                DesktopPipUi(model.value,position,preferences.value,blur.value,true,{},{},{},
                    {preferences.value=preferences.value.copy(lyrics=!preferences.value.lyrics)},
                    {plays++},{previous++},{next++},{seeks++})
            }
        }
        onNodeWithTag("pip-play").performClick()
        onNodeWithTag("pip-previous").performClick()
        onNodeWithTag("pip-next").performClick()
        onNodeWithTag("pip-seek").performTouchInput { click(center) }
        runOnIdle { assertEquals(1,plays);assertEquals(1,previous);assertEquals(1,next);assertEquals(1,seeks);blur.value=true }
        onNodeWithTag("pip-background-sharp").assertExists()
        onNodeWithTag("pip-background-prepared").assertDoesNotExist()
        onNodeWithContentDescription("Hide lyrics").performClick()
        onNodeWithTag("pip-lyrics").assertDoesNotExist()
        onNodeWithContentDescription("Show lyrics").performClick()
        onNodeWithTag("pip-lyrics").assertExists()
        runOnIdle { model.value=model.value.copy(controlsLocked=true) }
        onNodeWithTag("pip-play").assertIsNotEnabled()
        onNodeWithTag("pip-next").assertIsNotEnabled()
        onNodeWithTag("pip-seek").assertIsNotEnabled()
    }

    @Test fun `unsynced missing and new track lyrics never show stale active words`() = runDesktopComposeUiTest(width=437,height=283) {
        val model=mutableStateOf(state())
        val position=PlaybackPosition().apply { report(5000) }
        setContent { MaterialTheme { DesktopPipUi(model.value,position,DesktopPipPreferences(),true,true,{},{},{},{},{},{},{},{}) } }
        onNodeWithTag("pip-active-lyric").assertTextEquals("Keep the music close")
        runOnIdle { model.value=model.value.copy(song=model.value.song!!.copy(videoId="new")) }
        onNodeWithText("Finding lyrics…").assertExists()
        onNodeWithText("Keep the music close").assertDoesNotExist()
        runOnIdle { model.value=model.value.copy(lyricsTrack="new",lyrics=listOf(LyricLine(0,"Plain lyrics without timestamps"))) }
        onNodeWithText("Plain lyrics without timestamps").assertExists()
        runOnIdle { model.value=model.value.copy(lyrics=null,lyricsError="Lyrics unavailable") }
        onNodeWithText("Lyrics unavailable").assertExists()
    }

    @Test fun `shared lyrics offset and paused seeks update the floating lyric without a lookup`() = runDesktopComposeUiTest(width=500,height=340) {
        val old=DesktopPlayerSettings.lyricsOffsetMs.value
        val position=PlaybackPosition().apply { report(5000) }
        try {
            DesktopPlayerSettings.lyricsOffsetMs.value=1500
            setContent { MaterialTheme { DesktopPipUi(state(),position,DesktopPipPreferences(),true,true,{},{},{},{},{},{},{},{}) } }
            onNodeWithTag("pip-active-lyric").assertTextEquals("A quieter kind of morning")
            runOnIdle { DesktopPlayerSettings.lyricsOffsetMs.value=-2000 }
            onNodeWithTag("pip-active-lyric").assertTextEquals("Keep the music close")
            runOnIdle { position.report(9000) }
            onNodeWithTag("pip-active-lyric").assertTextEquals("Every note finds a place")
            runOnIdle { DesktopPlayerSettings.lyricsOffsetMs.value=0; position.report(500) }
            onNodeWithTag("pip-active-lyric").assertTextEquals("Intro")
        } finally { DesktopPlayerSettings.lyricsOffsetMs.value=old }
    }

    @Test fun `one artwork request supplies both cover and backdrop across resizes`() = runDesktopComposeUiTest(width=1000,height=800) {
        val fixture=fixtureArtwork();val bytes=fixture.readBytes();fixture.delete()
        val requests=java.util.concurrent.atomic.AtomicInteger()
        val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/cover.png") { exchange ->
            requests.incrementAndGet()
            exchange.responseHeaders.add("Content-Type","image/png")
            exchange.responseHeaders.add("Cache-Control","no-store")
            exchange.sendResponseHeaders(200,bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val dimensions=mutableStateOf(340 to 210)
            val model=state("http://127.0.0.1:${server.address.port}/cover.png")
            val position=PlaybackPosition().apply { report(5000) }
            setContent { MaterialTheme {
                Box(Modifier.size(dimensions.value.first.dp,dimensions.value.second.dp)) {
                    DesktopPipUi(model,position,DesktopPipPreferences(),false,true,{},{},{},{},{},{},{},{})
                }
            } }
            waitUntil(timeoutMillis=10000) { onAllNodesWithTag("pip-background-image").fetchSemanticsNodes().isNotEmpty() }
            for(size in listOf(437 to 283,820 to 410,360 to 620)) {
                runOnIdle { dimensions.value=size };waitForIdle()
            }
            assertEquals(1,requests.get(),"cover, prepared blur and resized layouts must share one fetch")
        } finally { server.stop(0) }
    }

    companion object {
        fun fixtureArtwork(): File {
            val image=BufferedImage(512,512,BufferedImage.TYPE_INT_RGB)
            val paint=image.createGraphics()
            paint.paint=java.awt.GradientPaint(0f,0f,java.awt.Color(19,96,101),512f,512f,java.awt.Color(198,159,89))
            paint.fillRect(0,0,512,512)
            paint.color=java.awt.Color(18,48,52);paint.fillOval(120,90,280,280)
            paint.color=java.awt.Color(219,209,177);paint.fillOval(230,200,60,60)
            paint.dispose()
            return File.createTempFile("pip-artwork-", ".png").also { ImageIO.write(image,"png",it) }
        }
        fun save(bitmap: androidx.compose.ui.graphics.ImageBitmap,name:String) {
            val pixels=bitmap.toPixelMap()
            val target=BufferedImage(bitmap.width,bitmap.height,BufferedImage.TYPE_INT_ARGB)
            for(y in 0 until bitmap.height)for(x in 0 until bitmap.width) target.setRGB(x,y,pixels[x,y].toArgb())
            val file=File("build/reports/pip-rebuild/$name");file.parentFile.mkdirs();ImageIO.write(target,"png",file)
        }
    }
}
