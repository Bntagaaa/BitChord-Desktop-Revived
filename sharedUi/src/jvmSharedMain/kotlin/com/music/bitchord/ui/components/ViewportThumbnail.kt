package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Opt-in on desktop. Android keeps Coil's existing behavior and requires no viewport observer.
 * This is intentionally supplied by the main desktop UI, not the playback/PiP composition.
 */
val LocalViewportThumbnailsEnabled = staticCompositionLocalOf { false }

internal fun Rect.hasArea(): Boolean = width > 0f && height > 0f

/**
 * A small overscan buys time for network/disk images as the next card approaches the edge.
 * Nothing many rows or shelves offscreen may start a request, even when LazyList precomposes it.
 *
 * A larger *exit* margin is used by the caller so a card hovering at the viewport edge does
 * not repeatedly cancel/restart an image request during slow wheel movement or trackpad jitter.
 */
fun shouldRequestThumbnail(
    rawBounds: Rect,
    clippedBounds: Rect,
    nearestVisibleAncestor: Rect?,
    horizontalMarginPx: Float,
    verticalMarginPx: Float,
): Boolean {
    if (!rawBounds.hasArea()) return false
    if (clippedBounds.hasArea()) return true
    val viewport = nearestVisibleAncestor?.takeIf(Rect::hasArea) ?: return false
    return rawBounds.left < viewport.right + horizontalMarginPx &&
        rawBounds.right > viewport.left - horizontalMarginPx &&
        rawBounds.top < viewport.bottom + verticalMarginPx &&
        rawBounds.bottom > viewport.top - verticalMarginPx
}

/** The closest non-clipped ancestor is the actual LazyRow/LazyColumn/grid viewport. */
private fun LayoutCoordinates.nearestVisibleAncestor(): Rect? {
    var parent = parentLayoutCoordinates
    while (parent != null) {
        val bounds = parent.boundsInWindow()
        if (bounds.hasArea()) return bounds
        parent = parent.parentLayoutCoordinates
    }
    return null
}

data class ViewportThumbnailGate(val request: Boolean, val modifier: Modifier)

/**
 * The request is *absent*, not merely alpha=0, until this item's placement enters the viewport.
 * onGloballyPositioned also runs as a lazy item moves, so offscreen work is cancelled promptly.
 * Only a Boolean state change causes recomposition; pixel-by-pixel scrolling does not.
 */
@Composable
fun rememberViewportThumbnailGate(modelKey: Any?): ViewportThumbnailGate {
    val enabled = LocalViewportThumbnailsEnabled.current
    val density = LocalDensity.current
    var allowed by remember(modelKey, enabled) { mutableStateOf(!enabled) }
    if (!enabled) return ViewportThumbnailGate(request = true, modifier = Modifier)
    if (modelKey == null) return ViewportThumbnailGate(request = false, modifier = Modifier)

    val enterX = with(density) { 200.dp.toPx() }
    val enterY = with(density) { 140.dp.toPx() }
    val exitX = with(density) { 280.dp.toPx() }
    val exitY = with(density) { 220.dp.toPx() }
    return ViewportThumbnailGate(
        request = allowed,
        modifier = Modifier.onGloballyPositioned { coordinates ->
            if (!coordinates.isAttached) {
                if (allowed) allowed = false
            } else {
                val inRange = shouldRequestThumbnail(
                    rawBounds = coordinates.boundsInWindow(clipBounds = false),
                    clippedBounds = coordinates.boundsInWindow(),
                    nearestVisibleAncestor = coordinates.nearestVisibleAncestor(),
                    horizontalMarginPx = if (allowed) exitX else enterX,
                    verticalMarginPx = if (allowed) exitY else enterY,
                )
                if (inRange != allowed) allowed = inRange
            }
        },
    )
}

/**
 * Coil's real AsyncImage remains in the layout (same size, modifier, clipping and semantics),
 * but receives a null model until the viewport gate permits requesting the URL.
 *
 * Desktop Coil's existing 32 MiB RAM / 100 MiB disk caches make revisiting fast without
 * keeping every shelf's decoded bitmap alive in the screen's retained state.
 */
@Composable
fun ViewportAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    colorFilter: ColorFilter? = null,
) {
    val gate = rememberViewportThumbnailGate(model)
    AsyncImage(
        model = if (gate.request) model else null,
        contentDescription = contentDescription,
        modifier = modifier.then(gate.modifier),
        contentScale = contentScale,
        alignment = alignment,
        colorFilter = colorFilter,
    )
}
