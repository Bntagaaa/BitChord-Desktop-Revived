package com.music.bitchord.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

internal const val LANDSCAPE_CHROME_MOTION_MS = 200

@Stable
internal class LandscapeLyricsChrome {
    var bodyActive by mutableStateOf(false)
    var headerHovered by mutableStateOf(false)
}
internal val LocalLandscapeLyricsChrome = compositionLocalOf<LandscapeLyricsChrome?> { null }

/** A fixed hover target; only the visible lyric viewport gives space to the footer. */
@Composable
internal fun LandscapeLyricsFrame(
    revealControlsOnHover: Boolean,
    controlsPinned: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    lyrics: @Composable BoxScope.() -> Unit,
    controls: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var hasFocus by remember { mutableStateOf(false) }
    var keyboardFocused by remember { mutableStateOf(false) }
    val chrome = LocalLandscapeLyricsChrome.current
    SideEffect { chrome?.bodyActive = hovered || keyboardFocused || controlsPinned }
    DisposableEffect(chrome) { onDispose { chrome?.bodyActive = false } }
    val visible = !revealControlsOnHover || hovered || keyboardFocused || controlsPinned || chrome?.headerHovered == true
    val reveal by animateFloatAsState(
        if (visible) 1f else 0f,
        if (reduceMotion) snap() else tween(LANDSCAPE_CHROME_MOTION_MS),
        label = "landscapeLyricsControls",
    )
    Layout(
        modifier = modifier.fillMaxSize().testTag("landscape-lyrics-frame").then(
            if (revealControlsOnHover) Modifier.clipToBounds() else Modifier,
        ).then(
            if (revealControlsOnHover) Modifier
                .hoverable(interaction)
                .onFocusChanged {
                    val entered = it.hasFocus && !hasFocus
                    hasFocus = it.hasFocus
                    if (!it.hasFocus) keyboardFocused = false
                    else if (entered && !hovered) keyboardFocused = true
                }
                .onPreviewKeyEvent { keyboardFocused = true; false }
                .focusable()
            else Modifier,
        ),
        content = {
            Layout(
                modifier = Modifier.testTag("landscape-lyrics-viewport").then(
                    if (revealControlsOnHover) Modifier.clipToBounds() else Modifier,
                ),
                content = { Box(Modifier.fillMaxSize(), content = lyrics) },
            ) { measurables, bounds ->
                // Keep the lyric renderer's own viewport/scroll anchor unchanged while
                // hovering. Its clipping parent gets shorter, not its text or scroll state.
                val content = measurables.single().measure(Constraints.fixed(bounds.maxWidth, bounds.maxHeight))
                layout(bounds.maxWidth, bounds.minHeight) { content.placeRelative(0, 0) }
            }
            Column(Modifier.then(if (visible) Modifier else Modifier.clearAndSetSemantics {})) {
                controls()
            }
        },
    ) { measurables, bounds ->
        val width = bounds.maxWidth
        val height = bounds.maxHeight
        val footer = measurables[1].measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
        val reserved = (footer.height * reveal).roundToInt().coerceIn(0, height)
        val viewportHeight = height - reserved
        val contentHeight = if (revealControlsOnHover) height else viewportHeight
        val viewport = measurables[0].measure(Constraints(
            minWidth = width, maxWidth = width,
            minHeight = viewportHeight, maxHeight = contentHeight,
        ))
        layout(width, height) {
            viewport.placeRelative(0, 0)
            if (reserved > 0) footer.placeRelativeWithLayer(0, viewportHeight) { alpha = reveal }
        }
    }
}
