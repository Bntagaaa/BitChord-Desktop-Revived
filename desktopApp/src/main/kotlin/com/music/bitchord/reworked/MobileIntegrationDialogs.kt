package com.music.bitchord.reworked

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.data.listentogether.*
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.stats.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** New desktop presentation of the original mobile room and membership state. */
@Composable
internal fun MobileListenTogetherDialog(integrations: MobileIntegrations, onDismiss: () -> Unit) {
    if (OfflineSmoke.active) {
        IntegrationDialog("Listen Together", onDismiss) {
            Text("Listen Together is unavailable during the offline app check. Restart normally to connect to a party.")
        }
        return
    }
    val party by integrations.party.collectAsState()
    val busy by integrations.busy.collectAsState()
    val message by integrations.message.collectAsState()
    val signedIn by integrations.session.signedIn.collectAsState()
    val connection by ListenTogether.serverConnectionState.collectAsState()
    val customServer by ListenTogether.customServerUrl.collectAsState()
    val activity by ListenTogether.activity.collectAsState()
    var server by remember(customServer) { mutableStateOf(customServer) }
    var nickname by remember { mutableStateOf(ListenTogether.nickname()) }
    var code by remember { mutableStateOf("") }
    var maxMembers by remember(party.maxMembers) { mutableIntStateOf(party.maxMembers) }
    val clipboard = LocalClipboardManager.current
    DisposableEffect(Unit) {
        ListenTogether.setScreenActive(true)
        onDispose { ListenTogether.setScreenActive(false) }
    }
    IntegrationDialog(if (party.inParty) "Listen Together · ${party.code}" else "Listen Together", onDismiss) {
        if (!party.inParty) {
            Text("Share a queue and listen in sync with up to five signed-in devices.")
            if (!signedIn) {
                Text("Connect YouTube Music to create or join a party.", color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { integrations.session.signIn() }) { Text("Sign in to YouTube Music") }
            }
            OutlinedTextField(nickname, { nickname = it.take(80) }, label = { Text("Nickname (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Party server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(server, { server = it }, singleLine = true,
                label = { Text("Custom server URL") }, placeholder = { Text("https://your-party-server.example") },
                modifier = Modifier.fillMaxWidth())
            Text(if (ListenTogether.defaultServer.isBlank()) "Enter your server address to use Listen Together."
                else "Leave blank to use the configured default server.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { integrations.savePartyServer(server) }, enabled = !busy) { Text("Save and check") }
                Text(when (val current = connection) {
                    ServerConnectionState.Checking -> "Checking…"
                    is ServerConnectionState.CustomOnline -> "Connected · ${current.latencyMs} ms"
                    is ServerConnectionState.DefaultOnline -> "Connected · ${current.latencyMs} ms"
                    is ServerConnectionState.CustomFallback -> "Default server connected · ${current.latencyMs} ms"
                    ServerConnectionState.Offline -> "Offline"
                }, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text("Create a party", style = MaterialTheme.typography.titleMedium)
            Text("Members · $maxMembers")
            Slider(maxMembers.toFloat(), { maxMembers = it.toInt() }, valueRange = 2f..5f, steps = 2)
            Button(onClick = { integrations.createParty(nickname, maxMembers) }, enabled = !busy && signedIn && ListenTogether.hasServer) { Text("Create party") }
            HorizontalDivider()
            Text("Join a party", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(code, { code = it }, label = { Text("Party code or invite link") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Button(onClick = { integrations.joinParty(code, nickname) }, enabled = !busy && signedIn && parsePartyCode(code) != null && ListenTogether.hasServer) { Text("Join party") }
        } else {
            Text(when (party.connection) {
                ListenTogether.Connection.LIVE -> if (party.clockSynced) "Connected · ${party.roundTripMs} ms" else "Synchronizing clocks…"
                ListenTogether.Connection.CONNECTING -> "Reconnecting…"
                ListenTogether.Connection.OFFLINE -> "Offline"
            }, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(party.code.orEmpty())) }) { Text("Copy code") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(JamInvite.url(party.code.orEmpty()))) }) { Text("Copy invite link") }
            }
            if (customServer.isNotBlank()) Text("Friends using a custom server need the same server address.", style = MaterialTheme.typography.bodySmall)
            party.playback.track?.let { track ->
                Text(track.title, style = MaterialTheme.typography.titleMedium)
                Text(track.artist, style = MaterialTheme.typography.bodyMedium)
                Text(if (party.playback.isPlaying) "Playing together" else "Paused", style = MaterialTheme.typography.bodySmall)
            }
            Text("Members · ${party.members.size}/${party.maxMembers}", style = MaterialTheme.typography.titleMedium)
            party.members.forEach { member ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(member.displayName.ifBlank { "Listener" })
                        Text(listOfNotNull(if (member.isHost) "Host" else null,
                            if (member.memberId == party.you?.memberId) "You" else null,
                            if (member.connected) "Connected" else "Reconnecting").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    if (party.you?.isHost == true && member.memberId != party.you?.memberId) {
                        TextButton(onClick = { ListenTogether.kick(member.memberId) }) { Text("Remove") }
                    }
                }
            }
            if (party.you?.isHost == true) {
                IntegrationToggle("Host controls playback", party.hostOnlyControl, onChange = ListenTogether::setHostOnlyControl)
                Text("Member limit · $maxMembers")
                Slider(maxMembers.toFloat(), { maxMembers = it.toInt() }, valueRange = 2f..5f, steps = 2,
                    onValueChangeFinished = { ListenTogether.setMaxMembers(maxMembers) })
            }
            if (!party.controlsLocked) IntegrationToggle("Autoplay recommendations", party.playback.autoplayEnabled, onChange = ListenTogether::setAutoplay)
            if (party.controlsLocked) Text("The host controls the shared queue. You can still pause this device.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = integrations::leaveParty, enabled = !busy) { Text("Leave party") }
            HorizontalDivider()
            OutlinedTextField(code, { code = it }, label = { Text("Another party code or invite") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { integrations.joinParty(code, nickname) }, enabled = !busy && parsePartyCode(code) != null) { Text("Switch party") }
            if (activity.isNotEmpty()) {
                Text("Recent session activity", style = MaterialTheme.typography.titleSmall)
                activity.take(8).forEach { entry ->
                    val action = when (entry.action) {
                        "play" -> "started playback"; "pause" -> "paused playback"; "seek" -> "changed position"
                        "next", "previous", "setTrack" -> "changed the song"
                        "setQueue", "queueAdd", "queueRemove", "queueMove", "queueClear" -> "changed the queue"
                        "join", "joined" -> "joined"; "leave", "left" -> "left"
                        else -> "updated the party"
                    }
                    Text("${entry.by} $action${entry.detail.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        (message ?: party.error)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun MobileScrobbleDialog(integrations: MobileIntegrations, onDismiss: () -> Unit) {
    if (OfflineSmoke.active) {
        IntegrationDialog("Scrobbling", onDismiss) {
            Text("Account connections and scrobbling are unavailable during the offline app check. Local Replay history remains available.")
        }
        return
    }
    val settings by MobileIntegrationSettings.values.collectAsState()
    val busy by integrations.busy.collectAsState()
    val message by integrations.message.collectAsState()
    val pending by integrations.authenticationPending.collectAsState()
    var endpoint by remember { mutableStateOf(settings.lastFmEndpoint) }
    var apiKey by remember { mutableStateOf(settings.lastFmApiKey) }
    var secret by remember { mutableStateOf(settings.lastFmSecret) }
    var token by remember { mutableStateOf(settings.listenBrainzToken) }
    fun saveLastFm(): Boolean = runCatching {
        MobileIntegrationSettings.update { it.copy(lastFmEndpoint = endpoint.trim(), lastFmApiKey = apiKey.trim(), lastFmSecret = secret.trim()) }
    }.fold({ true }, { integrations.message.value = "Enter a valid Last.fm-compatible endpoint."; false })

    IntegrationDialog("Scrobbling", onDismiss) {
        Text("Last.fm / Libre.fm", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(endpoint == LastFM.DEFAULT_API_ENDPOINT, onClick = { endpoint = LastFM.DEFAULT_API_ENDPOINT }, label = { Text("Last.fm") })
            FilterChip(endpoint == LastFM.LIBREFM_API_ENDPOINT, onClick = { endpoint = LastFM.LIBREFM_API_ENDPOINT }, label = { Text("Libre.fm") })
        }
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("API endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(secret, { secret = it }, label = { Text("API secret") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text("Use your application's API credentials, then authorize your listening account in the browser.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (saveLastFm()) integrations.message.value = "API configuration saved." }, enabled = !busy) { Text("Save configuration") }
            TextButton(onClick = { Desktop.getDesktop().browse(URI("https://www.last.fm/api/account/create")) }) { Text("Get API credentials") }
        }
        if (settings.lastFmAccount.isNotBlank()) {
            Text("Connected as ${settings.lastFmAccount}")
            IntegrationToggle("Send scrobbles", settings.lastFmEnabled) { value -> MobileIntegrationSettings.update { it.copy(lastFmEnabled = value) } }
            TextButton(onClick = integrations::disconnectLastFm) { Text("Disconnect account") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (saveLastFm()) integrations.startLastFmSignIn() }, enabled = !busy && apiKey.isNotBlank() && secret.isNotBlank()) { Text("Sign in in browser") }
                if (pending) OutlinedButton(onClick = integrations::finishLastFmSignIn, enabled = !busy) { Text("Complete sign in") }
            }
        }
        HorizontalDivider()
        Text("ListenBrainz", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(token, { token = it }, label = { Text("User token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { MobileIntegrationSettings.update { it.copy(listenBrainzToken = token.trim(), listenBrainzEnabled = token.isNotBlank()) }; integrations.message.value = "ListenBrainz token saved." }) { Text("Save token") }
            TextButton(onClick = { Desktop.getDesktop().browse(URI("https://listenbrainz.org/profile/")) }) { Text("Open profile and token") }
        }
        IntegrationToggle("Send listens", settings.listenBrainzEnabled, enabled = settings.listenBrainzToken.isNotBlank()) { value -> MobileIntegrationSettings.update { it.copy(listenBrainzEnabled = value) } }
        HorizontalDivider()
        IntegrationToggle("Send now playing", settings.nowPlaying) { value -> MobileIntegrationSettings.update { it.copy(nowPlaying = value) } }
        IntegrationToggle("Use the primary artist only", settings.primaryArtistOnly) { value -> MobileIntegrationSettings.update { it.copy(primaryArtistOnly = value) } }
        Text("Pauses and seeks do not add listening time. A track longer than 30 seconds is scrobbled after half its duration or three minutes of listening.", style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun MobileReplayDialog(integrations: MobileIntegrations, onDismiss: () -> Unit) {
    var period by remember { mutableStateOf(ReplayPeriod.THIS_MONTH) }
    var chart by remember { mutableStateOf("Songs") }
    val settings by MobileIntegrationSettings.values.collectAsState()
    val facts by ArtistFacts.revision.collectAsState()
    val message by integrations.message.collectAsState()
    val summary by produceState<ReplaySummary?>(null, period, facts) {
        while (isActive) { value = ListeningStats.summary(period); delay(10_000L) }
    }
    IntegrationDialog("Your Replay", onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReplayPeriod.entries.forEach { item ->
                FilterChip(period == item, onClick = { period = item }, label = { Text(item.chip) })
            }
        }
        val data = summary
        if (data == null) CircularProgressIndicator()
        else if (data.isEmpty) Text("Play some music and your listening will appear here. Your Replay stays on this device.")
        else {
            Text(data.label, style = MaterialTheme.typography.headlineSmall)
            Text("${data.minutes} minutes · ${data.totalPlays} plays · ${data.distinctSongs} songs", style = MaterialTheme.typography.titleMedium)
            Text("${data.distinctArtists} artists · ${data.distinctAlbums} albums", style = MaterialTheme.typography.bodyMedium)
            data.busiestDay?.let { Text("Busiest day: $it · ${data.busiestDayMs / 60_000} minutes", style = MaterialTheme.typography.bodySmall) }
            data.peakHour?.let { Text("Peak listening hour: ${it.toString().padStart(2, '0')}:00", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("Songs", "Artists", "Albums") + if (ArtistFacts.genresAvailable) listOf("Genres") else emptyList()).forEach { name ->
                    FilterChip(chart == name, onClick = { chart = name }, label = { Text(name) })
                }
            }
            if (chart == "Songs") {
                data.songs.take(30).forEachIndexed { index, entry ->
                    ReplayRanking(index, entry.song.title, entry.song.artist, entry.ms, entry.plays, data.songs.firstOrNull()?.ms ?: 1) {
                        integrations.session.player.playSongs(data.songs.map(RankedSong::song), index)
                    }
                }
            } else {
                val entries = when (chart) { "Artists" -> data.artists; "Albums" -> data.albums; else -> data.genres }
                if (entries.isEmpty()) Text("More metadata will appear as you listen.")
                entries.take(30).forEachIndexed { index, entry ->
                    ReplayRanking(index, entry.title, entry.subtitle.orEmpty(), entry.ms, entry.plays, entries.firstOrNull()?.ms ?: 1) {
                        val id = entry.browseId
                        if (id != null && chart != "Genres") integrations.session.openBrowse(BrowseItem(id, entry.title,
                            entry.subtitle.orEmpty(), entry.artworkUrl, if (chart == "Artists") BrowseType.ARTIST else BrowseType.ALBUM))
                        else integrations.session.search(entry.title)
                        onDismiss()
                    }
                }
            }
        }
        HorizontalDivider()
        IntegrationToggle("Record listening for Replay", settings.replayEnabled) { value -> MobileIntegrationSettings.update { it.copy(replayEnabled = value) } }
        IntegrationToggle("Genre chart", settings.replayGenres, enabled = settings.lastFmApiKey.isNotBlank()) { value -> MobileIntegrationSettings.update { it.copy(replayGenres = value) } }
        Text("The genre chart uses your Last.fm API key and sends artist names to Last.fm. Listening totals stay on this device.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            integrations.perform {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Export Replay"
                    selectedFile = java.io.File("BitChord-Reworked-Replay.json")
                    fileFilter = FileNameExtensionFilter("JSON", "json")
                }
                if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                    val text = Json { prettyPrint = true }.encodeToString(ListeningStats.exportAll())
                    withContext(Dispatchers.IO) { chooser.selectedFile.writeText(text) }
                    integrations.message.value = "Replay exported."
                }
            }
        }) { Text("Export Replay") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun IntegrationDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        modifier = Modifier.widthIn(min = 480.dp, max = 720.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false))
}

@Composable
private fun IntegrationToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun ReplayRanking(index: Int, title: String, subtitle: String, ms: Long, plays: Int, maximum: Long, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text((index + 1).toString(), Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            Text("${ms / 60_000} min · $plays", style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(progress = { (ms.toFloat() / maximum.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}
