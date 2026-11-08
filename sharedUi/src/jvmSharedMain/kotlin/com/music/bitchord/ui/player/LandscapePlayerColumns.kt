package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class PlayerPane { Main, Lyrics, Queue }
private enum class LandscapeSlot { Actions, Artwork, Header, Panes }

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
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val leftWidth = width / 2
        val rightWidth = width - leftWidth
        val inset = gutter.roundToPx().coerceAtMost(minOf(leftWidth, rightWidth) / 2)
        val contentWidth = (leftWidth - inset * 2).coerceAtLeast(0)
        val actionRow = subcompose(LandscapeSlot.Actions) { Box { actions() } }.single()
            .measure(Constraints(maxWidth = contentWidth, maxHeight = height))
        val geometry = landscapeColumnGeometry(contentWidth, height, actionRow.height,
            (if (compact) 12.dp else 24.dp).roundToPx())
        val art = subcompose(LandscapeSlot.Artwork) {
            Box { artwork(Modifier.fillMaxSize()) }
        }.single().measure(Constraints.fixed(geometry.artworkSize, geometry.artworkSize))
        val metadata = subcompose(LandscapeSlot.Header) {
            Box { header(pane != PlayerPane.Queue) }
        }.single().measure(Constraints(
            minWidth = (rightWidth - inset * 2).coerceAtLeast(0),
            maxWidth = (rightWidth - inset * 2).coerceAtLeast(0),
            maxHeight = (height - geometry.headerTop).coerceAtLeast(0),
        ))
        val bodyTop = (geometry.headerTop + metadata.height + (if (compact) 4.dp else 12.dp).roundToPx())
            .coerceAtMost(height)
        val topInset = bodyTop.toDp()
        val body = subcompose(LandscapeSlot.Panes) {
            AnimatedContent(
                targetState = pane,
                transitionSpec = {
                    if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None else
                        fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(140))
                },
                modifier = Modifier.fillMaxSize(),
                label = "landscapePane",
            ) { shown ->
                // The transition container always occupies the complete column. Each pane
                // gets its own inset, so switching Queue never translates an exiting panel.
                Box(Modifier.fillMaxSize().padding(
                    start = gutter, end = gutter,
                    top = if (shown == PlayerPane.Queue) 0.dp else topInset,
                )) {
                    when (shown) {
                        PlayerPane.Main -> mainPane()
                        PlayerPane.Lyrics -> lyricsPane()
                        PlayerPane.Queue -> queuePane()
                    }
                }
            }
        }.single().measure(Constraints.fixed(rightWidth, height))
        layout(width, height) {
            art.placeRelative(inset + (contentWidth - art.width) / 2, geometry.artworkTop)
            actionRow.placeRelative(inset + (contentWidth - actionRow.width) / 2, height - actionRow.height)
            body.placeRelative(leftWidth, 0)
            if (pane != PlayerPane.Queue) metadata.placeRelative(leftWidth + inset, geometry.headerTop)
        }
    }
}
