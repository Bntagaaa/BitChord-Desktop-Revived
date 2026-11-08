package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal enum class PlayerPane { Main, Lyrics, Queue }
private enum class LandscapeSlot { Actions, Artwork, Header, Main, Panes }

internal data class LandscapeColumnGeometry(val artworkSize: Int, val artworkTop: Int, val headerTop: Int)

internal fun landscapeColumnGeometry(width: Int, height: Int, actionsHeight: Int, gap: Int): LandscapeColumnGeometry {
    val artworkArea = (height - actionsHeight - gap).coerceAtLeast(0)
    val size = minOf(width.coerceAtLeast(0), artworkArea)
    val top = (artworkArea - size) / 2
    return LandscapeColumnGeometry(size, top, top)
}

/** One measure pass owns the cover, action row, fixed header, and bounded pane slots. */
@Composable
internal fun LandscapePlayerColumns(
    pane: PlayerPane,
    compact: Boolean,
    gutter: Dp,
    reduceMotion: Boolean,
    artwork: @Composable (Modifier) -> Unit,
    actions: @Composable () -> Unit,
    header: @Composable (active: Boolean) -> Unit,
    mainPane: @Composable () -> Unit,
    lyricsPane: @Composable () -> Unit,
    queuePane: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // This moves the one persistent header between its two real layouts. Song
    // changes only move the text inside it, never the controls or lyric list.
    val panelTransition = updateTransition(pane, label = "landscapeHeaderPosition")
    val lyricsFraction by panelTransition.animateFloat(transitionSpec = {
        if (reduceMotion || initialState == PlayerPane.Queue || targetState == PlayerPane.Queue) snap()
        else tween(360)
    }, label = "lyricsPosition") { if (it == PlayerPane.Lyrics) 1f else 0f }
    val mainAlpha by animateFloatAsState(
        if (pane == PlayerPane.Main) 1f else 0f,
        if (reduceMotion) snap() else tween(120, delayMillis = if (pane == PlayerPane.Main) 240 else 0),
        label = "landscapeMainControls",
    )
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val leftWidth = width / 2
        val rightWidth = width - leftWidth
        val inset = gutter.roundToPx().coerceAtMost(minOf(leftWidth, rightWidth) / 2)
        val contentWidth = (leftWidth - inset * 2).coerceAtLeast(0)
        // The slot emits the action row, a spacer AND the output caption.
        // A Box overlays those siblings and also reports the wrong artwork area.
        val actionRow = subcompose(LandscapeSlot.Actions) {
            Column(Modifier.testTag("landscape-output-controls")) { actions() }
        }.single()
            .measure(Constraints(maxWidth = contentWidth, maxHeight = height))
        val geometry = landscapeColumnGeometry(contentWidth, height, actionRow.height,
            (if (compact) 12.dp else 24.dp).roundToPx())
        val art = subcompose(LandscapeSlot.Artwork) {
            Box(Modifier.testTag("landscape-artwork")) { artwork(Modifier.fillMaxSize()) }
        }.single().measure(Constraints.fixed(geometry.artworkSize, geometry.artworkSize))
        val metadata = subcompose(LandscapeSlot.Header) {
            Box(Modifier.testTag("landscape-track-header")) { header(pane != PlayerPane.Queue) }
        }.single().measure(Constraints(
            minWidth = (rightWidth - inset * 2).coerceAtLeast(0),
            maxWidth = (rightWidth - inset * 2).coerceAtLeast(0),
            maxHeight = height,
        ))
        val headerGap = (if (compact) 4.dp else 12.dp).roundToPx()
        val main = subcompose(LandscapeSlot.Main) {
            Box(Modifier
                .testTag("landscape-main-controls")
                .then(if (pane == PlayerPane.Main) Modifier else Modifier.clearAndSetSemantics {})
                .graphicsLayer { alpha = mainAlpha }) { mainPane() }
        }.single().measure(Constraints(
            maxWidth = (rightWidth - inset * 2).coerceAtLeast(0),
            maxHeight = (height - metadata.height - headerGap).coerceAtLeast(0),
        ))
        val mainHeaderTop = ((height - metadata.height - headerGap - main.height) / 2).coerceAtLeast(0)
        fun headerTop(): Int {
            val position = if (panelTransition.currentState == PlayerPane.Queue || pane == PlayerPane.Queue)
                if (pane == PlayerPane.Lyrics) 1f else 0f
            else lyricsFraction
            return (mainHeaderTop + (geometry.headerTop - mainHeaderTop) * position).roundToInt()
        }
        val bodyTop = (geometry.headerTop + metadata.height + headerGap)
            .coerceAtMost(height)
        val topInset = bodyTop.toDp()
        val body = subcompose(LandscapeSlot.Panes) {
            AnimatedContent(
                targetState = pane,
                transitionSpec = {
                    if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None else
                        fadeIn(tween(180, delayMillis = 180)) togetherWith fadeOut(tween(100))
                },
                modifier = Modifier.fillMaxSize(),
                label = "landscapePane",
            ) { shown ->
                // The transition container always occupies the complete column. Each pane
                // gets its own inset, so switching Queue never translates an exiting panel.
                Box(Modifier.fillMaxSize().padding(
                    start = inset.toDp(), end = inset.toDp(),
                    top = if (shown == PlayerPane.Queue) 0.dp else topInset,
                ).then(if (shown == PlayerPane.Lyrics) Modifier.testTag("landscape-lyrics-body") else Modifier).drawWithContent {
                    // Reveal the lyrics beneath the travelling header. Their layout stays
                    // fixed, and no lyric can draw through the header during either direction.
                    val clipTop = if (shown == PlayerPane.Lyrics)
                        (headerTop() + metadata.height + headerGap - bodyTop).coerceAtLeast(0).toFloat()
                    else 0f
                    clipRect(top = clipTop.coerceAtMost(size.height)) { this@drawWithContent.drawContent() }
                }) {
                    when (shown) {
                        PlayerPane.Main -> Unit
                        PlayerPane.Lyrics -> lyricsPane()
                        PlayerPane.Queue -> queuePane()
                    }
                }
            }
        }.single().measure(Constraints.fixed(rightWidth, height))
        layout(width, height) {
            art.placeRelative(inset + (contentWidth - art.width) / 2, geometry.artworkTop)
            actionRow.placeRelative(inset + (contentWidth - actionRow.width) / 2, height - actionRow.height)
            if (mainAlpha > 0f) main.placeRelative(
                leftWidth + (rightWidth - main.width) / 2, mainHeaderTop + metadata.height + headerGap)
            body.placeRelative(leftWidth, 0)
            if (pane != PlayerPane.Queue) metadata.placeRelative(leftWidth + inset, headerTop())
        }
    }
}
