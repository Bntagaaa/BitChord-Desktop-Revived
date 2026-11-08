package com.music.bitchord.reworked

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.*
import com.music.bitchord.ui.player.CanvasArtworkPlayer
import com.music.bitchord.ui.player.artistCredits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The classic card option consumes the same mobile mood endpoints and identities. */
@Composable
internal fun ClassicExplorePage(state: UiState<List<MoodGenreSection>>, onOpen: (MoodGenre) -> Unit, onRetry: () -> Unit) {
    when (state) {
        UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is UiState.Error -> Column(Modifier.padding(24.dp)) { Text(state.message); TextButton(onClick = onRetry) { Text("Retry") } }
        is UiState.Success -> LazyVerticalGrid(columns = GridCells.Adaptive(190.dp), contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            state.data.forEach { section ->
                item(span = { GridItemSpan(maxLineSpan) }) { Text(section.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) }
                items(section.items) { mood ->
                    Surface(Modifier.height(108.dp).clickable { onOpen(mood) }, shape = RoundedCornerShape(10.dp), tonalElevation = 3.dp) {
                        Row {
                            Box(Modifier.width(7.dp).fillMaxHeight().background(mood.stripeColor?.let(::Color) ?: ReworkedAppearance.accent))
                            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.Center) { Text(mood.title, style = MaterialTheme.typography.titleMedium, maxLines = 3) }
                        }
                    }
                }
            }
        }
    }
}

/** New desktop presentation of the mobile Canvas, lyrics, artist and queue data. */
@Composable
internal fun MobileNowPlayingDetails(session: MobileSession, onLyrics: () -> Unit, modifier: Modifier = Modifier) {
    val listener by MobileYouTubeAccounts.state.collectAsState()
    val revision = listener.revision
    val state by session.player.state.collectAsState()
    val fetchedLyrics by session.lyrics.collectAsState()
    val lyrics = fetchedLyrics?.takeIf { it.ownerId == state.song?.videoId }
    val loading by session.lyricsLoading.collectAsState()
    val song = state.song ?: return
    var canvas by remember(song.videoId, revision) { mutableStateOf<CanvasArtwork?>(session.host.cachedCanvas(song)) }
    var linked by remember(song.videoId, revision) { mutableStateOf(song) }
    var artist by remember(song.videoId, revision) { mutableStateOf<ArtistPage?>(null) }
    LaunchedEffect(song.videoId, revision) {
        if (OfflineSmoke.active) return@LaunchedEffect
        val motion = session.host.canvasFor(song)
        val details = MobileYouTubeAccounts.withListener(revision) {
            val enriched = if (song.localUri == null && song.localPath == null && !song.videoId.contains(':'))
                YtMusicRepository.trackLinks(song.videoId).onFailure { if (it is CancellationException) throw it }.getOrNull() ?: song else song
            enriched to enriched.artistId?.let {
                YtMusicRepository.artistPage(it).onFailure { error -> if (error is CancellationException) throw error }.getOrNull()
            }
        }
        currentCoroutineContext().ensureActive()
        if (revision != MobileYouTubeAccounts.state.value.revision) return@LaunchedEffect
        canvas = motion; linked = details.first; artist = details.second
    }
    Surface(modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Now playing", style = MaterialTheme.typography.titleMedium)
                Text(song.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
                Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                DetailsCard(if (canvas == null) "Artwork" else "Canvas") {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))) {
                        AsyncImage(song.thumbnailUrl, song.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        canvas?.let { CanvasArtworkPlayer(it, state.isPlaying, Modifier.fillMaxSize()) }
                    }
                }
            }
            item {
                DetailsCard("Lyrics") {
                    val lines = lyrics?.lines.orEmpty().filterNot { it.isGap }
                    val selected = lines.indexOfLast { it.timeMs <= state.positionMs + MobilePlayerSettings.lyricsOffsetMs.value }.coerceAtLeast(0)
                    if (lines.isEmpty()) Text(if (loading) "Loading lyrics…" else "No lyrics found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else lines.drop((selected-1).coerceAtLeast(0)).take(4).forEach { line ->
                        Text(line.text, style = if (line == lines.getOrNull(selected)) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                            color = if (line == lines.getOrNull(selected)) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable { session.player.seek(line.timeMs) }.padding(vertical = 5.dp))
                    }
                    TextButton(onClick = onLyrics) { Text("Open full lyrics") }
                }
            }
            item {
                DetailsCard("Credits") {
                    artistCredits(linked.artist, linked.artists, linked.artistId).forEach { credit ->
                        TextButton(onClick = { if (credit.browseId != null) session.openBrowse(BrowseItem(credit.browseId!!, credit.name, "", null, BrowseType.ARTIST)) else session.search(credit.name) }) { Text(credit.name) }
                    }
                    linked.albumName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            artist?.let { page -> item {
                DetailsCard("About the artist") {
                    AsyncImage(page.thumbnailUrl, page.name, Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                    Text(page.name ?: linked.artist, style = MaterialTheme.typography.titleMedium)
                    page.monthlyListenerCount?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    page.description?.let { Text(it, maxLines = 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                }
            } }
            state.queue.getOrNull(state.queueIndex + 1)?.let { next -> item {
                DetailsCard("Next in queue") {
                    Row(Modifier.fillMaxWidth().clickable { session.player.next() }, verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(next.thumbnailUrl, null, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
                        Column(Modifier.padding(start = 10.dp)) { Text(next.title, maxLines = 2); Text(next.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            } }
        }
    }
}

@Composable
private fun DetailsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); content() }
    }
}
