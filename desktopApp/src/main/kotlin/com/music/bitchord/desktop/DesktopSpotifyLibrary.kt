package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.screens.LibraryGridPage
import com.music.bitchord.sharedui.resources.Res as SharedUiRes
import com.music.bitchord.sharedui.resources.spotify_logo
import org.jetbrains.compose.resources.painterResource

import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifyTrack
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The Library row that opens the Spotify account's playlists; the phone's browse id. */
internal const val SPOTIFY_LIBRARY_ID = "app:spotify"

/** The Library grid's title for those playlists, which is also how the grid is told apart. */
internal const val SPOTIFY_SHELF = "Spotify"

/** Matches looked up at once; the phone's figure, gentle enough on YouTube Music's search. */
private const val MATCH_PARALLELISM = 4

/** A card on the Spotify grid, opening a [DesktopCollection] of its tracks. */
internal fun SpotifyPlaylist.toShelfItem() = ShelfItem(
    title = name,
    subtitle = owner.orEmpty(),
    thumbnailUrl = imageUrl,
    videoId = null,
    browseId = SPOTIFY_PAGE_PREFIX + id,
)

/** A row for a Spotify track whose YouTube Music version has not been found yet. */
internal fun SpotifyTrack.asPendingSong() = Song(
    videoId = SPOTIFY_PENDING_PREFIX + id,
    title = title,
    artist = artist,
    thumbnailUrl = imageUrl,
    durationText = durationMs.takeIf { it > 0 }?.let { ms -> "%d:%02d".format(ms / 60000, ms / 1000 % 60) },
    albumName = album,
)

/**
 * A placeholder shown while Spotify tracks are being matched to YouTube Music.
 * The original metadata is retained, so tapping a row can resolve it immediately instead
 * of accidentally sending its Spotify id to the YouTube playback engine.
 */
internal fun Song.unresolvedSpotifyTrack(): SpotifyTrack? {
    val spotifyId = when {
        videoId.startsWith(SPOTIFY_PENDING_PREFIX) -> videoId.removePrefix(SPOTIFY_PENDING_PREFIX)
        videoId.startsWith(SPOTIFY_MISSING_PREFIX) -> videoId.removePrefix(SPOTIFY_MISSING_PREFIX)
        else -> return null
    }
    if (spotifyId.isBlank()) return null
    val parts = durationText.orEmpty().split(':').map(String::toIntOrNull)
    val seconds = if (parts.size in 2..3 && parts.all { it != null }) {
        parts.fold(0L) { acc, piece -> acc * 60L + piece!! }
    } else 0L
    return SpotifyTrack(
        id = spotifyId,
        title = title,
        artist = artist,
        album = albumName,
        durationMs = (seconds * 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        imageUrl = thumbnailUrl,
    )
}

/**
 * Fills a Spotify playlist page the way the phone's `MainViewModel` does: Spotify's tracks as each
 * page of them arrives, then every row swapped in place for its YouTube Music match, or marked as
 * having none. [update] applies a change to the page if it is still the one open.
 */
internal suspend fun loadSpotifyPlaylist(
    playlistId: String,
    isOpen: () -> Boolean,
    update: ((DesktopCollection) -> DesktopCollection) -> Unit,
) = coroutineScope {
    launch {
        // The library card only had a thumbnail.
        runCatching { SpotifyLibrary.cover(playlistId) }.getOrNull()
            ?.let { cover -> update { it.copy(thumbnailUrl = cover) } }
    }
    // SpotifyLibrary calls onPage from its IO dispatcher. Forward those snapshots through a
    // channel so Compose state is only changed on the desktop UI coroutine.
    val pages = Channel<List<SpotifyTrack>>(Channel.UNLIMITED)
    val pageConsumer = launch {
        for (soFar in pages) {
            if (!isOpen()) {
                pages.cancel()
                break
            }
            update { it.copy(songs = soFar.map(SpotifyTrack::asPendingSong), loading = false) }
        }
    }
    val tracks = try {
        SpotifyLibrary.tracks(playlistId) { soFar ->
            pages.trySend(soFar.toList())
        }
    } finally {
        pages.close()
        pageConsumer.join()
    }
    check(tracks.isNotEmpty()) { "This Spotify playlist has no tracks." }
    val gate = Semaphore(MATCH_PARALLELISM)
    tracks.forEachIndexed { index, track ->
        launch {
            gate.withPermit {
                if (!isOpen()) return@withPermit
                val match = runCatching { SpotifyImporter.matchTrack(track) }.getOrNull()
                val row = match?.copy(thumbnailUrl = match.thumbnailUrl ?: track.imageUrl)
                    ?: track.asPendingSong().copy(videoId = SPOTIFY_MISSING_PREFIX + track.id)
                update { page ->
                    if (index < page.songs.size) {
                        page.copy(songs = page.songs.toMutableList().also { it[index] = row })
                    } else {
                        page
                    }
                }
            }
        }
    }
}


/**
 * Desktop counterpart of the mobile v1.8.1 Spotify library route. Authentication is separate from
 * the YouTube Music account; playlist cards open the same Spotify-backed detail pages.
 */
@Composable
internal fun DesktopSpotifyLibraryPage(
    playlists: List<SpotifyPlaylist>,
    connected: Boolean,
    loading: Boolean,
    error: String?,
    gridState: LazyGridState,
    contentPadding: PaddingValues,
    onConnect: () -> Unit,
    onManageAccount: () -> Unit,
    onRetry: () -> Unit,
    onOpenPlaylist: (SpotifyPlaylist) -> Unit,
    onImport: suspend (String, Boolean, (String) -> Unit) -> String,
) {
    var showImport by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(SharedUiRes.drawable.spotify_logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(32.dp),
            )
            Text("Spotify", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showImport = true }) { Text("Import playlist") }
            TextButton(onClick = onManageAccount) {
                Text("Account & integrations")
            }
            if (connected) {
                TextButton(onClick = onRetry, enabled = !loading) {
                    Text(if (loading) "Refreshing..." else "Refresh")
                }
            }
        }
        when {
            !connected -> Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Connect your Spotify account to browse playlists and Liked Songs.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onConnect) { Text("Connect Spotify") }
            }
            loading && playlists.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            error != null && playlists.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(error, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onRetry, enabled = !loading) { Text("Retry") }
                    OutlinedButton(onClick = onConnect) { Text("Reconnect Spotify") }
                }
            }
            else -> {
                if (error != null) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
                LibraryGridPage(
                    shelf = HomeShelf(SPOTIFY_SHELF, playlists.map(SpotifyPlaylist::toShelfItem)),
                    gridState = gridState,
                    onItemClick = { item ->
                        playlists.firstOrNull { (SPOTIFY_PAGE_PREFIX + it.id) == item.browseId }
                            ?.let(onOpenPlaylist)
                    },
                    onItemLongPress = {},
                    contentPadding = contentPadding,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (showImport) {
            DesktopSpotifyImportDialog(onImport = onImport, onDismiss = { showImport = false })
        }
    }
}

