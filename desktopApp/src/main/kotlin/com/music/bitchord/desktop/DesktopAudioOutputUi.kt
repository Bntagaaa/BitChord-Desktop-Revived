package com.music.bitchord.desktop

import com.music.bitchord.desktop.icons.DesktopIcons

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.audio_output_playing
import com.music.bitchord.sharedui.resources.audio_pipeline
import com.music.bitchord.sharedui.resources.cast
import com.music.bitchord.ui.player.ThinSlider
import org.jetbrains.compose.resources.stringResource

internal val DesktopAudioOutputButtonSize = 32.dp

@Composable
internal fun DesktopAudioOutputButton(
    onOpen: (Rect) -> Unit,
    iconSize: Dp = 18.dp,
    tint: Color = DesktopSecondary,
    interactive: Boolean = true,
) {
    val glyph: @Composable () -> Unit = {
        Icon(
            DesktopIcons.Audio.Headphones,
            if (interactive) DesktopStrings["audio_output", "Audio output"] else null,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
    if (interactive) {
        var anchor by remember { mutableStateOf<Rect?>(null) }
        DesktopToolbarButton(
            onClick = { anchor?.let(onOpen) },
            size = DesktopAudioOutputButtonSize,
            modifier = Modifier.onGloballyPositioned { anchor = it.boundsInRoot() },
            content = glyph,
        )
    } else {
        Box(Modifier.size(DesktopAudioOutputButtonSize), contentAlignment = Alignment.Center) { glyph() }
    }
}

@Composable
internal fun DesktopAudioOutputMenu(
    onDismiss: () -> Unit,
    anchor: Rect? = null,
    onOpenPipeline: (() -> Unit)? = null,
    onOpenCast: (() -> Unit)? = null,
) {
    if (LocalDesktopMenuPortal.current == null) return

    val cast by DesktopPlayerHost.castState.collectAsState()
    DesktopMenuSurface(onDismiss = onDismiss, anchor = anchor, maxHeight = 390.dp) {
        Text(
            DesktopStrings["audio_output", "Audio output"],
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = DesktopForeground,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 7.dp),
        )
        AudioOutputDeviceRows(onDismiss)
        if (onOpenCast != null && cast.supported) {
            AudioOutputMenuRow(
                name = cast.connectedName ?: stringResource(Res.string.cast),
                icon = if (cast.connectedName != null) DesktopIcons.Audio.CastConnected else DesktopIcons.Audio.Cast,
                arrow = true,
            ) {
                onDismiss()
                onOpenCast()
            }
        }
        if (onOpenPipeline != null) {
            AudioOutputMenuDivider()
            AudioOutputVolume()
            AudioOutputMenuRow(name = stringResource(Res.string.audio_pipeline), icon = DesktopIcons.Audio.GraphicEq, arrow = true) {
                onDismiss()
                onOpenPipeline()
            }
        }
    }
}

@Composable
internal fun DesktopAudioOutputPage(onBack: () -> Unit) {
    val scroll = rememberScrollState()
    DesktopSettingsPageFrame {
        DesktopSettingsPageTitle(DesktopSettingsPage.AUDIO_OUTPUT.title)
        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(bottom = 28.dp),
        ) {
            DesktopSettingsGroup {
                AudioOutputDeviceRows(onBack, page = true)
            }
        }
    }
}

