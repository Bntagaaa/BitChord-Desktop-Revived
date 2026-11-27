package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.music.bitchord.ui.LocalDesktopPresentationVisible
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal class MotionVideoPresentation {
    var latest by mutableStateOf<MotionVideoFrameInfo?>(null)
    var lease: MotionVideoSessions.Lease? = null

    fun withFrame(read: (VideoFrame) -> Unit) = lease?.withFrame(read)

    fun sample(maxEdge: Int): ImageBitmap? {
        var bitmap: ImageBitmap? = null
        withFrame { bitmap = it.sample(maxEdge) }
        return bitmap
    }
}

@Composable
internal fun rememberMotionPresentation(
    source: MotionVideoSource,
    playing: Boolean,
    visible: Boolean = true,
): MotionVideoPresentation {
    val available = visible && LocalDesktopPresentationVisible.current
    val presentation = remember(source, available) { MotionVideoPresentation() }
    val running by rememberUpdatedState(playing)
    LaunchedEffect(source, available) {
        if (!available) return@LaunchedEffect
        val lease = UnifiedVideoEngine.motion(source, running)
        presentation.lease = lease
        try {
            coroutineScope {
                launch { snapshotFlow { running }.collect(lease::running) }
                lease.presentation.collect { presentation.latest = it }
            }
        } finally {
            presentation.latest = null
            presentation.lease = null
            lease.close()
        }
    }
    return presentation
}

@Composable
internal fun DesktopMotionArtwork(
    source: MotionVideoSource,
    modifier: Modifier,
    playing: Boolean,
    contentScale: ContentScale,
    publishBackdrop: Boolean,
    trackViewport: Boolean = false,
) {
    var inViewport by remember(source) { mutableStateOf(!trackViewport) }
    val presentation = rememberMotionPresentation(source, playing, inViewport)
    val size by remember(presentation) { derivedStateOf { presentation.latest?.size } }
    val owner = remember(source) { Any() }
    val hasFrame = size != null
    LaunchedEffect(owner, publishBackdrop, hasFrame) {
        if (publishBackdrop && hasFrame) {
            presentation.sample(128)?.let { DesktopCanvasBackdrop.publish(source.url, owner, it) }
        } else {
            DesktopCanvasBackdrop.clear(owner)
        }
    }
    DisposableEffect(owner) { onDispose { DesktopCanvasBackdrop.clear(owner) } }
    Box(if (trackViewport) modifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        inViewport = bounds.width > 0f && bounds.height > 0f
    } else modifier) {
        size?.let { dimensions ->
            val painter = remember(presentation, dimensions) {
                VideoSurfacePainter(dimensions, { presentation.latest }, { presentation.withFrame(read = it) })
            }
            Image(painter, null, Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}
