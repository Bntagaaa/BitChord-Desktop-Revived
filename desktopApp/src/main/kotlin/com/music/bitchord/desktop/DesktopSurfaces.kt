package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.border
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.desktop.icons.DesktopIcons
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

/** A popup card separator. Settings groups supply their own inset dividers. */
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
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val gutter = if (maxWidth < 500.dp) 16.dp else DesktopSettingsPageGutter
        Column(
            Modifier
                .widthIn(max = DesktopSettingsPageWidth)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = gutter),
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
    // Settings, panels and dialog rows all receive the same faint animated hover.
    // The previous page-only exception made much of Settings feel inert.
    val hoverAlpha = if (LocalDesktopPanelIsPage.current) 0.045f else 0.06f
    return this
        .then(if (enabled) Modifier.desktopHoverFill(alpha = hoverAlpha) else Modifier)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
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
        DesktopSettingsPageFrame(content = content)
        return
    }
    DesktopRegisterFlyout(
        layer = DesktopFlyoutLayer.DIALOG,
        onDismiss = if (dismissOnEscape) onDismiss else null,
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DesktopFlyoutBackdrop(onDismiss = onDismiss)
        DesktopFlyoutCard(
            Modifier.padding(16.dp).widthIn(max = maxWidth.dp).fillMaxWidth(),
            glass = true,
        ) {
            Column(
                Modifier.padding(bottom = 8.dp),
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
    /** Modernized modal dialogs share the New playlist glass shell. */
    glass: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val decoration = when {
        frosted -> modifier.clip(shape).desktopBarGlass(shape, DesktopBlurArea.QUICK_SEARCH)
        glass -> modifier.clip(shape).desktopBarGlass(shape)
        else -> modifier.desktopCard(shape)
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
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

/** Compact, consistent rows for desktop dropdowns (sort, language and display-scale choices). */
@Composable
internal fun DesktopDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    androidx.compose.material3.DropdownMenuItem(
        text = text,
        onClick = onClick,
        trailingIcon = trailingIcon,
        modifier = Modifier.height(40.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    )
}

/**
 * Shared modal frame matching the modernized "New playlist" dialog style:
 * glass surface, 20.dp rounded corners, and a click-to-dismiss scrim.
 */
@Composable
internal fun DesktopDialogFrame(
    onDismiss: () -> Unit,
    maxWidth: Dp = 480.dp,
    dismissOnEscape: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    DesktopRegisterFlyout(
        layer = DesktopFlyoutLayer.DIALOG,
        onDismiss = if (dismissOnEscape) onDismiss else null,
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .focusProperties { canFocus = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        val shape = RoundedCornerShape(20.dp)
        Box(
            Modifier
                .padding(16.dp)
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .clip(shape)
                .desktopBarGlass(shape),
        ) {
            // Swallows clicks that land on the panel's own background so they do not reach the
            // scrim behind it and dismiss what was just clicked.
            Box(
                Modifier
                    .matchParentSize()
                    .focusProperties { canFocus = false }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            )
            Column(Modifier.padding(bottom = 8.dp), content = content)
        }
    }
}

@Composable
internal fun DesktopDialogHeading(title: String, subtitle: String = "") {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = DesktopForeground,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp),
    )
    if (subtitle.isNotBlank()) {
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = DesktopSecondary,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp),
        )
    }
}

@Composable
internal fun DesktopDialogSectionHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = DesktopSecondary,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
internal fun DesktopDialogError(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = DesktopAccent,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp),
    )
}

/** Filled rounded input field matching the "New playlist" dialog. */
@Composable
internal fun DesktopDialogField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 22.dp,
    enabled: Boolean = true,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .clip(RoundedCornerShape(11.dp))
            .background(DesktopForeground.copy(alpha = if (enabled) 0.08f else 0.04f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = DesktopSecondary)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = DesktopForeground),
                cursorBrush = SolidColor(DesktopAccent),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty() && enabled) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    DesktopIcons.Actions.Close,
                    DesktopStrings["clear", "Clear"],
                    tint = DesktopSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
internal fun DesktopDialogButtons(
    busy: Boolean = false,
    confirmLabel: String,
    confirmEnabled: Boolean = true,
    dismissLabel: String = DesktopStrings["cancel", "Cancel"],
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(color = DesktopAccent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(12.dp))
        }
        TextButton(onClick = onDismiss, enabled = !busy) { Text(dismissLabel, color = DesktopSecondary) }
        TextButton(onClick = onConfirm, enabled = confirmEnabled && !busy) {
            Text(confirmLabel, color = if (confirmEnabled && !busy) DesktopAccent else DesktopSecondary)
        }
    }
}
