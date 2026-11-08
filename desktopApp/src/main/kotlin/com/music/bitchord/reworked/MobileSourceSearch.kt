package com.music.bitchord.reworked

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.*
import com.music.bitchord.ui.screens.CompactTrackRow

/** Desktop focus and keyboard adapter for the mobile Sources search tab. */
@Composable
internal fun MobileSourceSearch(session: MobileSession, onMenu: (Song) -> Unit) {
    val query by session.query.collectAsState()
    val results by session.searchResults.collectAsState()
    val playback by session.player.state.collectAsState()
    val errors by MobileSources.errors.collectAsState()
    val sources by MobileSources.sources.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { session.query.value = it }, singleLine = true, label = { Text("Search enabled music sources") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { session.search() }), modifier = Modifier.weight(1f))
            Button(onClick = { session.search() }, enabled = query.isNotBlank()) { Text("Search") }
        }
        Text(sources.filter { it.enabled }.joinToString(" · ") { it.name }.ifBlank { "Add a music source in Settings to search it." }, style = MaterialTheme.typography.bodySmall)
        when (val answer = results) {
            UiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is UiState.Error -> Text(answer.message, color = MaterialTheme.colorScheme.error)
            is UiState.Success -> {
                val songs = answer.data.filterIsInstance<SearchResult.Track>().map { it.song }
                if (songs.isEmpty()) Text("No tracks found in the enabled sources.")
                LazyColumn(Modifier.weight(1f)) {
                    items(songs, key = Song::videoId) { song ->
                        CompactTrackRow(song.title, song.artist, song.thumbnailUrl, { session.player.playSongs(songs, songs.indexOf(song)) },
                            onLongPress = { onMenu(song) }, isCurrent = playback.song?.videoId == song.videoId, isPlaying = playback.isPlaying)
                    }
                }
            }
            null -> Text("Source results keep their provider identity for playback and downloads.")
        }
        errors.values.take(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
