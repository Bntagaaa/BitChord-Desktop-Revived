package com.music.bitchord.reworked

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.*
import com.music.bitchord.data.spotify.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable
internal fun ReworkedSpotifyPage(
    onOpenPlaylist: (SpotifyPlaylist) -> Unit,
    onImport: () -> Unit,
    onSetup: () -> Unit,
) {
    var revision by remember { mutableStateOf(0) }
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) {
        loading = true; message = null
        try { playlists = SpotifyLibrary.playlists() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "Could not load Spotify library" }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Spotify", style = MaterialTheme.typography.headlineLarge)
        Text("Your playlists, matched through BitChord's music sources", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onSetup) { Text("Connect Spotify") }
            TextButton(onClick = onImport) { Text("Import playlist") }
            TextButton(onClick = { revision++ }, enabled = !loading) { Text("Refresh") }
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Loading library…", Modifier.padding(vertical = 8.dp))
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp)) }
        LazyColumn(Modifier.weight(1f)) {
            items(playlists, key = { it.id }) { playlist ->
                Row(Modifier.fillMaxWidth().clickable { onOpenPlaylist(playlist) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(playlist.imageUrl, null, Modifier.size(56.dp))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(playlist.owner.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReworkedSpotifyImportDialog(
    signedIn: Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (title: String, songs: List<Song>, toAccount: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var toAccount by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0 to 0) }
    var result by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    var unmatched by remember { mutableStateOf<List<SpotifyImportTrack>>(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Spotify playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(input, { input = it; result = null; message = null }, enabled = !busy, singleLine = true,
                    label = { Text("Spotify playlist link or URI") })
                if (signedIn) ReworkedToggle("Save to YouTube Music account", toAccount) { toAccount = it }
                else Text("The imported playlist will be saved on this computer.")
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (progress.second == 0) "Reading playlist…" else "Matched ${progress.first} / ${progress.second}")
                }
                result?.let { (title, songs) ->
                    Text("$title: ${songs.size} playable tracks")
                    if (unmatched.isNotEmpty()) Text("${unmatched.size} unmatched: " + unmatched.take(8).joinToString { "${it.title} — ${it.artist}" })
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            TextButton(enabled = !busy && input.isNotBlank(), onClick = {
                busy = true; message = null
                scope.launch {
                    try {
                        val ready = result
                        if (ready != null) {
                            require(ready.second.isNotEmpty()) { "No playable tracks to save" }
                            onSave(ready.first, ready.second, signedIn && toAccount)
                            onDismiss()
                        } else {
                            val id = SpotifyImporter.extractPlaylistId(input) ?: error("Enter a valid Spotify playlist link or URI")
                            val (title, tracks) = SpotifyImporter.fetchPlaylistTracks(id)
                            val (songs, missing) = SpotifyImporter.resolveToSongs(tracks, onProgress = { done, total ->
                                scope.launch { progress = maxOf(progress.first, done) to total }
                            })
                            unmatched = missing; result = title to songs
                            if (songs.isEmpty()) message = "No playable matches were found."
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message ?: "Import failed" }
                    finally { busy = false }
                }
            }) { Text(if (result == null) "Find tracks" else "Save playlist") }
        },
    )
}
