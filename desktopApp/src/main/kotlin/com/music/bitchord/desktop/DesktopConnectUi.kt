package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.desktop.icons.DesktopIcons
import com.music.bitchord.ui.screens.desktopTopScrollFade

/**
 * Settings page & pairing screen for BitChord LAN Connect.
 *
 * Lets Android BitChord discover this PC over Wi-Fi (mDNS) or pair via PIN / manual IP
 * (including Tailscale) and control playback directly on this computer.
 */
@Composable
internal fun DesktopConnectDialog(onDismiss: () -> Unit) {
    val enabled by DesktopConnectHost.enabled.collectAsState()
    val state by DesktopConnectHost.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    var nameDraft by remember(state.deviceName) { mutableStateOf(state.deviceName) }
    var pinDraft by remember(state.pin) { mutableStateOf(state.pin) }
    var pinFeedback by remember { mutableStateOf<String?>(null) }
    var copiedNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(enabled) {
        if (enabled) DesktopConnectHost.refreshNetwork()
    }

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 680) {
        Column(
            desktopPanelBody(cardMax = 680.dp)
                .desktopTopScrollFade(scrollState)
                .verticalScroll(scrollState)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (LocalDesktopPanelIsPage.current) {
                DesktopSettingsPageTitle(DesktopSettingsPage.CONNECT.title)
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = panelInset(20.dp), end = panelInset(12.dp), top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Connect (LAN Remote)",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = DesktopForeground,
                        )
                        Text(
                            "Control playback on this PC from BitChord Android on the same network",
                            style = MaterialTheme.typography.bodySmall,
                            color = DesktopSecondary,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(DesktopIcons.Actions.Close, contentDescription = "Close", tint = DesktopSecondary)
                    }
                }
            }

            // Enable toggle & live status
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = panelInset(20.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(DesktopCardFill)
                    .border(1.dp, DesktopCardEdge, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.connectedClients.isNotEmpty()) DesktopAccent.copy(alpha = 0.18f)
                                else DesktopForeground.copy(alpha = 0.08f),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            DesktopIcons.Audio.Phone,
                            contentDescription = null,
                            tint = if (state.connectedClients.isNotEmpty()) DesktopAccent else DesktopSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Allow remote control on local network",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = DesktopForeground,
                        )
                        val statusText = when {
                            !enabled -> "Disabled — phone cannot discover or control this PC"
                            state.error != null -> "Error: ${state.error}"
                            !state.running -> "Starting local receiver…"
                            state.connectedClients.isNotEmpty() -> {
                                val names = state.connectedClients.joinToString(", ") { it.clientName }
                                "Connected: $names"
                            }
                            state.primaryAddress != null -> {
                                val mdns = if (state.mdnsActive) "mDNS active" else "manual IP ready"
                                "Listening on ${state.primaryAddress}:${state.port} · $mdns"
                            }
                            else -> "Listening on port ${state.port} (no LAN IPv4 detected)"
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.error != null) Color(0xFFFF6B63) else DesktopSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = DesktopConnectHost::setEnabled,
                    )
                }

                // Editable device name
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it.take(64) },
                        label = { Text("PC name shown on Android") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { DesktopConnectHost.setDeviceName(nameDraft) },
                        enabled = nameDraft.trim().isNotEmpty() && nameDraft.trim() != state.deviceName,
                    ) {
                        Text("Save")
                    }
                }
            }

            if (enabled && state.running) {
                // Pairing card (PIN + QR + Manual address)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = panelInset(20.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(DesktopCardFill)
                        .border(1.dp, DesktopCardEdge, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Pair a new Android device",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = DesktopForeground,
                            )
                            Text(
                                "Open BitChord Android → Settings → Connect (LAN Remote) or Player → Audio Output → BitChord PC, then enter this PIN.",
                                style = MaterialTheme.typography.bodySmall,
                                color = DesktopSecondary,
                            )
                        }
                        IconButton(onClick = { DesktopConnectHost.refreshNetwork() }) {
                            Icon(DesktopIcons.Actions.Refresh, contentDescription = "Refresh network", tint = DesktopSecondary)
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "PAIRING PIN (SAVED ACROSS RESTARTS)",
                                style = MaterialTheme.typography.labelSmall,
                                color = DesktopSecondary,
                                letterSpacing = 1.sp,
                            )
                            Text(
                                text = if (state.pin.length == 6) state.pin.chunked(3).joinToString(" ") else state.pin,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = DesktopForeground,
                                letterSpacing = 3.sp,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = pinDraft,
                                    onValueChange = {
                                        pinDraft = it.filter(Char::isDigit).take(DesktopConnectServer.MAX_PIN_LENGTH)
                                        pinFeedback = null
                                    },
                                    label = { Text("Custom PIN (4–6 digits)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedButton(
                                    onClick = {
                                        DesktopConnectHost.setCustomPin(pinDraft)
                                            .onSuccess { pinFeedback = "PIN saved" }
                                            .onFailure { pinFeedback = it.message }
                                    },
                                    enabled = pinDraft.length >= DesktopConnectServer.MIN_PIN_LENGTH && pinDraft != state.pin,
                                ) {
                                    Text("Save")
                                }
                                OutlinedButton(
                                    onClick = {
                                        DesktopConnectHost.rotatePin()
                                        pinFeedback = "Generated new saved PIN"
                                    },
                                ) {
                                    Text("Random")
                                }
                            }
                            pinFeedback?.let { msg ->
                                Text(msg, style = MaterialTheme.typography.labelSmall, color = DesktopAccent)
                            }

                            if (state.addresses.isNotEmpty()) {
                                Text(
                                    "Manual address (LAN / Tailscale):",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = DesktopSecondary,
                                )
                                state.addresses.take(4).forEach { ip ->
                                    val endpoint = "$ip:${state.port}"
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            endpoint,
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = DesktopForeground,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(endpoint))
                                                copiedNotice = "Copied $endpoint"
                                            },
                                        ) {
                                            Text("Copy", color = DesktopAccent)
                                        }
                                    }
                                }
                            }

                            state.pairLink?.let { link ->
                                Button(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(link))
                                        copiedNotice = "Copied pairing link"
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DesktopAccent),
                                ) {
                                    Icon(DesktopIcons.Actions.Copy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Copy pairing link")
                                }
                            }

                            copiedNotice?.let { notice ->
                                Text(notice, style = MaterialTheme.typography.labelSmall, color = DesktopAccent)
                            }
                        }

                        state.pairLink?.let { link ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                DesktopQrCode(content = link, size = 128.dp)
                                Text(
                                    "Pairing QR",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DesktopSecondary,
                                )
                            }
                        }
                    }
                }
            }

            // Paired devices list
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = panelInset(20.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(DesktopCardFill)
                    .border(1.dp, DesktopCardEdge, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Paired devices (${state.pairedClients.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = DesktopForeground,
                )
                if (state.pairedClients.isEmpty()) {
                    Text(
                        "No phones paired yet. Once paired, your phone reconnects automatically without entering the PIN again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DesktopSecondary,
                    )
                } else {
                    val onlineIds = state.connectedClients.mapTo(HashSet()) { it.clientId }
                    state.pairedClients.forEachIndexed { idx, client ->
                        if (idx > 0) DesktopCardRule()
                        val online = client.clientId in onlineIds
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (online) Color(0xFF34C759) else DesktopSecondary.copy(alpha = 0.35f)),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    client.clientName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = DesktopForeground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (online) "Connected now" else "Paired · offline",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (online) Color(0xFF34C759) else DesktopSecondary,
                                )
                            }
                            TextButton(onClick = { DesktopConnectHost.revokeClient(client.clientId) }) {
                                Text("Unpair", color = Color(0xFFFF6B63))
                            }
                        }
                    }
                }
            }
        }

        if (!LocalDesktopPanelIsPage.current) {
            DesktopCardRule()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = panelInset(16.dp), vertical = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(DesktopStrings["done", "Done"], color = DesktopAccent)
                }
            }
        }
    }
}
