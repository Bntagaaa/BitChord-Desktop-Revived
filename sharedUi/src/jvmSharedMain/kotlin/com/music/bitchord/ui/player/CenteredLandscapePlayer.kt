package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.close_lyrics
import com.music.bitchord.sharedui.resources.open_lyrics
import com.music.bitchord.sharedui.resources.up_next
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.icons.SfStyleIcons
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

private enum class CenteredSlot { Artwork, Controls, Panes, BottomLeft, BottomRight, VolumePopup }

private val CENTERED_MAX_CONTENT_WIDTH = 1200.dp
private val CENTERED_MAX_ART_SIZE = 520.dp
private val CENTERED_MIN_CONTROLS_WIDTH = 280.dp

internal data class CenteredLandscapeGeometry(
    val artworkSize: Int,
    val stackWidth: Int,
    val stackTop: Int,
    val centeredStackX: Int,
    val leftStackX: Int,
    val rightPaneContainerLeft: Int,
    val rightPaneLeft: Int,
    val rightPaneWidth: Int,
    val bottomBarTop: Int,
)

internal fun centeredLandscapeGeometry(
    width: Int,
    height: Int,
    inset: Int,
    controlsHeight: Int,
    reservedControlsHeight: Int,
    bottomBarHeight: Int,
    stackGap: Int,
    bottomGap: Int,
    maxContentWidth: Int,
    maxArtSize: Int,
    minControlsWidth: Int,
): CenteredLandscapeGeometry {
    val contentWidth = minOf(width, maxContentWidth)
    val contentLeft = (width - contentWidth) / 2
    val leftWidth = contentWidth / 2
    val rightWidth = contentWidth - leftWidth
    val leftContentWidth = (leftWidth - inset * 2).coerceAtLeast(0)
    val stackAreaHeight = (height - bottomBarHeight - bottomGap).coerceAtLeast(0)
    val effectiveControlsHeight = maxOf(controlsHeight, reservedControlsHeight)
    val maxArtHeight = (stackAreaHeight - effectiveControlsHeight - stackGap).coerceAtLeast(0)
    val artSize = minOf(leftContentWidth, maxArtHeight, maxArtSize)
    val stackWidth = maxOf(artSize, minOf(leftContentWidth, minControlsWidth))
    val stackHeight = artSize + stackGap + controlsHeight
    val stackTop = ((stackAreaHeight - stackHeight) / 2).coerceAtLeast(0)
    val centeredStackX = ((width - stackWidth) / 2).coerceAtLeast(inset)
    val leftStackX = contentLeft + inset + ((leftContentWidth - stackWidth) / 2).coerceAtLeast(0)
    return CenteredLandscapeGeometry(
        artworkSize = artSize,
        stackWidth = stackWidth,
        stackTop = stackTop,
        centeredStackX = centeredStackX,
        leftStackX = leftStackX,
        rightPaneContainerLeft = contentLeft + leftWidth,
        rightPaneLeft = contentLeft + leftWidth + inset,
        rightPaneWidth = (rightWidth - inset * 2).coerceAtLeast(0),
        bottomBarTop = (height - bottomBarHeight).coerceAtLeast(0),
    )
}

/**
 * Centered-to-split landscape player shell sharing [LandscapePlayerShell] backdrop
 * and top drag handle with [LandscapePlayerLayout].
 */
@Composable
internal fun CenteredLandscapePlayerLayout(
    pane: PlayerPane,
    lyricsEnabled: Boolean,
    queueButtonVisible: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    background: @Composable (Modifier) -> Unit,
    artwork: @Composable (Modifier) -> Unit,
    header: @Composable (compact: Boolean, active: Boolean) -> Unit,
    scrubber: @Composable () -> Unit,
    transport: @Composable (compact: Boolean) -> Unit,
    volume: (@Composable () -> Unit)?,
    volumeLevel: () -> Float,
    lyricsPane: @Composable () -> Unit,
    queuePane: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    LandscapePlayerShell(background = background, modifier = modifier) { compact, gutter, reduceMotion, _ ->
        CenteredLandscapePlayerColumns(
            pane = pane,
            compact = compact,
            gutter = gutter,
            reduceMotion = reduceMotion,
            lyricsEnabled = lyricsEnabled,
            queueButtonVisible = queueButtonVisible,
            onToggleLyrics = onToggleLyrics,
            onToggleQueue = onToggleQueue,
            artwork = artwork,
            header = { active -> header(compact, active) },
            scrubber = scrubber,
            transport = { transport(compact) },
            volume = volume,
            volumeLevel = volumeLevel,
            lyricsPane = lyricsPane,
            queuePane = queuePane,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = LANDSCAPE_HANDLE_STRIP,
                    bottom = if (compact) 8.dp else 20.dp,
                ),
        )
    }
}

