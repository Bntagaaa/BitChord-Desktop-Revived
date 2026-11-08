package com.music.bitchord.reworked

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** The occurrence is the server set-video-id, or its original local index, never a video id. */
internal data class PlaylistEditEntry(val key: String, val song: Song)

internal fun playlistEditEntries(songs: List<Song>, remote: Boolean): List<PlaylistEditEntry> {
    val entries = songs.mapIndexed { index, song ->
        val key = if (remote) "server:" + requireNotNull(song.setVideoId?.takeIf(String::isNotBlank)) {
            "A playlist entry has no server identity. Reload the complete playlist before editing."
        } else "local:$index"
        PlaylistEditEntry(key, song)
    }
    require(entries.map { it.key }.distinct().size == entries.size) { "The playlist contains a repeated entry identity" }
    return entries
}

internal fun editedPlaylistSongs(original: List<PlaylistEditEntry>, edited: List<PlaylistEditEntry>): List<Song> {
    val byKey = original.associateBy { it.key }
    require(byKey.size == original.size && edited.map { it.key }.distinct().size == edited.size) { "A playlist occurrence was repeated" }
    return edited.map { entry ->
        val known = requireNotNull(byKey[entry.key]) { "The edit contains an entry that was not loaded" }
        require(known.song == entry.song) { "The edit changed a playlist entry instead of moving it" }
        known.song
    }
}

/** Original mobile remove-entry + playlistMoves endpoints, applied to one freshly checked list.
 * The caller holds accountWrite and has revalidated ownership before entering this function.
 */
internal suspend fun saveRemotePlaylistEdits(
    original: List<PlaylistEditEntry>,
    edited: List<PlaylistEditEntry>,
    fresh: suspend () -> List<Song>,
    remove: suspend (List<Pair<String, String>>) -> Unit,
    reorder: suspend (List<String>, List<String>) -> Unit,
): List<Song> {
    val updated = editedPlaylistSongs(original, edited)
    val latest = playlistEditEntries(fresh(), remote = true)
    fun identities(entries: List<PlaylistEditEntry>) = entries.map { it.key to it.song.videoId }
    check(identities(latest) == identities(original)) { "The playlist changed since it was opened. Reload it before saving." }
    currentCoroutineContext().ensureActive()
    val retained = edited.mapTo(HashSet()) { it.key }
    val removed = original.filterNot { it.key in retained }.map { checkNotNull(it.song.setVideoId) to it.song.videoId }
    val current = original.filter { it.key in retained }.map { checkNotNull(it.song.setVideoId) }
    val target = updated.map { checkNotNull(it.setVideoId) }
    if (removed.isNotEmpty()) remove(removed)
    currentCoroutineContext().ensureActive()
    if (current != target) reorder(current, target)
    return updated
}

/** Fresh JVM presentation of mobile ReorderPlaylistPage/MainViewModel's staged edit contract. */
@Composable
internal fun MobilePlaylistEditor(page: DetailPage, session: MobileSession, onDismiss: () -> Unit) {
    val revision = remember(page.browseId) { session.listenerRevision.value }
    val liveRevision by session.listenerRevision.collectAsState()
    val local = page.browseId.startsWith("local:")
    var original by remember(page.browseId, revision) { mutableStateOf<List<PlaylistEditEntry>>(emptyList()) }
    val order = remember(page.browseId, revision) { mutableStateListOf<PlaylistEditEntry>() }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var needsReload by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val changed = order.map { it.key } != original.map { it.key }

    LaunchedEffect(liveRevision) { if (liveRevision != revision) onDismiss() }
    LaunchedEffect(page.browseId, revision, reload) {
        loading = true; error = null; needsReload = false
        original = emptyList(); order.clear()
        try {
            session.requireListener(revision)
            check(page.type == BrowseType.PLAYLIST) { "Only playlists can be edited" }
            val songs = if (local) {
                session.playlists.value.firstOrNull { it.id == page.browseId }?.songs
                    ?: throw IllegalStateException("This local playlist no longer exists")
            } else {
                OfflineSmoke.requireNetwork()
                check(':' !in page.browseId) { "This source does not support editing a YouTube playlist" }
                session.accountRead(revision) {
                    check(YtMusicRepository.playlistOwned(page.browseId).getOrThrow() == true) { "This playlist is not owned by the active YouTube profile" }
                    YtMusicRepository.playlistEntries(page.browseId).getOrThrow()
                }
            }
            session.requireListener(revision)
            original = playlistEditEntries(songs, remote = !local)
            order.addAll(original)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not load the complete playlist"; needsReload = true }
        finally { loading = false }
    }

    fun save() {
        if (saving || loading || needsReload || !changed) return
        val draft = order.toList()
        saving = true; error = null
        scope.launch {
            try {
                session.requireListener(revision)
                val saved = if (local) {
                    editedPlaylistSongs(original, draft).also {
                        session.replaceLocalPlaylistSongs(page.browseId, original.map { entry -> entry.song }, it)
                    }
                } else session.accountWrite(revision) {
                    check(YtMusicRepository.playlistOwned(page.browseId).getOrThrow() == true) { "This playlist is no longer owned by the active YouTube profile" }
                    val id = page.browseId.removePrefix("VL")
                    saveRemotePlaylistEdits(original, draft,
                        fresh = { YtMusicRepository.playlistEntries(page.browseId).getOrThrow() },
                        remove = { YtMusicRepository.removeFromPlaylist(id, it).getOrThrow() },
                        reorder = { current, target -> YtMusicRepository.reorderPlaylist(id, current, target).getOrThrow() })
                }
                session.requireListener(revision)
                session.detail.value?.takeIf { it.browseId == page.browseId }?.let {
                    session.detail.value = it.copy(songs = UiState.Success(saved), continuation = null)
                }
                if (!local) session.refreshLibrary()
                session.notice("Playlist updated")
                onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // A timed-out server edit may already have applied part of its batch.
                // Retrying uses a fresh snapshot, never the former set-video-id order.
                needsReload = true
                error = (failure.message ?: "Could not save the playlist") + " Reload the playlist before trying again."
            } finally { saving = false }
        }
    }

    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = !saving, dismissOnClickOutside = !saving,
    )) {
        Surface(Modifier.width(760.dp).heightIn(min = 440.dp, max = 700.dp), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("Edit playlist tracks", style = MaterialTheme.typography.headlineSmall)
                Text(page.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Drag the handle or use the arrows. Remove keeps other copies of the same song.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 10.dp)) }
                when {
                    loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    order.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(if (original.isEmpty()) "This playlist has no editable tracks" else "All tracks will be removed when you save")
                    }
                    else -> EditablePlaylistRows(order, enabled = !saving && !needsReload, modifier = Modifier.weight(1f))
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${order.size} tracks", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { reload++ }, enabled = !saving && !loading) { Text("Reload") }
                    TextButton(onClick = { order.clear(); order.addAll(original) }, enabled = changed && !saving && !needsReload) { Text("Reset") }
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                    Button(onClick = ::save, enabled = changed && !loading && !saving && !needsReload && liveRevision == revision) {
                        Text(if (saving) "Saving…" else "Save changes")
                    }
                }
            }
        }
    }
}

