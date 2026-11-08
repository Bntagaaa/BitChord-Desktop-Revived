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
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
    boundLyricsToArtwork: Boolean = false,
    trackKey: String? = null,
) {
    val chrome = remember { LandscapeLyricsChrome() }
    val headerInteraction = remember { MutableInteractionSource() }
    val headerHovered by headerInteraction.collectIsHoveredAsState()
    SideEffect { chrome.headerHovered = headerHovered && pane == PlayerPane.Lyrics }
    var introductoryHeader by remember(pane, boundLyricsToArtwork, trackKey) { mutableStateOf(true) }
    LaunchedEffect(pane, boundLyricsToArtwork, trackKey) {
        if (pane == PlayerPane.Lyrics && boundLyricsToArtwork) {
            delay(2000)
            introductoryHeader = false
        }
    }
    val showHeader = pane != PlayerPane.Lyrics || !boundLyricsToArtwork ||
        introductoryHeader || chrome.bodyActive || headerHovered
    val headerReveal by animateFloatAsState(if (showHeader) 1f else 0f,
        if (reduceMotion) snap() else tween(LANDSCAPE_CHROME_MOTION_MS), label = "landscapeHeaderReveal")
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
    SubcomposeLayout(modifier.testTag("landscape-columns")) { constraints ->
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
        val actionTop = if (boundLyricsToArtwork) {
            val coverBottom = geometry.artworkTop + geometry.artworkSize
            coverBottom + (height - coverBottom - actionRow.height) / 2
        } else height - actionRow.height
        val art = subcompose(LandscapeSlot.Artwork) {
            Box(Modifier.testTag("landscape-artwork")) { artwork(Modifier.fillMaxSize()) }
        }.single().measure(Constraints.fixed(geometry.artworkSize, geometry.artworkSize))
        val metadata = subcompose(LandscapeSlot.Header) {
            Box(Modifier.testTag("landscape-track-header").clipToBounds()
                .hoverable(headerInteraction, enabled = boundLyricsToArtwork && pane == PlayerPane.Lyrics)) {
                Box(Modifier
                    .then(if (showHeader) Modifier else Modifier.clearAndSetSemantics {})
                    .layout { measurable, bounds ->
                        val child = measurable.measure(bounds)
                        layout(child.width, child.height) {
                            if (headerReveal > 0f) child.placeRelativeWithLayer(0, 0) {
                                translationY = -(1f - headerReveal) * child.height
                                alpha = headerReveal
                            }
                        }
                    }) { header(pane != PlayerPane.Queue) }
            }
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
            maxHeight = ((if (boundLyricsToArtwork) actionTop else height) - metadata.height - headerGap).coerceAtLeast(0),
        ))
        val mainStackHeight = metadata.height + headerGap + main.height
        val mainHeaderTop = ((height - mainStackHeight) / 2).coerceIn(0,
            ((if (boundLyricsToArtwork) actionTop else height) - mainStackHeight).coerceAtLeast(0))
        fun headerTop(): Int {
            val position = if (panelTransition.currentState == PlayerPane.Queue || pane == PlayerPane.Queue)
                if (pane == PlayerPane.Lyrics) 1f else 0f
            else lyricsFraction
            return (mainHeaderTop + (geometry.headerTop - mainHeaderTop) * position).roundToInt()
        }
        val bodyTop = (geometry.headerTop + metadata.height + headerGap)
            .coerceAtMost(height)
        val topInset = bodyTop.toDp()
        val lyricsBottomInset = if (boundLyricsToArtwork)
            (height - geometry.artworkTop - geometry.artworkSize).coerceAtLeast(0).toDp()
        else 0.dp
        val body = subcompose(LandscapeSlot.Panes) {
          CompositionLocalProvider(LocalLandscapeLyricsChrome provides chrome) {
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
                    top = if (shown == PlayerPane.Queue || (shown == PlayerPane.Lyrics && boundLyricsToArtwork)) {
                        if (boundLyricsToArtwork) geometry.artworkTop.toDp() else 0.dp
                    } else topInset,
                    bottom = if (shown != PlayerPane.Main) lyricsBottomInset else 0.dp,
                ).testTag(if (shown == PlayerPane.Lyrics) "landscape-lyrics-body" else "landscape-other-pane").then(
                    if (shown == PlayerPane.Lyrics && boundLyricsToArtwork) Modifier.landscapeLyricEdges(
                        top = { (headerTop() + metadata.height - geometry.artworkTop).coerceAtLeast(0) * headerReveal },
                        fadeBottom = false,
                    ) else Modifier.drawWithContent {
                        val clipTop = if (shown == PlayerPane.Lyrics)
                            (headerTop() + metadata.height + headerGap - bodyTop).coerceAtLeast(0).toFloat()
                        else 0f
                        clipRect(top = clipTop.coerceAtMost(size.height)) { this@drawWithContent.drawContent() }
                    }
                )) {
                    when (shown) {
                        PlayerPane.Main -> Unit
                        PlayerPane.Lyrics -> lyricsPane()
                        PlayerPane.Queue -> queuePane()
                    }
                }
            }
          }
        }.single().measure(Constraints.fixed(rightWidth, height))
        layout(width, height) {
            art.placeRelative(inset + (contentWidth - art.width) / 2, geometry.artworkTop)
            val actionLeft = if (boundLyricsToArtwork) (width - actionRow.width) / 2
                else inset + (contentWidth - actionRow.width) / 2
            actionRow.placeRelative(actionLeft, actionTop)
            if (mainAlpha > 0f) main.placeRelative(
                leftWidth + (rightWidth - main.width) / 2, mainHeaderTop + metadata.height + headerGap)
            body.placeRelative(leftWidth, 0)
            if (pane != PlayerPane.Queue) metadata.placeRelative(leftWidth + inset, headerTop())
        }
    }
}
