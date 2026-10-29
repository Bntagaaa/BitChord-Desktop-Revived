package com.music.bitchord.desktop

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.screens.desktopTopScrollFade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContentEquals

@OptIn(ExperimentalTestApi::class)
class DesktopScrollFadeTest {
    @Test fun cachedMasksPreserveBothScrollableAndStaticPixels() = runDesktopComposeUiTest(width=800,height=400) {
        lateinit var old:ScrollState;lateinit var current:ScrollState;lateinit var scope:CoroutineScope
        var tall by mutableStateOf(true)
        setContent {
            old=rememberScrollState();current=rememberScrollState();scope=rememberCoroutineScope()
            Row(Modifier.background(Color.White)) {
                Box(Modifier.size(160.dp,240.dp).testTag("old").legacyFade(old).verticalScroll(old)) { Box(Modifier.width(160.dp).height(if(tall) 720.dp else 80.dp).background(Color(0xffb03355))) }
                Spacer(Modifier.width(20.dp))
                Box(Modifier.size(160.dp,240.dp).testTag("new").desktopTopScrollFade(current).verticalScroll(current)) { Box(Modifier.width(160.dp).height(if(tall) 720.dp else 80.dp).background(Color(0xffb03355))) }
            }
        }
        fun compare() {
            val a=onNodeWithTag("old").captureToImage();val b=onNodeWithTag("new").captureToImage()
            assertContentEquals(IntArray(a.width*a.height).also(a::readPixels),IntArray(b.width*b.height).also(b::readPixels))
        }
        waitForIdle();compare()
        runOnIdle { scope.launch { old.scrollTo(120);current.scrollTo(120) } }
        waitForIdle();compare()
        runOnIdle { scope.launch { old.scrollTo(old.maxValue);current.scrollTo(current.maxValue) } }
        waitForIdle();compare()
        runOnIdle { tall=false }
        waitForIdle();compare()
    }
    /** Frozen pre-change mask, for exact visual regression checks. */
    private fun Modifier.legacyFade(state:ScrollState):Modifier = graphicsLayer { compositingStrategy=CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val top=minOf(34.dp.toPx(),size.height/3f);val bottom=minOf(54.dp.toPx(),size.height/3f)
        if(state.value>0) drawRect(Brush.verticalGradient(listOf(Color.Transparent,Color.Black),startY=0f,endY=top),blendMode=BlendMode.DstIn)
        if(state.value<state.maxValue) drawRect(Brush.verticalGradient(listOf(Color.Black,Color.Transparent),startY=size.height-bottom,endY=size.height),blendMode=BlendMode.DstIn)
    }
}
