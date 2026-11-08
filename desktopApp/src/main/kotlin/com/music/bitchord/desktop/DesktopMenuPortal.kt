package com.music.bitchord.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Renders menus in the main window so Haze can sample the actual page below them. */
internal class DesktopMenuPortalState {
    internal val entry: MutableState<DesktopMenuPortalEntry?> = mutableStateOf(null)
    internal var onClosed: () -> Unit = {}
    internal var originInRoot: Offset = Offset.Zero
    private var press: Offset? = null
    private var pressedAt = 0L

    internal fun recordPress(position: Offset) {
        press = position + originInRoot
        pressedAt = System.nanoTime()
    }

    internal fun recentPress(): Rect? = press
        ?.takeIf { System.nanoTime() - pressedAt < 500_000_000L }
        ?.let { Rect(it, Size.Zero) }
}

internal fun Modifier.desktopMenuPointer(state: DesktopMenuPortalState): Modifier =
    onGloballyPositioned { state.originInRoot = it.positionInRoot() }
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) {
                        event.changes.firstOrNull()?.let { state.recordPress(it.position) }
                    }
                }
            }
        }

internal data class DesktopMenuPortalEntry(
    val owner: Any,
    val horizontalBiasPx: Int = 0,
    val anchor: Rect,
    val onDismiss: () -> Unit,
    val content: @Composable () -> Unit,
)

internal val LocalDesktopMenuPortal = staticCompositionLocalOf<DesktopMenuPortalState?> { null }
/** Only the fullscreen player's popup needs a negative placement bias. */
internal val LocalDesktopMenuHorizontalBias = staticCompositionLocalOf { 0.dp }

/**
 * Anchor-aware compact-menu motion. Only the popup's draw layer changes during
 * the transition: measured dimensions, pointer targets and layout stay intact.
 */
internal object DesktopContextMenuMotion {
    const val OPEN_MS = 190
    const val CLOSE_MS = 170
    private const val INITIAL_SCALE = 0.30f

    fun scale(progress: Float, reduceMotion: Boolean): Float =
        if (reduceMotion) 1f else INITIAL_SCALE + (1f - INITIAL_SCALE) * progress.coerceIn(0f, 1f)

    fun scrimAlpha(progress: Float, dark: Boolean): Float =
        progress.coerceIn(0f, 1f) * if (dark) 0.16f else 0.10f
}

/**
 * An action disposes its menu immediately, but the portal retains its content
 * long enough to animate out. A transparent hit target protects against double
 * actions during the exit; after it completes the content is released.
 *
 * Haze samples only the card from the page behind it. The dimming scrim draws
 * a solid rectangle and adds no full-screen offscreen texture.
 */
@Composable
internal fun DesktopMenuPortal(state: DesktopMenuPortalState) {
    val active = state.entry.value
    var retained by remember { mutableStateOf<DesktopMenuPortalEntry?>(null) }
    if (active != null) SideEffect { retained = active }
    val shown = active ?: retained ?: return
    val reduceMotion by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val progress = remember { Animatable(0f) }

    LaunchedEffect(active?.owner, active != null, reduceMotion) {
        if (active != null) {
            if (reduceMotion) progress.snapTo(1f)
            else progress.animateTo(1f, spring(dampingRatio = 1f, stiffness = 470f))
        } else {
            if (reduceMotion) progress.snapTo(0f)
            else progress.animateTo(0f, tween(DesktopContextMenuMotion.CLOSE_MS, easing = FastOutSlowInEasing))
            if (state.entry.value == null) retained = null
        }
    }

    val focusRequester = remember(shown.owner) { FocusRequester() }
    LaunchedEffect(active?.owner) {
        if (active != null) focusRequester.requestFocus()
    }
    val dark = DesktopTheme.isDark
    Box(
        Modifier.fillMaxSize().focusRequester(focusRequester)
            .onPreviewKeyEvent {
                if (active != null && it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                    active.onDismiss()
                    true
                } else false
            }.focusable(),
    ) {
        val density = LocalDensity.current
        Box(
            Modifier.fillMaxSize()
                .drawBehind {
                    drawRect(Color.Black.copy(alpha = DesktopContextMenuMotion.scrimAlpha(progress.value, dark)))
                }
                .focusProperties { canFocus = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = active != null,
                ) { active?.onDismiss() }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) event.changes.forEach { it.consume() }
                        }
                    }
                },
        )
        val margin = with(density) { 12.dp.roundToPx() }
        Layout(
            content = {
                Box {
                    shown.content()
                    if (active == null) {
                        Box(
                            Modifier.matchParentSize().clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {},
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val child = measurables.first().measure(constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0),
                maxHeight = (constraints.maxHeight - 2 * margin).coerceAtLeast(0),
            ))
            val anchor = shown.anchor.translate(-state.originInRoot)
            val below = anchor.bottom.roundToInt() + margin
            val above = anchor.top.roundToInt() - child.height - margin
            val placeAbove = below + child.height + margin > constraints.maxHeight && above >= margin
            val y = if (placeAbove) above else below
            val x = (anchor.left.roundToInt() + shown.horizontalBiasPx).coerceIn(
                margin, (constraints.maxWidth - child.width - margin).coerceAtLeast(margin),
            )
            val finalY = y.coerceIn(
                margin, (constraints.maxHeight - child.height - margin).coerceAtLeast(margin),
            )
            val pivotX = if (anchor.center.x > constraints.maxWidth * 0.5f) 1f else 0f
            layout(constraints.maxWidth, constraints.maxHeight) {
                child.placeWithLayer(x, finalY) {
                    val p = progress.value.coerceIn(0f, 1f)
                    alpha = p
                    val scale = DesktopContextMenuMotion.scale(p, reduceMotion)
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(pivotX, if (placeAbove) 1f else 0f)
                    // A direct layer alpha; no extra full-screen framebuffer.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
            }
        }
    }
}
