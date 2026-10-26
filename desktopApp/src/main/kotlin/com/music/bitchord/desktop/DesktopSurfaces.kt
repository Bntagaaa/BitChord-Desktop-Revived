package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.border
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

// The look every modal card in the app shares.

/** The card fill. */
internal val DesktopCardFill: Color get() = DesktopTheme.palette.cardFill

/** A block inside a card — a settings group — lifted just off the fill. */
internal val DesktopCardInsetFill: Color get() = DesktopTheme.palette.cardInsetFill

/** The hairline that bounds a card, and separates one row from the next. */
internal val DesktopCardEdge: Color get() = DesktopTheme.palette.cardEdge

/** What a row reads at while the pointer is on it. */
internal val DesktopRowHover: Color get() = DesktopTheme.palette.rowHover

/** The scrim a modal card sits on. */
internal val DesktopScrim: Color get() = DesktopTheme.palette.scrim

/** Card, hairline and all. */
internal fun Modifier.desktopCard(shape: Shape): Modifier =
    clip(shape).background(DesktopCardFill).border(0.5.dp, DesktopCardEdge, shape)

/** A block of rows inside a card. */
internal fun Modifier.desktopCardInset(shape: Shape): Modifier =
    clip(shape).background(DesktopCardInsetFill).border(0.5.dp, DesktopCardEdge, shape)

/** The rule between two rows of a card. A page of Settings has none: its rows are spaced, not ruled. */
@Composable
internal fun DesktopCardRule(modifier: Modifier = Modifier) {
    if (LocalDesktopPanelIsPage.current) return
    HorizontalDivider(modifier, thickness = 0.5.dp, color = DesktopCardEdge)
}

/**
 * Whether the panels drawn here are pages of Settings rather than cards over the page. Provided by
 * the Settings host, so the same screen is a card from the player and a page from Settings.
 */
internal val LocalDesktopPanelIsPage = staticCompositionLocalOf { false }

/** The margin either side of a page of Settings: every title, heading and row starts on it. */
internal val DesktopSettingsPageGutter = 28.dp

/**
 * How wide Settings and its pages run: the width the Settings card had, so a page in a wide window
 * reads as a column of rows rather than rows stretched to the window's edges.
 */
internal val DesktopSettingsPageWidth = 840.dp

/** A page of Settings: a column down the middle of the page area, as tall as the area. */
@Composable
internal fun DesktopSettingsPageFrame(
    maxWidth: Dp = DesktopSettingsPageWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = DesktopSettingsPageGutter),
            content = content,
        )
    }
}

/**
 * The part of a panel that scrolls. On a card it stops at [cardMax] so the card stays a card; on a
 * page it takes whatever the page has left below the heading and above the actions, which stay
 * where they are rather than scrolling away with the rows. [fill] gives a page the whole of it even
 * while its rows are few.
 */
@Composable
internal fun ColumnScope.desktopPanelBody(
    cardMax: Dp,
    cardMin: Dp = 0.dp,
    fill: Boolean = false,
): Modifier = if (LocalDesktopPanelIsPage.current) {
    Modifier.weight(1f, fill = fill)
} else {
    Modifier.heightIn(min = cardMin, max = cardMax)
}

/**
 * A panel's own side inset: [card] on a card, none on a page, where the page's gutter is the inset
 * and the rows line up under the title.
 */
@Composable
internal fun panelInset(card: Dp): Dp = if (LocalDesktopPanelIsPage.current) 0.dp else card

/**
 * A row's tap. On a page the row does not light up under the pointer — only its switch, slider or
 * chip does; on a card it keeps the usual wash.
 */
@Composable
internal fun Modifier.desktopRowClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return if (LocalDesktopPanelIsPage.current) {
        clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
    } else {
        clickable(enabled = enabled, onClick = onClick)
    }
}

/**
 * The dialog shell: the same card the song menu is drawn on — or, inside Settings, a page of it.
 *
 * [popup] keeps it a card even there, for a prompt a page opens over itself.
 */
