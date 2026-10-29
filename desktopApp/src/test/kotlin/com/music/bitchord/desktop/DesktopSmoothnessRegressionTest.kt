package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.AbstractPreferences
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DesktopSmoothnessRegressionTest {
    private class Node:AbstractPreferences(null,"") {
        val values=ConcurrentHashMap<String,String>()
        val flushes=AtomicInteger()
        var flushedOnEdt=false
        override fun putSpi(key:String,value:String) { values[key]=value }
        override fun getSpi(key:String):String?=values[key]
        override fun removeSpi(key:String) { values.remove(key) }
        override fun removeNodeSpi() { values.clear() }
        override fun keysSpi():Array<String> = values.keys.toTypedArray()
        override fun childrenNamesSpi():Array<String> = emptyArray()
        override fun childSpi(name:String):AbstractPreferences = error("No children")
        override fun syncSpi() {}
        override fun flushSpi() { flushedOnEdt=EventQueue.isDispatchThread();flushes.incrementAndGet() }
    }
    @Test fun preferenceValuesAreImmediateAndUiWritesFlushOnceOffTheEdt() {
        val node=Node()
        EventQueue.invokeAndWait {
            repeat(100) { DesktopPreferenceChunks.write(node,"slider",it.toString()) }
            assertEquals("99",DesktopPreferenceChunks.read(node,"slider"))
        }
        DesktopPreferenceFlush.flushPending()
        assertEquals(1,node.flushes.get())
        assertFalse(node.flushedOnEdt)
    }
    @Test fun backgroundWritesRetainSynchronousDurability() {
        val node=Node()
        DesktopPreferenceChunks.write(node,"session","fixture")
        assertEquals(1,node.flushes.get())
        assertEquals("fixture",DesktopPreferenceChunks.read(node,"session"))
    }
    @Test fun drainPreservesLatestChunkedValueAndRemoval() {
        val node=Node()
        EventQueue.invokeAndWait {
            DesktopPreferenceChunks.write(node,"queue","x".repeat(30_000))
            DesktopPreferenceChunks.write(node,"queue","last")
            DesktopPreferenceChunks.write(node,"remove","old")
            DesktopPreferenceChunks.remove(node,"remove")
            assertEquals("last",DesktopPreferenceChunks.read(node,"queue"))
            assertNull(DesktopPreferenceChunks.read(node,"remove"))
        }
        DesktopPreferenceFlush.flushPending()
        assertEquals(setOf("queue.0"),node.values.keys)
        assertFalse(node.flushedOnEdt)
    }
    @Test fun liveVideoRepaintsWithoutRecomposingItsContainer() = runDesktopComposeUiTest(width=300,height=200) {
        val frame=mutableStateOf<Painter?>(null)
        val composed=AtomicInteger();val drawn=AtomicInteger()
        fun next(color:Color) = object:Painter() {
            override val intrinsicSize=Size(60f,40f)
            override fun DrawScope.onDraw() { drawn.incrementAndGet();drawRect(color) }
        }
        frame.value=next(Color.Red)
        setContent {
            val ready by remember { derivedStateOf { frame.value!=null } }
            val painter=remember { DesktopLiveFramePainter(frame) }
            SideEffect { composed.incrementAndGet() }
            if(ready) Image(painter,null,Modifier.size(120.dp,80.dp).testTag("video"))
        }
        waitForIdle()
        val before=composed.get();val draws=drawn.get()
        repeat(40) { runOnIdle { frame.value=next(if(it%2==0) Color.Blue else Color.Green) };waitForIdle() }
        assertEquals(before,composed.get());assertTrue(drawn.get()>draws)
        onNodeWithTag("video").assertIsDisplayed()
        runOnIdle { frame.value=null }
        onNodeWithTag("video").assertDoesNotExist()
    }
    @Test fun typeaheadKeepsTypingOutOfTheRootAndRejectsLateResults() = runDesktopComposeUiTest(width=300,height=200) {
        val query=mutableStateOf("first");val typing=mutableStateOf(true);val revision=mutableStateOf(1L)
        val started=CopyOnWriteArrayList<String>();val delivered=CopyOnWriteArrayList<List<String>>()
        val first=CompletableDeferred<Result<List<String>>>()
        val second=CompletableDeferred<Result<List<String>>>()
        val composed=AtomicInteger()
        setContent {
            SideEffect { composed.incrementAndGet() }
            Text("Root")
            DesktopTypeaheadEffect(query,typing,revision,1,
                load={ text -> started+=text;withContext(NonCancellable) { if(text=="first") first.await() else second.await() } },
                publish={ delivered+=it })
        }
        mainClock.advanceTimeBy(100)
        waitUntil(timeoutMillis=3000) { "first" in started }
        val before=composed.get()
        runOnIdle { query.value="second";revision.value++ }
        mainClock.advanceTimeBy(100)
        waitUntil(timeoutMillis=3000) { "second" in started }
        first.complete(Result.success(listOf("stale")))
        second.complete(Result.success(listOf("current")))
        waitUntil(timeoutMillis=3000) { listOf("current") in delivered }
        assertFalse(listOf("stale") in delivered)
        assertEquals(before,composed.get())
    }
    @Test fun hiddenTypeaheadDoesNotFetchAndProviderFailuresRemainLocal() = runDesktopComposeUiTest(width=200,height=100) {
        val query=mutableStateOf("query");val typing=mutableStateOf(true);val revision=mutableStateOf(1L)
        var visible by mutableStateOf(false)
        val calls=AtomicInteger();val published=AtomicInteger()
        setContent {
            DesktopTypeaheadEffect<String>(query,typing,revision,1,enabled=visible,
                load={ calls.incrementAndGet();assertFalse(EventQueue.isDispatchThread());error("fixture failure") },
                // This test owns a Compose test dispatcher, rather than a production AWT window.
                publish={ assertTrue(it.isEmpty());published.incrementAndGet() })
        }
        mainClock.advanceTimeBy(500)
        assertEquals(0,calls.get())
        val before=published.get()
        runOnIdle { visible=true }
        mainClock.advanceTimeBy(500)
        waitUntil(timeoutMillis=3000) { published.get()>before }
        assertEquals(1,calls.get())
    }
}
