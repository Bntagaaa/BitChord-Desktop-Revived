package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * A clipped image bounds check, including nested horizontal shelves. Just checking the
 * outer window would load cards laid out beyond a LazyRow's visible horizontal viewport.
 *
 * A small lead area primes the next card before it scrolls into view. The image stays active
 * briefly when it leaves that area to prevent repeated Coil request restarts on tiny scrolls.
 */
internal object ViewportThumbnailPolicy {
    val preload = 144.dp
    const val RELEASE_GRACE_MS = 900L

    fun closeEnough(bounds: Rect, viewport: Rect, ancestors: List<Rect>, marginPx: Float): Boolean {
        if (bounds.width <= 0f || bounds.height <= 0f) return false
        fun touches(a: Rect, b: Rect): Boolean =
            a.right >= b.left - marginPx &&
                a.left <= b.right + marginPx &&
                a.bottom >= b.top - marginPx &&
                a.top <= b.bottom + marginPx

        if (!touches(bounds, viewport)) return false
        return ancestors.all { touches(bounds, it) }
    }

    fun closeEnough(coordinates: LayoutCoordinates, marginPx: Float): Boolean {
        if (!coordinates.isAttached) return false
        val root = coordinates.findRootCoordinates()
        val viewport = Rect(0f, 0f, root.size.width.toFloat(), root.size.height.toFloat())
        val bounds = coordinates.boundsInWindow()
        val parents = ArrayList<Rect>(8)
        var node = coordinates.parentLayoutCoordinates
        while (node != null) {
            if (node.isAttached) parents.add(node.boundsInWindow())
            node = node.parentLayoutCoordinates
        }
        return closeEnough(bounds, viewport, parents, marginPx)
    }
}

/**
 * Near-viewport only Coil request. Size, shape, hover and Click handlers live outside this
 * component and are always present; only the image request is gated. The Coil disk cache survives
 * scrolling and the memory cache is independently bounded by DesktopImageLoader.
 *
 * This component intentionally keeps its modifier attached while idle so Compose still observes
 * layout placement changes when the user scrolls in either direction.
 */
@Composable
fun ViewportAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val margin = with(LocalDensity.current) { ViewportThumbnailPolicy.preload.toPx() }
    var nearViewport by remember(model) { mutableStateOf(false) }
    var keepLoaded by remember(model) { mutableStateOf(false) }

    LaunchedEffect(nearViewport, model) {
        if (nearViewport && model != null) {
            keepLoaded = true
        } else {
            // Retain the image for a moment during scroll direction changes, eliminating flicker
            // while never retaining arbitrary offscreen thumbnails for the whole session.
            if (keepLoaded) delay(ViewportThumbnailPolicy.RELEASE_GRACE_MS)
            keepLoaded = false
        }
    }

    AsyncImage(
        model = if (keepLoaded && model != null) model else null,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.onGloballyPositioned { coordinates ->
            val shouldLoad = model != null && ViewportThumbnailPolicy.closeEnough(coordinates, margin)
            if (nearViewport != shouldLoad) nearViewport = shouldLoad
        },
    )
}
