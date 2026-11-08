package com.music.bitchord.reworked

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.LocalGroup
import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.matchesSearch
import com.music.bitchord.data.sortedForLibrary
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.ui.screens.CompactTrackRow
import kotlinx.coroutines.launch

private enum class LocalLibraryTab(val label: String) { SONGS("Songs"), ARTISTS("Artists"), ALBUMS("Albums"), PLAYLISTS("Playlists"), FOLDERS("Folders") }

/** New desktop host of mobile LocalFolder + the original pure LocalGroups model. */
@Composable
internal fun MobileLocalLibrary(session: MobileSession, downloaded: Boolean, onMenu: (Song) -> Unit) {
    val allSongs by (if (downloaded) MobileFiles.downloads else MobileFiles.localSongs).collectAsState()
    val records by MobileDownloadedCollections.records.collectAsState()
    val localPlaylists by session.playlists.collectAsState()
    val liked by session.liked.collectAsState()
    val playback by session.player.state.collectAsState()
    val scanning by MobileFiles.scanning.collectAsState()
    val active by MobileFiles.active.collectAsState()
    val errors by MobileFiles.errors.collectAsState()
    val pending by MobileDownloadedCollections.approval.collectAsState()
    val syncMessage by MobileDownloadedCollections.message.collectAsState()
    val folderId = if (downloaded) "local:downloads" else "local:device"
    val preferencePrefix = if (downloaded) "downloads" else "local_music"
    var tab by remember(downloaded) { mutableStateOf(LocalLibraryTab.SONGS) }
    var query by remember(downloaded) { mutableStateOf("") }
    var chosenGroup by remember(downloaded) { mutableStateOf<String?>(null) }
    var sort by remember(downloaded) { mutableStateOf(runCatching {
        LocalMusicSort.valueOf(DesktopStore.preferences.get("${preferencePrefix}_sort", "TITLE_ASC"))
    }.getOrDefault(LocalMusicSort.TITLE_ASC)) }
    var view by remember(downloaded) { mutableStateOf(runCatching {
        LibraryViewType.valueOf(DesktopStore.preferences.get("${preferencePrefix}_view", "LIST"))
    }.getOrDefault(LibraryViewType.LIST)) }
    var sortMenu by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var deleteSelection by remember { mutableStateOf(false) }
    var savePlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val title = if (downloaded) "Downloads" else "Local music"
    val groups = remember(allSongs, records, localPlaylists, liked, tab, folderId) {
        when (tab) {
            LocalLibraryTab.ARTISTS -> LocalGroups.artistsOf(folderId, allSongs)
            LocalLibraryTab.ALBUMS -> LocalGroups.albumsOf(folderId, allSongs,
                if (downloaded) MobileDownloadedCollections.collectionsAmong(allSongs) else emptyList())
            LocalLibraryTab.FOLDERS -> LocalGroups.foldersOf(folderId, allSongs)
            LocalLibraryTab.PLAYLISTS -> {
                val ids = allSongs.mapTo(HashSet()) { it.videoId }
                val localLiked = liked.filter { it.videoId in ids }
                val lists = localPlaylists.map { list -> LocalGroup(list.id, list.title, "", list.songs.firstNotNullOfOrNull { it.thumbnailUrl },
                    BrowseType.PLAYLIST, list.songs.filter { it.videoId in ids }) }
                listOf(LocalGroup("device:liked", "Liked songs", "", localLiked.firstNotNullOfOrNull { it.thumbnailUrl }, BrowseType.PLAYLIST, localLiked)) + lists
            }
            LocalLibraryTab.SONGS -> emptyList()
        }
    }
    val group = groups.firstOrNull { it.browseId == chosenGroup }
    val songs = remember(allSongs, group, sort, query) {
        (group?.songs ?: allSongs.sortedForLibrary(sort)).filter { it.matchesSearch(query) }
    }
    val filteredGroups = remember(groups, query) {
        groups.filter { query.isBlank() || it.title.contains(query, true) || it.subtitle.contains(query, true) || it.songs.any { song -> song.matchesSearch(query) } }
    }
    fun toggle(song: Song) { selection = if (song.videoId in selection) selection - song.videoId else selection + song.videoId }
    fun open(group: LocalGroup) { chosenGroup = group.browseId; query = ""; selection = emptySet() }
    fun play(rows: List<Song>, index: Int = 0) {
        session.player.playSongs(rows.map { it.copy(playbackSource = group?.title ?: title) }, index)
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (group != null) IconButton(onClick = { chosenGroup = null; query = "" }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to $title") }
            Column(Modifier.weight(1f)) {
                Text(group?.title ?: title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${group?.songs?.size ?: allSongs.size} tracks", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!downloaded) {
                TextButton(onClick = MobileFiles::chooseFolder, enabled = !scanning) { Text("Add folder") }
                IconButton(onClick = MobileFiles::scanAgain, enabled = !scanning) { Icon(Icons.Rounded.Refresh, "Rescan folders") }
            }
            IconButton(onClick = {
                view = if (view == LibraryViewType.LIST) LibraryViewType.GRID else LibraryViewType.LIST
                DesktopStore.preferences.put("${preferencePrefix}_view", view.name)
            }) { Icon(if (view == LibraryViewType.LIST) Icons.Rounded.GridView else Icons.Rounded.ViewList, "Switch library view") }
        }
        if (group == null) {
            val tabs = if (downloaded) listOf(LocalLibraryTab.SONGS, LocalLibraryTab.ARTISTS, LocalLibraryTab.ALBUMS) else LocalLibraryTab.entries
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEach { option -> FilterChip(selected = tab == option, onClick = { tab = option; selection = emptySet() }, label = { Text(option.label) }) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Filter this library") }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
            if (tab == LocalLibraryTab.SONGS || group != null) {
                Button(onClick = { play(songs) }, enabled = songs.isNotEmpty()) { Text("Play") }
                IconButton(onClick = { play(songs.shuffled()) }, enabled = songs.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, "Shuffle") }
            }
            if (group == null && tab == LocalLibraryTab.SONGS) Box {
                TextButton(onClick = { sortMenu = true }) { Text(sort.label()) }
                DropdownMenu(sortMenu, { sortMenu = false }) { LocalMusicSort.entries.forEach { order ->
                    DropdownMenuItem(text = { Text(order.label()) }, onClick = { sort = order; DesktopStore.preferences.put("${preferencePrefix}_sort", order.name); sortMenu = false })
                } }
            }
            TextButton(onClick = { selecting = !selecting; selection = emptySet() }) { Text(if (selecting) "Done" else "Select") }
        }
        if (selecting) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${selection.size} selected")
            TextButton(onClick = { selection = songs.mapTo(HashSet()) { it.videoId } }) { Text("Select all") }
            TextButton(onClick = { allSongs.filter { it.videoId in selection }.forEach(session.player::addToQueue) }, enabled = selection.isNotEmpty()) { Text("Add to queue") }
            TextButton(onClick = { savePlaylist = true }, enabled = selection.isNotEmpty()) { Text("Save playlist") }
            if (downloaded) TextButton(onClick = { deleteSelection = true }, enabled = selection.isNotEmpty()) { Text("Remove downloads") }
        }
        if (scanning && !downloaded) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (downloaded && active.isNotEmpty()) {
            Text("${active.size} downloads in progress", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { active.values.average().toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { active.keys.forEach(MobileFiles::cancelDownload) }) { Text("Cancel pending downloads") }
        }
        if (downloaded && errors.isNotEmpty()) Text(errors.values.last(), color = MaterialTheme.colorScheme.error, maxLines = 2)
        if (downloaded && pending.isNotEmpty()) {
            Text(syncMessage ?: "New playlist tracks are ready to download")
            pending.forEach { (id, update) -> TextButton(onClick = { MobileDownloadedCollections.approve(id) }) { Text("Download ${update.count} new tracks · ${update.title}") } }
        }
        val showSongs = tab == LocalLibraryTab.SONGS || group != null
        if ((showSongs && songs.isEmpty()) || (!showSongs && filteredGroups.isEmpty())) {
            Text(if (allSongs.isEmpty()) { if (downloaded) "Downloaded tracks will appear here." else "Add a music folder to browse your files." } else "No matches in this view.",
                Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (view == LibraryViewType.GRID) {
            LazyVerticalGrid(GridCells.Adaptive(154.dp), Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (showSongs) gridItemsIndexed(songs, key = { index, song -> "${song.videoId}:$index" }) { index, song ->
                    LocalGridCard(song.title, song.artist, song.thumbnailUrl, false, song.videoId in selection,
                        onClick = { if (selecting) toggle(song) else play(songs, index) },
                        onMenu = { onMenu(song) })
                } else items(filteredGroups, key = { it.browseId }) { item ->
                    LocalGridCard(item.title, "${item.songs.size} tracks" + item.subtitle.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(), item.thumbnailUrl,
                        tab == LocalLibraryTab.ARTISTS, item.songs.isNotEmpty() && item.songs.all { it.videoId in selection },
                        onClick = { if (selecting) {
                            val ids = item.songs.mapTo(HashSet()) { it.videoId }
                            selection = if (selection.containsAll(ids)) selection - ids else selection + ids
                        } else open(item) }, onMenu = { item.songs.forEach(session.player::addToQueue); session.notice("Added ${item.songs.size} tracks to the queue") }, menuIsQueue = true)
                }
            }
        } else LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (showSongs) itemsIndexed(songs, key = { index, song -> "${song.videoId}:$index" }) { index, song ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) Checkbox(song.videoId in selection, { toggle(song) })
                    Box(Modifier.weight(1f)) { CompactTrackRow(song.title, song.artist, song.thumbnailUrl,
                        { if (selecting) toggle(song) else play(songs, index) }, { onMenu(song) },
                        isCurrent = playback.song?.videoId == song.videoId, isPlaying = playback.isPlaying) }
                    if (!selecting) IconButton(onClick = { onMenu(song) }) { Icon(Icons.Rounded.MoreHoriz, "Track actions") }
                }
            } else items(filteredGroups, key = { it.browseId }) { item ->
                Row(Modifier.fillMaxWidth().clickable { open(item) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (selecting) Checkbox(item.songs.isNotEmpty() && item.songs.all { it.videoId in selection }, { checked ->
                        val ids = item.songs.mapTo(HashSet()) { it.videoId }; selection = if (checked) selection + ids else selection - ids
                    })
                    LocalCover(item.thumbnailUrl, tab == LocalLibraryTab.ARTISTS, Modifier.size(58.dp))
                    Column(Modifier.weight(1f)) { Text(item.title, style = MaterialTheme.typography.titleMedium); Text("${item.songs.size} tracks" + item.subtitle.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    IconButton(onClick = { play(item.songs) }, enabled = item.songs.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, "Play ${item.title}") }
                    IconButton(onClick = { item.songs.forEach(session.player::addToQueue) }, enabled = item.songs.isNotEmpty()) { Icon(Icons.Rounded.PlaylistAdd, "Queue ${item.title}") }
                }
            }
        }
    }
    if (deleteSelection) AlertDialog(onDismissRequest = { deleteSelection = false }, title = { Text("Remove ${selection.size} downloaded tracks?") },
        text = { Text("Their audio files and saved lyrics will be deleted. Playlist sync will remember that you removed them.") },
        confirmButton = { TextButton(onClick = { selection.forEach(MobileFiles::removeDownload); selection = emptySet(); deleteSelection = false }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { deleteSelection = false }) { Text("Cancel") } })
    if (savePlaylist) AlertDialog(onDismissRequest = { savePlaylist = false }, title = { Text("Save a local playlist") },
        text = { OutlinedTextField(playlistName, { playlistName = it }, singleLine = true, label = { Text("Playlist name") }) },
        confirmButton = { TextButton(enabled = playlistName.isNotBlank(), onClick = {
            val selected = allSongs.filter { it.videoId in selection }
            scope.launch { runCatching { session.savePlaylist(playlistName, selected) }.onSuccess { savePlaylist = false; playlistName = "" }.onFailure { session.notice(it.message ?: "Could not save the playlist") } }
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { savePlaylist = false }) { Text("Cancel") } })
}

@Composable
private fun LocalGridCard(title: String, subtitle: String, artwork: String?, artist: Boolean, selected: Boolean,
    onClick: () -> Unit, onMenu: () -> Unit, menuIsQueue: Boolean = false) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(6.dp)) {
        LocalCover(artwork, artist, Modifier.fillMaxWidth().aspectRatio(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) { Icon(if (menuIsQueue) Icons.Rounded.PlaylistAdd else Icons.Rounded.MoreHoriz, if (menuIsQueue) "Add to queue" else "Track actions") }
        }
        Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LocalCover(artwork: String?, artist: Boolean, modifier: Modifier) {
    Box(modifier.clip(if (artist) CircleShape else RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (artwork != null) AsyncImage(artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(if (artist) Icons.Rounded.Person else Icons.Rounded.MusicNote, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LocalMusicSort.label(): String = when (this) {
    LocalMusicSort.TITLE_ASC -> "Title A–Z"
    LocalMusicSort.TITLE_DESC -> "Title Z–A"
    LocalMusicSort.DATE_ADDED -> "Date added"
    LocalMusicSort.DATE_MODIFIED -> "Date modified"
}
