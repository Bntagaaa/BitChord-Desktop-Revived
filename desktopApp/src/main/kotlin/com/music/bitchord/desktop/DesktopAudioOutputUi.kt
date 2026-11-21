package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.screens.desktopTopScrollFade

/**
 * The real desktop Audio Output entry point (floating player, top player, and Settings).
 *
 * Previously this called DesktopDialogPanel and opened a wide, dimmed modal even
 * though the shared fullscreen player's output picker had already been redesigned.
 * Use the same in-window MenuPortal and frosted surface as the account/song menus
 * instead. The portal anchors to the clicked output control, flips above the
 * bottom player, clamps at window edges, and dismisses on outside click/Escape.
 */
@Composable
internal fun DesktopAudioOutputDialog(onDismiss: () -> Unit) {
    val selected by DesktopAudioDevices.selected.collectAsState()
    val changes by DesktopAudioDevices.changes.collectAsState()
    val devices = remember(changes) { DesktopAudioDevices.available() }
    var showAdvanced by remember { mutableStateOf(false) }
    val advanced = devices.filter(DesktopAudioDevices::isAdvancedDevice)
    val visible = devices.filter { !DesktopAudioDevices.isAdvancedDevice(it) || showAdvanced || it.id == selected }
    val listState = rememberLazyListState()

    // DesktopMenuSurface uses desktopMenuGlass: the same 35 dp backdrop blur,
    // tint, edge and Reduce Blur / Menus & popups settings as the account picker.
    // No full-screen modal or dark scrim is rendered over the library/player.
    DesktopMenuSurface(onDismiss = onDismiss) {
        Text(
            DesktopStrings["audio_output", "Audio output"],
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = DesktopForeground,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 7.dp),
        )

        // Only the devices scroll. The title and advanced toggle stay visible.
        // 232 dp fits about four compact rows, even on small windows.
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 232.dp).desktopTopScrollFade(listState),
            contentPadding = PaddingValues(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "system-default") {
                AudioOutputMenuRow(
                    name = DesktopStrings["d_system_default", "System default"],
                    description = if (selected == DesktopAudioDevices.SYSTEM_DEFAULT) "Playing here" else "",
                    icon = Icons.Rounded.Speaker,
                    chosen = selected == DesktopAudioDevices.SYSTEM_DEFAULT,
                ) {
                    DesktopAudioDevices.select(DesktopAudioDevices.SYSTEM_DEFAULT)
                    onDismiss()
                }
            }
            items(visible, key = { "device:" + it.id }) { device ->
                AudioOutputMenuRow(
                    name = device.name,
                    description = device.description,
                    icon = audioOutputIcon(device.name),
                    chosen = selected == device.id,
                ) {
                    DesktopAudioDevices.select(device.id)
                    onDismiss()
                }
            }
        }

        if (advanced.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                color = DesktopCardEdge,
                thickness = 0.5.dp,
            )
            Text(
                if (showAdvanced) "Hide advanced audio devices"
                else "Show advanced audio devices (${advanced.size})",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showAdvanced = !showAdvanced }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(5.dp))
    }
}

@Composable
private fun AudioOutputMenuRow(
    name: String,
    description: String,
    icon: ImageVector,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (chosen) DesktopForeground.copy(alpha = 0.085f) else Color.Transparent)
            .desktopRowClickable(onClick = onClick)
            .heightIn(min = 46.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(DesktopForeground.copy(alpha = if (chosen) 0.15f else 0.075f)),
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
                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
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
        if (chosen) {
            Spacer(Modifier.width(8.dp))
            Icon(BitChordIcons.Check, null, tint = DesktopForeground, modifier = Modifier.size(18.dp))
        }
    }
}

private fun audioOutputIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        "bluetooth" in n || "buds" in n || "airpods" in n -> Icons.Rounded.Bluetooth
        "headphone" in n || "headset" in n || "earpods" in n -> Icons.Rounded.Headphones
        "usb" in n || "dac" in n -> Icons.Rounded.Usb
        "hdmi" in n || "display" in n -> Icons.Rounded.Tv
        else -> Icons.Rounded.Speaker
    }
}
