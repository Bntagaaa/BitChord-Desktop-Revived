package com.music.bitchord.reworked

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Five mixed upstream results, with every action routed into the existing app queue/navigation. */
@Composable
internal fun ReworkedQuickSearch(
    onDismiss: () -> Unit,
    onPlay: (Song) -> Unit,
    onBrowse: (BrowseItem) -> Unit,
    onFullSearch: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var selected by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        rows = emptyList(); selected = 0; error = null
        if (query.isBlank()) { loading = false; return@LaunchedEffect }
        loading = true
        delay(250)
        try {
            com.music.bitchord.data.YtMusicRepository.search(query, SearchFilter.ALL).fold(
                onSuccess = { results ->
                    val first = results.take(5).toMutableList()
                    val playlist = results.firstOrNull { it is SearchResult.Browse && it.item.type == BrowseType.PLAYLIST }
                    if (playlist != null && playlist !in first) { if (first.size == 5) first.removeLast(); first += playlist }
                    rows = first
                },
                onFailure = { if (it is CancellationException) throw it else error = it.message ?: "Search failed" },
            )
        } finally { loading = false }
    }
    fun activate(row: SearchResult) {
        onDismiss()
        when (row) {
            is SearchResult.TopTrack -> onPlay(row.song)
            is SearchResult.Track -> onPlay(row.song)
            is SearchResult.Browse -> onBrowse(row.item)
            else -> onFullSearch(query)
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
            Column(Modifier.width(570.dp).padding(16.dp).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> { onDismiss(); true }
                    Key.DirectionDown -> { selected = (selected + 1).coerceAtMost(rows.size); true }
                    Key.DirectionUp -> { selected = (selected - 1).coerceAtLeast(0); true }
                    Key.Enter -> {
                        val row = if (event.isShiftPressed) rows.firstOrNull { it is SearchResult.Track || it is SearchResult.TopTrack } else rows.getOrNull(selected)
                        if (row != null) activate(row) else if (query.isNotBlank()) { onDismiss(); onFullSearch(query) }
                        true
                    }
                    else -> false
                }
            }) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    placeholder = { Text("Search songs, artists, albums and playlists") }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
                Spacer(Modifier.height(10.dp))
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
                rows.forEachIndexed { index, result ->
                    val song = (result as? SearchResult.Track)?.song ?: (result as? SearchResult.TopTrack)?.song
                    val item = (result as? SearchResult.Browse)?.item
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (selected == index) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .clickable { activate(result) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(song?.thumbnailUrl ?: item?.thumbnailUrl, null, Modifier.size(38.dp).clip(RoundedCornerShape(5.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song?.title ?: item?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song?.artist ?: item?.subtitle.orEmpty(), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (!loading && query.isNotBlank() && rows.isEmpty() && error == null) Text("No results", Modifier.padding(8.dp))
                TextButton(onClick = { onDismiss(); onFullSearch(query) }, enabled = query.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Search all results  ·  Enter")
                }
                Text("↑ ↓ Choose    Shift+Enter Play first song    Esc Close", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
