package com.music.bitchord.desktop

import com.music.bitchord.desktop.icons.DesktopIcons

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.ui.icons.BitChordIcons
import java.util.Locale

private val SettingsShape = RoundedCornerShape(14.dp)
private val RowInset = 16.dp
private val GlyphSize = 22.dp
private val GlyphGap = 14.dp

internal fun Modifier.settingsCard(): Modifier =
    clip(SettingsShape).background(DesktopCardInsetFill)

internal val LocalDesktopSettingsBack = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun DesktopSettingsPageTitle(title: String) {
    val onBack = LocalDesktopSettingsBack.current
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    DesktopIcons.Navigation.ArrowBack,
                    contentDescription = DesktopStrings["back", "Back"],
                    tint = DesktopForeground,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            title,
            color = DesktopForeground,
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 34.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.W800,
                letterSpacing = if (DesktopFontSettings.mode == DesktopFontMode.DEFAULT) (-0.5).sp else (-0.8).sp,
            ),
            modifier = Modifier.padding(start = 4.dp).semantics { heading() },
        )
    }
}

@Composable
internal fun DesktopSettingsGroup(
    title: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title.uppercase(Locale.getDefault()),
                color = DesktopSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).semantics { heading() },
            )
        }
        Column(Modifier.fillMaxWidth().settingsCard()) { content() }
        if (footer != null) {
            Text(
                footer,
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
            )
        }
    }
}

@Composable
internal fun DesktopSettingsDivider(insetForIcon: Boolean = false) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (insetForIcon) RowInset + GlyphSize + GlyphGap else RowInset),
        thickness = 0.5.dp,
        color = DesktopCardEdge,
    )
}

@Composable
private fun Modifier.settingsInteraction(source: MutableInteractionSource, enabled: Boolean): Modifier {
    if (!enabled) return this
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    return desktopHoverFill(alpha = 0.06f)
        .background(if (pressed) DesktopForeground.copy(alpha = 0.07f) else Color.Transparent)
        .then(if (focused) Modifier.border(1.dp, DesktopAccent.copy(alpha = 0.8f), RoundedCornerShape(7.dp)) else Modifier)
        .pointerHoverIcon(PointerIcon.Hand)
}

@Composable
internal fun DesktopSettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    enabled: Boolean = true,
    checked: Boolean? = null,
    selected: Boolean? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val input = when {
        checked != null && onClick != null -> Modifier.toggleable(
            value = checked, enabled = enabled, role = Role.Switch,
            interactionSource = interaction, indication = null, onValueChange = { onClick() },
        )
        selected != null && onClick != null -> Modifier.selectable(
            selected = selected, enabled = enabled, role = Role.RadioButton,
            interactionSource = interaction, indication = null, onClick = onClick,
        )
        onClick != null -> Modifier.clickable(
            enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick,
        )
        else -> Modifier
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .settingsInteraction(interaction, enabled && onClick != null)
            .then(input)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 52.dp)
            .padding(horizontal = RowInset, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = DesktopForeground, modifier = Modifier.size(GlyphSize))
            Spacer(Modifier.width(GlyphGap))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = DesktopForeground, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        when {
            trailing != null -> {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
            checked != null -> {
                Spacer(Modifier.width(12.dp))
                DesktopSettingsSwitch(checked, interaction = interaction)
            }
            selected != null -> if (selected) {
                Spacer(Modifier.width(12.dp))
                Icon(BitChordIcons.Check, null, tint = DesktopAccent, modifier = Modifier.size(18.dp))
            }
            onClick != null -> {
                Spacer(Modifier.width(12.dp))
                if (value != null) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DesktopSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Icon(BitChordIcons.ChevronRight, null, tint = DesktopSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Desktop adaptation of v1.10 IosSwitch. A row can supply its interaction and own the one toggle action. */
@Composable
internal fun DesktopSettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    label: String? = null,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val reduceMotion by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val pressed by interaction.collectIsPressedAsState()
    val position by animateFloatAsState(
        if (checked) 1f else 0f,
        if (reduceMotion) snap() else spring(dampingRatio = 0.78f, stiffness = 700f),
        label = "Settings switch position",
    )
    val stretch by animateFloatAsState(
        if (pressed && !reduceMotion) 1.18f else 1f,
        spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "Settings switch thumb",
    )
    val dark = DesktopTheme.isDark
    val track by animateColorAsState(
        if (checked) Color(if (dark) 0xFF30D158 else 0xFF34C759)
        else Color(0xFF8E8E93).copy(alpha = if (dark) 0.36f else 0.22f),
        animationSpec = if (reduceMotion) snap() else spring(),
        label = "Settings switch track",
    )
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val input = if (onCheckedChange == null) Modifier else Modifier
        .settingsInteraction(interaction, enabled)
        .toggleable(
            value = checked, enabled = enabled, role = Role.Switch,
            interactionSource = interaction, indication = null, onValueChange = onCheckedChange,
        )
        .semantics { if (label != null) contentDescription = label }
    Box(
        Modifier.then(input).alpha(if (enabled) 1f else 0.45f).size(64.dp, 28.dp)
            .clip(CircleShape).background(track),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.graphicsLayer {
                translationX = (2.dp.toPx() + 20.dp.toPx() * position) * if (ltr) 1f else -1f
                transformOrigin = TransformOrigin(if (checked == ltr) 1f else 0f, 0.5f)
                scaleX = stretch
            }.size(40.dp, 24.dp).background(Color.White, CircleShape),
        )
    }
}

/** Equal segments can wrap their labels in a narrow window without requiring horizontal scroll. */
@Composable
internal fun <T> DesktopSettingsSegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val reduceMotion by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val position by animateFloatAsState(
        options.indexOf(selected).coerceAtLeast(0).toFloat(),
        if (reduceMotion) snap() else spring(dampingRatio = 0.8f),
        label = "Settings selected segment",
    )
    val dark = DesktopTheme.isDark
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(shape)
            .background(Color(0xFF767680).copy(alpha = if (dark) 0.32f else 0.12f))
            .padding(3.dp).selectableGroup(),
    ) {
        val cellWidth = maxWidth / options.size
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.offset(x = cellWidth * position).width(cellWidth)
                    .fillMaxHeight()
                    .background(if (dark) Color(0xFF636366) else Color.White, RoundedCornerShape(9.dp)),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            options.forEach { option ->
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).settingsInteraction(source, true)
                        .selectable(
                            selected = option == selected, role = Role.RadioButton,
                            interactionSource = source, indication = null, onClick = { if (option != selected) onSelect(option) },
                        )
                        .heightIn(min = 36.dp).padding(horizontal = 4.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        color = DesktopForeground,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}


@Composable
internal fun DesktopSettingsControl(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = RowInset, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = DesktopForeground, modifier = Modifier.size(GlyphSize))
                Spacer(Modifier.width(GlyphGap))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = DesktopForeground, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        content()
    }
}