@Composable
private fun AudioOutputDeviceRows(onDismiss: () -> Unit, page: Boolean = false) {
    val selected by DesktopAudioDevices.selected.collectAsState()
    val changes by DesktopAudioDevices.changes.collectAsState()
    val devices = remember(changes) { DesktopAudioDevices.available() }
    var showAdvanced by remember { mutableStateOf(false) }
    val advanced = devices.count(DesktopAudioDevices::isAdvancedDevice)

    AudioOutputDeviceRow(
        page, false,
        DesktopStrings["d_system_default", "System default"],
        if (selected == DesktopAudioDevices.SYSTEM_DEFAULT) stringResource(Res.string.audio_output_playing) else "",
        DesktopIcons.Audio.Speaker,
        selected == DesktopAudioDevices.SYSTEM_DEFAULT,
    ) {
        DesktopAudioDevices.select(DesktopAudioDevices.SYSTEM_DEFAULT)
        onDismiss()
    }
    devices.forEach { device ->
        if (showAdvanced || device.id == selected || !DesktopAudioDevices.isAdvancedDevice(device)) {
            key(device.id) {
                AudioOutputDeviceRow(
                    page, true,
                    name = device.name,
                    description = if (device.id == selected) stringResource(Res.string.audio_output_playing) else device.description,
                    icon = audioOutputIcon(device.name),
                    chosen = device.id == selected,
                ) {
                    DesktopAudioDevices.select(device.id)
                    onDismiss()
                }
            }
        }
    }
    if (advanced > 0) {
        if (!page) AudioOutputMenuDivider()
        val label = if (showAdvanced) "Hide advanced audio devices" else "Show advanced audio devices ($advanced)"
        val icon = if (showAdvanced) DesktopIcons.Window.ExpandLess else DesktopIcons.Window.ExpandMore
        if (page) {
            DesktopSettingsDivider(insetForIcon = true)
            DesktopSettingsRow(label, icon = icon, onClick = { showAdvanced = !showAdvanced })
        } else AudioOutputMenuRow(name = label, icon = icon) { showAdvanced = !showAdvanced }
    }
}

@Composable
private fun AudioOutputDeviceRow(
    page: Boolean,
    divider: Boolean,
    name: String,
    description: String,
    icon: ImageVector,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    if (page) {
        if (divider) DesktopSettingsDivider(insetForIcon = true)
        DesktopSettingsRow(name, description, icon, selected = chosen, onClick = onClick)
    } else {
        AudioOutputMenuRow(name, description = description, icon = icon, chosen = chosen, onClick = onClick)
    }
}

@Composable
private fun AudioOutputMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        color = DesktopCardEdge,
        thickness = 0.5.dp,
    )
}

@Composable
private fun AudioOutputVolume() {
    val volume = DesktopPlayerHost.volume
    val systemLevel by volume.level.collectAsState()
    var level by remember(volume) { mutableFloatStateOf(systemLevel) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(systemLevel, dragging) {
        if (!dragging) level = systemLevel
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (level > 0f) DesktopIcons.Audio.VolumeUp else DesktopIcons.Audio.VolumeOff,
            contentDescription = null,
            tint = DesktopSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        ThinSlider(
            value = level,
            onValueChange = {
                dragging = true
                level = it
                volume.set(it)
            },
            onValueChangeFinished = { dragging = false },
            idleHeight = 4.dp,
            activeHeight = 7.dp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AudioOutputMenuRow(
    name: String,
    description: String = "",
    icon: ImageVector,
    chosen: Boolean? = null,
    arrow: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
    val selected = chosen == true
    val fill by animateColorAsState(
        targetValue = when {
            hovered && selected -> DesktopForeground.copy(alpha = 0.15f)
            hovered -> DesktopForeground.copy(alpha = 0.09f)
            selected -> DesktopForeground.copy(alpha = 0.085f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = if (reduceAnimation) 0 else 170),
        label = "desktop-audio-output-hover",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .hoverable(interaction)
            .semantics { if (chosen != null) this.selected = chosen }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .heightIn(min = 46.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(DesktopForeground.copy(alpha = if (selected) 0.15f else 0.075f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = DesktopForeground.copy(alpha = 0.85f), modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = DesktopForeground,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (description.isNotBlank()) {
                Text(
                    description,
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(BitChordIcons.Check, null, tint = DesktopForeground, modifier = Modifier.size(18.dp))
        } else if (arrow) {
            Icon(DesktopIcons.Navigation.ChevronRight, null, tint = DesktopSecondary, modifier = Modifier.size(17.dp))
        }
    }
}

private fun audioOutputIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        "bluetooth" in n || "buds" in n || "airpods" in n -> DesktopIcons.Audio.Bluetooth
        "headphone" in n || "headset" in n || "earpods" in n -> DesktopIcons.Audio.Headphones
        "usb" in n || "dac" in n -> DesktopIcons.Audio.Usb
        "hdmi" in n || "display" in n -> DesktopIcons.Audio.Tv
        else -> DesktopIcons.Audio.Speaker
    }
}