/**
 * Centered-to-split landscape player arrangement:
 * - In [PlayerPane.Main], artwork and controls stack in a centered column.
 * - Opening [PlayerPane.Lyrics] or [PlayerPane.Queue] slides that column left
 *   with `tween(360)` (matching the classic title/artist motion) while revealing
 *   the active side pane on the right.
 */
@Composable
internal fun CenteredLandscapePlayerColumns(
    pane: PlayerPane,
    compact: Boolean,
    gutter: Dp,
    reduceMotion: Boolean,
    lyricsEnabled: Boolean,
    queueButtonVisible: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    artwork: @Composable (Modifier) -> Unit,
    header: @Composable (active: Boolean) -> Unit,
    scrubber: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    volume: (@Composable () -> Unit)?,
    volumeLevel: () -> Float,
    lyricsPane: @Composable () -> Unit,
    queuePane: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var volumeOpen by remember { mutableStateOf(false) }
    LaunchedEffect(volume == null) {
        if (volume == null) volumeOpen = false
    }

    val panelTransition = updateTransition(pane, label = "centeredPlayerPosition")
    val paneFraction by panelTransition.animateFloat(
        transitionSpec = {
            if (reduceMotion) snap() else tween(360)
        },
        label = "centeredPaneFraction",
    ) { if (it == PlayerPane.Main) 0f else 1f }

    SubcomposeLayout(modifier.testTag("centered-landscape-columns")) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val maxContentPx = CENTERED_MAX_CONTENT_WIDTH.roundToPx()
        val contentWidth = minOf(width, maxContentPx)
        val leftWidth = contentWidth / 2
        val rightWidth = contentWidth - leftWidth
        val inset = gutter.roundToPx().coerceAtMost(minOf(leftWidth, rightWidth) / 2)
        val cornerInset = (if (compact) 16.dp else 24.dp).roundToPx().coerceAtMost(width / 4)
        val leftContentWidth = (leftWidth - inset * 2).coerceAtLeast(0)
        val stackGap = (if (compact) 10.dp else 22.dp).roundToPx()
        val controlGap = (if (compact) 4.dp else 10.dp).roundToPx()
        val bottomGap = (if (compact) 8.dp else 16.dp).roundToPx()
        val maxArtPx = CENTERED_MAX_ART_SIZE.roundToPx()
        val minControlsWidthPx = CENTERED_MIN_CONTROLS_WIDTH.roundToPx()

        val bottomLeft = subcompose(CenteredSlot.BottomLeft) {
            Box(Modifier.testTag("centered-bottom-left")) {
                if (lyricsEnabled) {
                    BottomGlyph(
                        icon = BitChordIcons.LyricsQuote,
                        contentDescription = stringResource(
                            if (pane == PlayerPane.Lyrics) Res.string.close_lyrics else Res.string.open_lyrics,
                        ),
                        onClick = onToggleLyrics,
                        highlighted = pane == PlayerPane.Lyrics,
                        vibrant = true,
                    )
                }
            }
        }.single().measure(Constraints(maxWidth = leftWidth, maxHeight = height))

        val bottomRight = subcompose(CenteredSlot.BottomRight) {
            Row(
                modifier = Modifier.testTag("centered-bottom-right"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (queueButtonVisible) {
                    BottomGlyph(
                        icon = BitChordIcons.Queue,
                        contentDescription = stringResource(Res.string.up_next),
                        onClick = onToggleQueue,
                        highlighted = pane == PlayerPane.Queue,
                        haptic = if (pane == PlayerPane.Queue) Haptic.Tap else Haptic.Expand,
                        vibrant = true,
                    )
                }
                if (volume != null) {
                    val muted = volumeLevel() <= 0f
                    BottomGlyph(
                        icon = if (muted) SfStyleIcons.VolumeOff else SfStyleIcons.VolumeUp,
                        contentDescription = "Volume",
                        onClick = { volumeOpen = !volumeOpen },
                        highlighted = volumeOpen,
                        vibrant = true,
                    )
                }
            }
        }.single().measure(Constraints(maxWidth = rightWidth, maxHeight = height))

        val bottomBarHeight = maxOf(bottomLeft.height, bottomRight.height)
        val stackAreaHeight = (height - bottomBarHeight - bottomGap).coerceAtLeast(0)
        val reservedControlsPx = (if (compact) 180.dp else 212.dp).roundToPx()
        val prelimArtSize = minOf(
            leftContentWidth,
            (stackAreaHeight - reservedControlsPx - stackGap).coerceAtLeast(0),
            maxArtPx,
        )
        val prelimStackWidth = maxOf(prelimArtSize, minOf(leftContentWidth, minControlsWidthPx))

        val controls = subcompose(CenteredSlot.Controls) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("centered-stack-controls"),
            ) {
                header(pane != PlayerPane.Queue)
                Spacer(Modifier.height(controlGap.toDp()))
                scrubber()
                Spacer(Modifier.height(controlGap.toDp()))
                transport()
            }
        }.single().measure(
            Constraints(
                minWidth = prelimStackWidth,
                maxWidth = prelimStackWidth,
                maxHeight = stackAreaHeight,
            ),
        )

        val geometry = centeredLandscapeGeometry(
            width = width,
            height = height,
            inset = inset,
            controlsHeight = controls.height,
            reservedControlsHeight = reservedControlsPx,
            bottomBarHeight = bottomBarHeight,
            stackGap = stackGap,
            bottomGap = bottomGap,
            maxContentWidth = maxContentPx,
            maxArtSize = maxArtPx,
            minControlsWidth = minControlsWidthPx,
        )

        val art = subcompose(CenteredSlot.Artwork) {
            Box(Modifier.testTag("landscape-artwork")) { artwork(Modifier.fillMaxSize()) }
        }.single().measure(Constraints.fixed(geometry.artworkSize, geometry.artworkSize))

        val paneBottomPadding = (bottomBarHeight + bottomGap).toDp()
        val paneTopPadding = geometry.stackTop.toDp()
        val rightPane = subcompose(CenteredSlot.Panes) {
            AnimatedContent(
                targetState = pane,
                transitionSpec = {
                    if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(180, delayMillis = 180)) togetherWith fadeOut(tween(100))
                },
                modifier = Modifier.fillMaxSize(),
                label = "centeredSidePane",
            ) { shown ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = inset.toDp(),
                            end = inset.toDp(),
                            top = paneTopPadding,
                            bottom = paneBottomPadding,
                        )
                        .testTag(if (shown == PlayerPane.Lyrics) "landscape-lyrics-body" else "landscape-other-pane")
                        .then(if (shown == PlayerPane.Main) Modifier.clearAndSetSemantics {} else Modifier)
                        .then(if (shown == PlayerPane.Lyrics) Modifier.landscapeLyricEdges() else Modifier),
                ) {
                    when (shown) {
                        PlayerPane.Main -> Unit
                        PlayerPane.Lyrics -> lyricsPane()
                        PlayerPane.Queue -> queuePane()
                    }
                }
            }
        }.single().measure(Constraints.fixed(rightWidth, height))

        val volumePopup = subcompose(CenteredSlot.VolumePopup) {
            Box {
                AnimatedVisibility(
                    visible = volume != null && volumeOpen,
                    enter = fadeIn(tween(140)),
                    exit = fadeOut(tween(120)),
                ) {
                    Box(
                        modifier = Modifier
                            .width(236.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("centered-volume-popup"),
                    ) {
                        volume?.invoke()
                    }
                }
            }
        }.single().measure(Constraints(maxWidth = width, maxHeight = height))

        val stackX = (geometry.centeredStackX + (geometry.leftStackX - geometry.centeredStackX) * paneFraction).roundToInt()

        layout(width, height) {
            art.placeRelative(stackX + (geometry.stackWidth - art.width) / 2, geometry.stackTop)
            controls.placeRelative(
                stackX + (geometry.stackWidth - controls.width) / 2,
                geometry.stackTop + art.height + stackGap,
            )
            if (pane != PlayerPane.Main || paneFraction > 0f) {
                rightPane.placeRelative(geometry.rightPaneContainerLeft, 0)
            }
            bottomLeft.placeRelative(cornerInset, geometry.bottomBarTop)
            bottomRight.placeRelative(
                (width - cornerInset - bottomRight.width).coerceAtLeast(0),
                geometry.bottomBarTop,
            )
            if (volume != null && volumeOpen && volumePopup.width > 0) {
                val popupGap = 10.dp.roundToPx()
                volumePopup.placeRelative(
                    (width - cornerInset - volumePopup.width).coerceAtLeast(cornerInset),
                    (geometry.bottomBarTop - volumePopup.height - popupGap).coerceAtLeast(0),
                )
            }
        }
    }
}