internal class DesktopSettingsRows(private val query: DesktopSettingsQuery, private val group: String) {
    internal class Entry(val id: String, val icon: Boolean, val content: @Composable () -> Unit)
    internal val entries = mutableListOf<Entry>()

    fun row(title: String, vararg detail: String?, icon: Boolean = false, id: String = title, content: @Composable () -> Unit) {
        if (query.matches(group, title, *detail)) entries += Entry(id, icon, content)
    }

    fun toggle(
        title: String,
        subtitle: String?,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        icon: ImageVector? = null,
        enabled: Boolean = true,
    ) = row(title, subtitle, icon = icon != null) {
        DesktopSettingsRow(title, subtitle, icon = icon, enabled = enabled, checked = checked, onClick = { onCheckedChange(!checked) })
    }

    fun navigationRow(title: String, subtitle: String?, onClick: () -> Unit) =
        row(title, subtitle) { DesktopSettingsRow(title, subtitle, onClick = onClick) }

    fun iconRow(icon: ImageVector, title: String, subtitle: String?, onClick: (() -> Unit)? = null) =
        row(title, subtitle, icon = true) { DesktopSettingsRow(title, subtitle, icon = icon, onClick = onClick) }

    fun choice(title: String, selected: String, keywords: String, icon: ImageVector? = null, subtitle: String? = null, onClick: () -> Unit) =
        row(title, selected, subtitle, keywords, icon = icon != null) {
            DesktopSettingsRow(title, subtitle = subtitle, icon = icon, value = selected, onClick = onClick)
        }
}

internal class DesktopSettingsQuery(query: String) {
    private val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    fun matches(vararg fields: String?): Boolean =
        terms.all { term -> fields.any { field -> field?.contains(term, ignoreCase = true) == true } }
}

@Composable
internal fun DesktopSettingsRows.Content(title: String? = null) {
    if (entries.isEmpty()) return
    DesktopSettingsGroup(title) {
        entries.forEachIndexed { index, entry ->
            key(entry.id) {
                if (index > 0) DesktopSettingsDivider(insetForIcon = entry.icon)
                entry.content()
            }
        }
    }
}

@Composable
internal fun DesktopSettingsPageTransition(content: @Composable () -> Unit) {
    val reduceMotion by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val opacity = remember { Animatable(if (reduceMotion) 1f else 0.86f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) opacity.snapTo(1f) else opacity.animateTo(1f, tween(140))
    }
    Box(Modifier.graphicsLayer { alpha = opacity.value }) { content() }
}
