package com.music.bitchord.reworked

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.my.kizzy.rpc.KizzyRPC
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fresh desktop presentation of mobile DiscordScreen: opt-in account, live preview and the
 * same title/status/activity/button controls. Authentication uses the mobile login boundary. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MobileDiscordSettings(session: MobileSession) {
    val settings by MobileDiscordSettingsStore.values.collectAsState()
    val playback by session.player.state.collectAsState()
    val status by session.integrations.discord.status.collectAsState()
    var showConsent by remember { mutableStateOf(false) }
    var manualToken by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var accountMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.token) {
        accountMessage = null
        val token = settings.token
        if (token.isNotBlank() && settings.username.isBlank()) {
            val result = withContext(Dispatchers.IO) {
                KizzyRPC.getUserInfo(token, MobileDiscordFingerprint.userAgent, MobileDiscordFingerprint.properties)
            }
            result.onSuccess { account ->
                MobileDiscordSettingsStore.update { if (it.token == token) it.copy(username = account.username,
                    displayName = account.name, avatar = account.avatar) else it }
            }.onFailure { accountMessage = "The Discord account could not be verified. Check the token and connection." }
        }
    }

    SettingsSection("Discord") {
        Text("Show the track you are playing on your Discord profile.")
        Text("This optional mobile feature uses your full Discord account token. Account automation conflicts with Discord’s terms. The token is saved in your local user profile and sent only to Discord; this desktop port does not use a system credential vault.",
            style = MaterialTheme.typography.bodySmall)
        if (settings.token.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (settings.avatar != null) AsyncImage(settings.avatar, "Discord avatar", Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(settings.displayName.ifBlank { "Discord account saved" }, style = MaterialTheme.typography.titleMedium)
                    if (settings.username.isNotBlank()) Text("@${settings.username}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = MobileDiscordSettingsStore::disconnect) { Text("Disconnect") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { showConsent = true }) { Text(if (settings.token.isBlank()) "Connect Discord" else "Change account") }
            TextButton(onClick = { showManual = !showManual }) { Text("Use a token") }
        }
        if (showManual) {
            OutlinedTextField(manualToken, { manualToken = it }, label = { Text("Discord account token") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                runCatching { MobileDiscordSettingsStore.connect(manualToken) }
                    .onSuccess { manualToken = ""; showManual = false; accountMessage = "Account saved. Turn on presence when you are ready." }
                    .onFailure { accountMessage = it.message }
            }, enabled = manualToken.isNotBlank()) { Text("Save token") }
        }
        if (settings.token.isNotBlank()) {
            ReworkedToggle("Share playback on Discord", settings.enabled) { value -> MobileDiscordSettingsStore.update { it.copy(enabled = value) } }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        accountMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        ReworkedToggle("Emphasize the song title", settings.useDetails) { value -> MobileDiscordSettingsStore.update { it.copy(useDetails = value) } }
        ReworkedToggle("Show measured lossless audio quality", settings.showAudioQuality) { value -> MobileDiscordSettingsStore.update { it.copy(showAudioQuality = value) } }
        ReworkedToggle("Advanced presence controls", settings.advanced) { value -> MobileDiscordSettingsStore.update { it.copy(advanced = value) } }
        if (settings.advanced) {
            Text("Status", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("online" to "Online", "idle" to "Idle", "dnd" to "Do not disturb").forEach { (value, label) ->
                    FilterChip(settings.status == value, { MobileDiscordSettingsStore.update { it.copy(status = value) } }, label = { Text(label) })
                }
            }
            Text("Activity", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("listening", "playing", "watching", "competing").forEach { value ->
                    FilterChip(settings.activityType == value, { MobileDiscordSettingsStore.update { it.copy(activityType = value) } },
                        label = { Text(value.replaceFirstChar(Char::uppercaseChar)) })
                }
            }
            OutlinedTextField(settings.activityName, { value -> MobileDiscordSettingsStore.update { it.copy(activityName = value.take(128)) } },
                label = { Text("Activity name") }, placeholder = { Text("BitChord Desktop Reworked") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Button labels support {song_name}, {artist_name}, and {album_name}.", style = MaterialTheme.typography.bodySmall)
            ReworkedToggle("YouTube Music button", settings.button1Visible) { value -> MobileDiscordSettingsStore.update { it.copy(button1Visible = value) } }
            OutlinedTextField(settings.button1Text, { value -> MobileDiscordSettingsStore.update { it.copy(button1Text = value.take(128)) } },
                label = { Text("Listen button label") }, placeholder = { Text(DISCORD_BUTTON_LISTEN) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ReworkedToggle("BitChord project button", settings.button2Visible) { value -> MobileDiscordSettingsStore.update { it.copy(button2Visible = value) } }
            OutlinedTextField(settings.button2Text, { value -> MobileDiscordSettingsStore.update { it.copy(button2Text = value.take(128)) } },
                label = { Text("Project button label") }, placeholder = { Text(DISCORD_BUTTON_PROJECT) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        val preview = discordPresence(playback.copy(isPlaying = playback.song != null, isLoading = false), settings, System.currentTimeMillis())
        if (preview != null) {
            HorizontalDivider()
            Text("Presence preview", style = MaterialTheme.typography.titleSmall)
            Text("${when (settings.activityType) { "playing" -> "Playing"; "watching" -> "Watching"; "competing" -> "Competing in"; else -> "Listening to" }} ${preview.name}")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(preview.artwork, "Current track artwork", Modifier.size(84.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(preview.details, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = if (settings.useDetails) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                    Text(preview.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = if (settings.useDetails) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium)
                    preview.quality?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (playback.durationMs > 0) LinearProgressIndicator(progress = { (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                preview.buttons.forEach { (label, url) -> OutlinedButton(onClick = { openExternal(url) }) { Text(label) } }
            }
            if (!playback.isPlaying) Text("The live card is hidden while playback is paused.", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showConsent) AlertDialog(onDismissRequest = { showConsent = false }, title = { Text("Connect your Discord account?") },
        text = { Text("Discord will open its own sign-in page. This feature receives a token with full account access and uses the same account protocol as mobile BitChord. Continue only if you accept Discord’s account-automation restriction and local token storage. Presence stays off until you enable it.") },
        confirmButton = { TextButton(onClick = { showConsent = false; DesktopBrowserAuth.discord(MobileDiscordSettingsStore::connect) }) { Text("Open Discord sign in") } },
        dismissButton = { TextButton(onClick = { showConsent = false }) { Text("Cancel") } })
}
