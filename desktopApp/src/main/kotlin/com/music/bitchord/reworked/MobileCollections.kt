package com.music.bitchord.reworked

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.*
import com.music.bitchord.ui.screens.CompactTrackRow
import com.music.bitchord.ui.components.contextClick
import com.music.bitchord.ui.player.CanvasArtworkPlayer
import kotlinx.coroutines.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Mobile DetailScreen's repository, artwork and song-row contracts in a desktop window. */
@Composable
internal fun CollectionPage(page: DetailPage, session: MobileSession, onSongMenu: (Song) -> Unit, onMenu: (DetailPage) -> Unit) {
    if (page.browseId.startsWith("shelf:")) {
        page.sections.firstOrNull()?.let { shelf -> com.music.bitchord.ui.screens.LibraryGridPage(shelf, androidx.compose.foundation.lazy.grid.rememberLazyGridState(), { session.openShelf(it) }, { item -> item.collectionPreview()?.let(onMenu) }, PaddingValues(24.dp)) }
        return
    }
    val playback by session.player.state.collectAsState()
    val loadingMore by session.loadingMoreDetail.collectAsState()
    val filter by session.detailFilter.collectAsState()
    val all = (page.songs as? UiState.Success)?.data.orEmpty()
    val songs = remember(all, filter) { if (filter.isBlank()) all else all.filter { "${it.title} ${it.artist} ${it.albumName.orEmpty()}".contains(filter, true) } }
    val playable = remember(songs) { songs.filterNot(Song::isUnresolvedSpotify) }
    val playingRow = remember(songs, playback.song) { playingRowMatcher(songs, playback.song) }
    val artistArt = rememberMobileArtistArt(page)
    var motion by remember(page.browseId) { mutableStateOf<com.music.bitchord.data.canvas.CanvasArtwork?>(null) }
    LaunchedEffect(page.browseId, MobilePlayerSettings.animatedCanvas.collectAsState().value) {
        motion = if (page.type == BrowseType.ARTIST) session.host.artistMotion(page.browseId, page.title)
        else if (page.type == BrowseType.ALBUM) session.host.releaseMotion(page.title, page.subtitle) else null
    }
    val listState = rememberLazyListState()
    LaunchedEffect(page.browseId) { listState.scrollToItem(0); session.detailFilter.value = "" }
    LazyColumn(state = listState, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.size(if (page.type == BrowseType.ARTIST) 250.dp else 210.dp).clip(RoundedCornerShape(12.dp))) {
                    AsyncImage(artistArt?.heroUrl ?: page.thumbnailUrl, page.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    motion?.let { artwork -> CanvasArtworkPlayer(artwork, isPlaying = true, modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(page.type.name.lowercase().replaceFirstChar(Char::titlecase), style = MaterialTheme.typography.labelLarge)
                    MobileArtistName(page, artistArt)
                    Text(page.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (all.isNotEmpty()) Text("${all.size}${if (page.continuation != null) "+" else ""} tracks", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { session.playCollection(page) }, enabled = playable.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Text("Play") }
                        OutlinedButton(onClick = { session.playCollection(page, shuffle = true) }, enabled = playable.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, null); Text("Shuffle") }
                        page.library?.let { saved -> IconButton(onClick = { session.toggleSaved(page) }) { Icon(if (saved.saved) Icons.Rounded.Check else Icons.Rounded.Add, if (saved.saved) "Remove from library" else "Save to library") } }
                        IconButton(onClick = { onMenu(page) }) { Icon(Icons.Rounded.MoreHoriz, "Collection actions") }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        item { OutlinedTextField(filter, { session.detailFilter.value = it }, label = { Text("Find in ${page.title}") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        when (val state = page.songs) {
            UiState.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp)) }
            is UiState.Error -> item {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { session.openBrowse(BrowseItem(page.browseId, page.title, page.subtitle, page.thumbnailUrl, page.type)) }) { Text("Retry") }
            }
            is UiState.Success -> {
                itemsIndexed(songs, key = { index, song -> "${song.queueEntryId ?: song.videoId}:$index" }) { _, song ->
                    Row(Modifier.fillMaxWidth().contextClick { onSongMenu(song) }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            CompactTrackRow(title = song.title,
                                subtitle = if (song.isMatchPending) "Finding a match…" else if (song.isMatchMissing) "No playable match · ${song.artist}" else song.artist,
                                thumbnailUrl = song.thumbnailUrl,
                                onClick = { if (!song.isUnresolvedSpotify) session.player.playSongs(playable.map { it.copy(playbackSource = page.title, playbackSourceType = PlaybackSourceType.BROWSE, playbackSourceId = page.browseId) }, playable.indexOf(song)) },
                                onLongPress = { onSongMenu(song) }, isCurrent = playingRow(song), isPlaying = playback.isPlaying, isVideo = song.isVideo,
                                showMenuButton = false)
                        }
                        Text(song.durationText.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { onSongMenu(song) }) { Icon(Icons.Rounded.MoreHoriz, "Actions for ${song.title}") }
                    }
                }
                if (page.continuation != null) item { TextButton(onClick = session::moreDetail, enabled = !loadingMore) { Text(if (loadingMore) "Loading…" else "Load more tracks") } }
            }
        }
        page.sections.forEach { shelf ->
            item {
                Text(shelf.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(shelf.items) { _, item ->
                        Column(Modifier.width(148.dp).clickable { session.openShelf(item, shelf.title) }) {
                            AsyncImage(item.thumbnailUrl, null, Modifier.size(148.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        page.description?.takeIf(String::isNotBlank)?.let { description -> item { Text("About", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp)); Text(description, Modifier.padding(top = 8.dp)) } }
    }
}

@Composable
internal fun PlaylistDialog(session: MobileSession, song: Song?, onDismiss: () -> Unit, onImport: () -> Unit) {
    val playlists by session.playlists.collectAsState()
    val signedIn by session.signedIn.collectAsState()
    val revision by session.listenerRevision.collectAsState()
    val onlineSong = song == null || (song.localUri == null && !song.videoId.contains(':'))
    var name by remember(revision) { mutableStateOf("") }
    var account by remember(revision) { mutableStateOf(false) }
    var busy by remember(revision) { mutableStateOf(false) }
    var error by remember(revision) { mutableStateOf<String?>(null) }
    var remote by remember(revision, song?.videoId) { mutableStateOf<List<UserPlaylist>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision, signedIn, song) {
        remote = emptyList()
        if (signedIn && song != null && onlineSong) session.accountRead(revision) { YtMusicRepository.userPlaylists() }
            .onSuccess { remote = it }.onFailure { error = it.message }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (song == null) "New playlist" else "Add to playlist") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (song != null) {
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(playlists, key = { it.id }) { playlist -> TextButton(onClick = { session.addToPlaylist(playlist.id, song); onDismiss() }) { Text("${playlist.title} · On this computer") } }
                    items(remote, key = { it.playlistId }) { playlist -> TextButton(enabled = !busy, onClick = {
                        busy = true; scope.launch { try {
                            session.accountWrite(revision) { YtMusicRepository.addToPlaylist(playlist.playlistId, listOf(song.videoId)) }
                                .onSuccess { session.refreshLibrary(); onDismiss() }.onFailure { error = it.message }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Could not add this song" }
                        finally { busy = false } }
                    }) { Text("${playlist.title} · YouTube Music") } }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("New playlist name") }, singleLine = true)
            if (signedIn && onlineSong) ReworkedToggle("Save to YouTube Music account", account) { account = it }
            TextButton(onClick = onImport, enabled = !busy) { Text("Import a Spotify playlist") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank() && !busy, onClick = {
        busy = true; scope.launch { try { session.requireListener(revision); session.savePlaylist(name, listOfNotNull(song), account); onDismiss() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = e.message ?: "Could not create playlist" } finally { busy = false } }
    }) { Text("Create") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
internal fun SongMenu(song: Song, session: MobileSession, onDismiss: () -> Unit, onPlaylist: () -> Unit, onLyricsTiming: (() -> Unit)? = null) {
    val liked by session.liked.collectAsState()
    val downloads by MobileFiles.downloads.collectAsState()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(song.title, maxLines = 2) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 460.dp)) {
            if (!song.isUnresolvedSpotify) {
                MenuAction("Play next") { session.player.playNext(song); onDismiss() }
                MenuAction("Add to queue") { session.player.addToQueue(song); onDismiss() }
                MenuAction(if (liked.any { it.videoId == song.videoId }) "Remove from liked songs" else "Like song") { session.toggleLike(song); onDismiss() }
                MenuAction("Add to playlist", onPlaylist)
                if (song.localUri == null) MenuAction("Start mix") { session.startMix(song); onDismiss() }
                if (downloads.any { it.videoId == song.videoId }) MenuAction("Remove download") { MobileFiles.removeDownload(song.videoId); onDismiss() }
                else if (song.localUri == null) MenuAction("Download") { MobileFiles.download(song); onDismiss() }
            }
            song.albumId?.let { id -> MenuAction("Go to album") { session.openBrowse(BrowseItem(id, song.albumName ?: "Album", song.artist, song.thumbnailUrl, BrowseType.ALBUM)); onDismiss() } }
            val artists = song.artists.ifEmpty { listOf(ArtistRef(song.artist, song.artistId)) }
            artists.forEach { artist -> MenuAction("Go to ${artist.name}") {
                if (artist.browseId != null) session.openBrowse(BrowseItem(artist.browseId!!, artist.name, "", null, BrowseType.ARTIST)) else session.search(artist.name)
                onDismiss()
            } }
            if (song.localUri == null && session.player.state.value.song?.videoId == song.videoId) {
                MenuAction(if (song.isVideo) "Switch to audio version" else "Switch to music video version") { session.toggleVersion(song); onDismiss() }
                if (song.isVideo) MenuAction(if (session.host.showVideo.value) "Hide video picture" else "Show video picture") { session.host.showVideo.value = !session.host.showVideo.value; onDismiss() }
            }
            if (session.player.state.value.song?.videoId == song.videoId && onLyricsTiming != null) {
                MenuAction("Adjust lyrics timing") { onDismiss(); onLyricsTiming() }
            }
            if (song.localUri == null && !song.isUnresolvedSpotify) MenuAction("Copy song link") { copyText("https://music.youtube.com/watch?v=${song.videoId}"); session.notice("Song link copied"); onDismiss() }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
internal fun CollectionMenu(page: DetailPage, session: MobileSession, onDismiss: () -> Unit) {
    val pins by session.host.pinnedPlaylists.collectAsState()
    val local by session.playlists.collectAsState()
    val revision by session.listenerRevision.collectAsState()
    val ownedLocal = local.any { it.id == page.browseId }
    var ownedRemote by remember(revision, page.browseId) { mutableStateOf(false) }
    var edit by remember(revision) { mutableStateOf(false) }
    var delete by remember(revision) { mutableStateOf(false) }
    var name by remember(revision) { mutableStateOf(page.title) }
    var busy by remember(revision) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var editTracks by remember(revision, page.browseId) { mutableStateOf(false) }
    LaunchedEffect(revision, page.browseId) { if (!ownedLocal && session.signedIn.value && page.type == BrowseType.PLAYLIST && !page.browseId.contains(':'))
        ownedRemote = session.accountRead(revision) { YtMusicRepository.playlistOwned(page.browseId) }.getOrNull() == true }
    fun action(block: suspend () -> Unit) { busy = true; scope.launch { try { session.requireListener(revision); block(); onDismiss() } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { session.notice(e.message ?: "Action failed") } finally { busy = false } } }
    if (editTracks) {
        MobilePlaylistEditor(page, session) { editTracks = false; onDismiss() }
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (delete) "Delete ${page.title}?" else page.title, maxLines = 2) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 480.dp)) {
            if (delete) Text("This removes the playlist. The music files themselves are kept.")
            else if (edit) OutlinedTextField(name, { name = it }, label = { Text("Playlist name") })
            else {
                MenuAction("Play") { session.playCollection(page); onDismiss() }
                MenuAction("Shuffle") { session.playCollection(page, true); onDismiss() }
                MenuAction("Start mix") { action { session.collectionSongs(page).firstOrNull()?.let(session::startMix) } }
                MenuAction("Play next") { action { session.collectionSongs(page).asReversed().forEach(session.player::playNext) } }
                MenuAction("Add to queue") { action { session.collectionSongs(page).forEach(session.player::addToQueue) } }
                if (!ownedLocal) MenuAction("Save a local copy") { action { session.savePlaylist(page.title, session.collectionSongs(page)) } }
                page.library?.let { state -> MenuAction(if (state.saved) "Remove from library" else "Save to library") { session.toggleSaved(page); onDismiss() } }
                MenuAction(if (page.browseId in pins) "Unpin" else "Pin playlist") { session.host.togglePin(page.browseId); onDismiss() }
                MenuAction("Download collection") { action {
                    val songs = session.collectionSongs(page)
                    session.accountRead(revision) { MobileFiles.downloadCollection(page.browseId, page.title, songs,
                        playlist = page.type == BrowseType.PLAYLIST, subtitle = page.subtitle, thumbnailUrl = page.thumbnailUrl) }
                } }
                if (!page.browseId.contains(':')) MenuAction("Copy collection link") { copyText(if (page.type == BrowseType.ARTIST) "https://music.youtube.com/channel/${page.browseId}" else "https://music.youtube.com/browse/${page.browseId}"); onDismiss() }
                if (ownedLocal || ownedRemote) { MenuAction("Edit tracks") { editTracks = true }; MenuAction("Rename playlist") { edit = true }; MenuAction("Delete playlist") { delete = true } }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        if (edit || delete) TextButton(enabled = !busy && (delete || name.isNotBlank()), onClick = { action {
            if (delete) { if (ownedLocal) session.deletePlaylist(page.browseId) else { session.accountWrite(revision) { YtMusicRepository.deletePlaylist(page.browseId.removePrefix("VL")) }.getOrThrow(); session.back(); session.refreshLibrary() } }
            else { if (ownedLocal) session.renamePlaylist(page.browseId, name) else { session.accountWrite(revision) { YtMusicRepository.renamePlaylist(page.browseId.removePrefix("VL"), name.trim()) }.getOrThrow(); session.refreshLibrary() }; session.detail.value = session.detail.value?.copy(title = name.trim()) }
        } }) { Text(if (delete) "Delete" else "Save") }
        else TextButton(onClick = onDismiss) { Text("Close") }
    }, dismissButton = { if (edit || delete) TextButton(onClick = { edit = false; delete = false }) { Text("Cancel") } })
}

@Composable
private fun MenuAction(title: String, onClick: () -> Unit) { TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(title, Modifier.fillMaxWidth()) } }

internal fun copyText(text: String) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }

/** Mobile browse identity retained by desktop context menus before the page is fetched. */
internal fun ShelfItem.collectionPreview(): DetailPage? = browseId?.let { id ->
    DetailPage(id, title, subtitle, thumbnailUrl, UiState.Loading, when {
        id.startsWith("UC") -> BrowseType.ARTIST; id.startsWith("MPRE") -> BrowseType.ALBUM; else -> BrowseType.PLAYLIST
    })
}