/**
 * Shared import dialog for Spotify and Library entry points. It owns a single import
 * session and prevents dismissal while an account/local playlist is being written.
 */
@Composable
internal fun DesktopSpotifyImportDialog(
    onImport: suspend (String, Boolean, (String) -> Unit) -> String,
    onDismiss: () -> Unit,
) {
    val importScope = rememberCoroutineScope()
    var importUrl by remember { mutableStateOf("") }
    var importToAccount by remember { mutableStateOf(false) }
    var importBusy by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var importResult by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { if (!importBusy) onDismiss() },
                title = { Text("Import Spotify playlist") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Paste a Spotify playlist link or Spotify URI. Songs will be matched on YouTube Music.")
                        OutlinedTextField(
                            value = importUrl,
                            onValueChange = { importUrl = it; importError = null; importResult = null },
                            label = { Text("Spotify playlist URL") },
                            singleLine = true,
                            enabled = !importBusy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Save to YouTube Music account", modifier = Modifier.weight(1f))
                            Switch(
                                checked = importToAccount,
                                onCheckedChange = { importToAccount = it },
                                enabled = !importBusy,
                            )
                        }
                        if (importBusy) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(importProgress.ifBlank { "Importing…" })
                            }
                        }
                        importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        importResult?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !importBusy && SpotifyImporter.extractPlaylistId(importUrl) != null,
                        onClick = {
                            importBusy = true
                            importError = null
                            importResult = null
                            importScope.launch {
                                try {
                                    importResult = onImport(importUrl, importToAccount) { message ->
                                        importScope.launch { importProgress = message }
                                    }
                                    importUrl = ""
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    importError = error.message ?: "Import failed."
                                } finally {
                                    importBusy = false
                                    importProgress = ""
                                }
                            }
                        },
                    ) { Text("Import") }
                },
                dismissButton = {
                    TextButton(onClick = { if (!importBusy) onDismiss() }, enabled = !importBusy) {
                        Text("Close")
                    }
                },
            )
}