/** Mobile's keyed drag/edge-scroll algorithm with desktop arrow and exact-occurrence remove actions. */
@Composable
private fun EditablePlaylistRows(order: MutableList<PlaylistEditEntry>, enabled: Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    var held by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var autoScroll by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val edgeZone = with(density) { 56.dp.toPx() }
    val edgeSpeed = with(density) { 900.dp.toPx() }
    fun settle() {
        val key = held ?: return
        val items = listState.layoutInfo.visibleItemsInfo
        val dragged = items.firstOrNull { it.key == key } ?: return
        val top = dragged.offset + offset
        val center = top + dragged.size / 2f
        val target = items.firstOrNull { it.key != key && it.index < order.size && center >= it.offset && center <= it.offset + it.size }
        if (target != null) {
            val from = dragged.index
            if (from !in order.indices || target.index !in order.indices) return
            val first = listState.firstVisibleItemIndex
            val firstOffset = listState.firstVisibleItemScrollOffset
            order.add(target.index, order.removeAt(from))
            if (from == first || target.index == first) listState.requestScrollToItem(first, firstOffset)
            offset -= (target.offset - dragged.offset).toFloat()
        }
        val viewport = listState.layoutInfo
        autoScroll = when {
            top < viewport.viewportStartOffset + edgeZone && listState.canScrollBackward -> -edgeSpeed
            top + dragged.size > viewport.viewportEndOffset - edgeZone && listState.canScrollForward -> edgeSpeed
            else -> 0f
        }
    }
    fun end() { held = null; offset = 0f; autoScroll = 0f }
    fun move(key: String, delta: Int) {
        if (!enabled) return
        val from = order.indexOfFirst { it.key == key }; val to = from + delta
        if (from in order.indices && to in order.indices) order.add(to, order.removeAt(from))
    }
    LaunchedEffect(enabled) { if (!enabled) end() }
    LaunchedEffect(autoScroll != 0f) {
        if (autoScroll == 0f) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (held != null && autoScroll != 0f) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(1f / 30f)
            previous = now
            val scrolled = listState.scrollBy(autoScroll * seconds)
            if (scrolled == 0f) break
            offset += scrolled; settle()
        }
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        itemsIndexed(order, key = { _, entry -> entry.key }) { index, entry ->
            val dragging = held == entry.key
            fun start() { held = entry.key; offset = 0f }
            fun drag(delta: Float) { offset += delta; settle() }
            Row(Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { translationY = if (dragging) offset else 0f }
                .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                .pointerInput(enabled, entry.key) {
                    if (enabled) detectDragGesturesAfterLongPress(onDragStart = { start() }, onDragEnd = ::end, onDragCancel = ::end,
                        onDrag = { change, amount -> change.consume(); drag(amount.y) })
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DragHandle, "Drag to reorder", Modifier.size(38.dp).pointerInput(enabled, entry.key) {
                    if (enabled) detectDragGestures(onDragStart = { start() }, onDragEnd = ::end, onDragCancel = ::end,
                        onDrag = { change, amount -> change.consume(); drag(amount.y) })
                })
                AsyncImage(entry.song.thumbnailUrl, null, Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(entry.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(entry.song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { move(entry.key, -1) }, enabled = enabled && index > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                IconButton(onClick = { move(entry.key, 1) }, enabled = enabled && index < order.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                IconButton(onClick = { if (enabled) order.indexOfFirst { it.key == entry.key }.takeIf { it >= 0 }?.let { order.removeAt(it) } }, enabled = enabled) {
                    Icon(Icons.Rounded.RemoveCircleOutline, "Remove this occurrence")
                }
            }
        }
        item(key = "editor-footer") { Spacer(Modifier.height(12.dp)) }
    }
}
