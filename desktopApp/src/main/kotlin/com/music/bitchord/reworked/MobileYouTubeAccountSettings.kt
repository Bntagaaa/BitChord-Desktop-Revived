package com.music.bitchord.reworked

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fresh desktop surface for mobile AccountProfileSelector / AccountChannelDialog. */
@Composable
internal fun MobileYouTubeAccountSettings(
    onAddAccount: () -> Unit = { DesktopBrowserAuth.youtubeSession() },
    onChooseInYouTube: (String) -> Unit = { id -> DesktopBrowserAuth.youtubeSession(YouTubeSessionMode.SWITCH_CHANNEL, id) },
) {
    val state by MobileYouTubeAccounts.state.collectAsState()
    var showAccounts by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("YouTube Music accounts", style = MaterialTheme.typography.titleMedium)
        Text(state.activeProfile?.name?.let { "Listening as $it" } ?: "Sign in to use your YouTube Music library.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showAccounts = true }, enabled = !state.busy) {
                Icon(Icons.Rounded.AccountCircle, null); Spacer(Modifier.width(8.dp)); Text("Accounts and profiles")
            }
            TextButton(onClick = onAddAccount, enabled = !state.busy) { Icon(Icons.Rounded.Add, null); Text("Add account") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (showAccounts) MobileYouTubeAccountPicker({ showAccounts = false }, onAddAccount, onChooseInYouTube)
}

/** Optional compact entry for the app bar, using the same complete selector. */
@Composable
internal fun MobileYouTubeProfileButton(modifier: Modifier = Modifier) {
    val state by MobileYouTubeAccounts.state.collectAsState()
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }, modifier = modifier) {
        AccountAvatar(state.activeProfile?.avatar, Modifier.size(26.dp))
        Spacer(Modifier.width(8.dp))
        Text(state.activeProfile?.name ?: "Sign in", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (show) MobileYouTubeAccountPicker({ show = false })
}

@Composable
internal fun MobileYouTubeAccountPicker(
    onDismiss: () -> Unit,
    onAddAccount: () -> Unit = { DesktopBrowserAuth.youtubeSession() },
    onChooseInYouTube: (String) -> Unit = { id -> DesktopBrowserAuth.youtubeSession(YouTubeSessionMode.SWITCH_CHANNEL, id) },
) {
    val state by MobileYouTubeAccounts.state.collectAsState()
    val scope = rememberCoroutineScope()
    var remove by remember { mutableStateOf<YouTubeAccountSummary?>(null) }
    LaunchedEffect(state.activeAccountId, state.activeProfileId) {
        if (state.signedIn && !state.busy) withContext(Dispatchers.IO) { MobileYouTubeAccounts.refreshChannels() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.width(600.dp).heightIn(max = 720.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Accounts and profiles", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Text("Choose a saved profile, or add a separate Google account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.accounts.isEmpty()) item { Text("No Google accounts saved.", Modifier.padding(vertical = 16.dp)) }
                    items(state.accounts, key = { it.accountId }) { account ->
                        OutlinedCard {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(account.name.ifBlank { "Google account" }, style = MaterialTheme.typography.titleSmall)
                                        if (account.email.isNotBlank()) Text(account.email, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { remove = account }, enabled = !state.busy) {
                                        Icon(Icons.Rounded.DeleteOutline, "Remove saved account")
                                    }
                                }
                                account.profiles.forEach { profile ->
                                    val selected = account.accountId == state.activeAccountId && profile.profileId == state.activeProfileId
                                    Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                                        scope.launch(Dispatchers.IO) { MobileYouTubeAccounts.selectProfile(account.accountId, profile.profileId) }
                                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected, onClick = null)
                                        AccountAvatar(profile.avatar, Modifier.padding(horizontal = 8.dp).size(38.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (profile.handle.isNotBlank()) Text(profile.handle, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(if (profile.isBrandAccount) "Brand" else "Personal", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                TextButton(onClick = { onChooseInYouTube(account.accountId) }, enabled = !state.busy) {
                                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Choose profile in YouTube Music")
                                }
                            }
                        }
                    }
                    if (state.signedIn) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Channels on the active account", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { scope.launch(Dispatchers.IO) { MobileYouTubeAccounts.refreshChannels() } }, enabled = !state.busy) {
                                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Refresh")
                                }
                            }
                            if (state.channels.isEmpty()) Text("Refresh to discover channels. If a channel is missing, choose it in YouTube Music and confirm the profile.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            state.channels.forEach { channel ->
                                val selected = YouTubeAccountRules.profileId(channel.pageId, channel.dataSyncId, channel.name) == state.activeProfileId
                                Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                                    scope.launch(Dispatchers.IO) { MobileYouTubeAccounts.selectChannel(channel.key) }
                                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected, onClick = null)
                                    AccountAvatar(channel.thumbnailUrl, Modifier.padding(horizontal = 8.dp).size(32.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(channel.name)
                                        if (channel.subtitle.isNotBlank()) Text(channel.subtitle, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (channel.activeOnWeb) Text("YouTube default", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Button(onClick = onAddAccount, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Add Google account")
                }
            }
        }
    }
    remove?.let { account ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove saved account?") },
            text = { Text("Remove ${account.name.ifBlank { "this Google account" }} and its saved profiles from BitChord Reworked?") },
            confirmButton = { TextButton(onClick = {
                remove = null; scope.launch(Dispatchers.IO) { MobileYouTubeAccounts.removeAccount(account.accountId) }
            }, enabled = !state.busy) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } })
    }
}

@Composable
private fun AccountAvatar(url: String?, modifier: Modifier) {
    if (url.isNullOrBlank()) Icon(Icons.Rounded.AccountCircle, null, modifier)
    else AsyncImage(url, contentDescription = null, modifier = modifier.clip(CircleShape))
}