@Composable
internal fun DesktopDialogPanel(
    onDismiss: () -> Unit,
    maxWidth: Int,
    popup: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    DesktopDialogPanel(onDismiss, maxWidth, popup, dismissOnEscape = false, content = content)
}

/** Opt-in dismissal keeps existing positional callers and unrelated dialog behavior unchanged. */
@Composable
internal fun DesktopDialogPanel(
    onDismiss: () -> Unit,
    maxWidth: Int,
    popup: Boolean = false,
    dismissOnEscape: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!popup && LocalDesktopPanelIsPage.current) {
        DesktopSettingsPageFrame(
            maxWidth = maxOf(maxWidth.dp, DesktopSettingsPageWidth),
            content = content,
        )
        return
    }
    DesktopRegisterFlyout(
        layer = DesktopFlyoutLayer.DIALOG,
        onDismiss = if (dismissOnEscape) onDismiss else null,
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DesktopFlyoutBackdrop(onDismiss = onDismiss)
        DesktopFlyoutCard(Modifier.widthIn(max = maxWidth.dp).fillMaxWidth()) {
            Column(
                Modifier.padding(bottom = 4.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                // A prompt opened from a page is a card, and so is anything it opens in turn.
                CompositionLocalProvider(LocalDesktopPanelIsPage provides false) {
                    content()
                }
            }
        }
    }
}

/** Audio Output's visual shell, also used by Quick Search and Keyboard Shortcuts. */
@Composable
internal fun DesktopFlyoutCard(
    modifier: Modifier = Modifier,
    onBackgroundClick: () -> Unit = {},
    /** Quick Search gets its own blur preference; unrelated dialogs remain opaque. */
    frosted: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val decoration = if (frosted) {
        modifier.clip(shape).desktopBarGlass(shape, DesktopBlurArea.QUICK_SEARCH)
    } else {
        modifier.desktopCard(shape)
    }
    Box(decoration) {
        // A separate pointer-only sibling: clicking content cannot dismiss the backdrop or leave
        // keyboard focus on a decorative panel that will be removed when the flyout closes.
        Box(
            Modifier.matchParentSize().focusProperties { canFocus = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBackgroundClick,
                ),
        )
        content()
    }
}

@Composable
internal fun DesktopFlyoutBackdrop(onDismiss: () -> Unit, blockScroll: Boolean = false) {
    Box(
        Modifier.fillMaxSize().background(DesktopScrim)
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .pointerInput(blockScroll) {
                if (blockScroll) awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.type == PointerEventType.Scroll) event.changes.forEach { it.consume() }
                    }
                }
            },
    )
}

/** Input and results share a focus lifetime, so a row can finish its click. */
internal fun Modifier.desktopSearchFocus(session: DesktopSearchSession<*>): Modifier =
    onFocusChanged { if (!it.hasFocus) session.dismissSuggestions() }.focusGroup()

/** Search captures its landing as a sibling, never the subtree containing this effect. */
@Composable
internal fun Modifier.desktopSearchGlass(
    haze: HazeState,
    shape: Shape,
    reduceBlur: Boolean,
): Modifier {
    val fill = DesktopSearchFill
    val surface = if (reduceBlur) {
        Modifier.background(fill)
    } else {
        // Paint underneath the effect, so an unattached/unsupported source cannot flash clear.
        // The effect still paints its blurred sibling sources over this base.
        Modifier.background(fill).hazeEffect(state = haze) {
            blurEnabled = true
            backgroundColor = fill
            blurRadius = 32.dp
            tints = listOf(HazeTint(fill.copy(alpha = 0.82f)))
            fallbackTint = HazeTint(fill)
            noiseFactor = 0.02f
        }
    }
    return clip(shape).then(surface).border(0.5.dp, DesktopBarEdge, shape)
}

/** Shared appearance for desktop dropdown menus; actions retain their existing focus handling. */
@Composable
internal fun DesktopDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.desktopMenuGlass(shape),
        shape = shape,
        containerColor = Color.Transparent,
        content = content,
    )
}
