package com.music.bitchord.desktop

import com.music.bitchord.ui.screens.desktopTopScrollFade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.ArtworkHoverMode
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** The compact palette lists playable tracks and real artist targets, never invented artists. */
internal fun quickSearchEntries(results: List<SearchResult>): List<SearchResult> =
    results.filter { it !is SearchResult.Browse || it.item.type == BrowseType.ARTIST }

internal fun quickSearchEntryKey(result: SearchResult): String = when (result) {
    is SearchResult.Track -> "track:" + result.song.videoId
    is SearchResult.TopTrack -> "track:" + result.song.videoId
    is SearchResult.Browse -> "artist:" + result.item.browseId
}

/** In-window overlay, not a new native popup/window: keyboard ownership stays at the app root. */
@Composable
internal fun DesktopQuickSearch(
    controller: DesktopQuickSearchState<SearchResult>,
    focusRequester: FocusRequester,
    onSelect: (SearchResult) -> Unit,
    onDismiss: () -> Unit,
) {
    // Only register its z-order; the established Quick Search router owns Escape and all editing.
    DesktopRegisterFlyout(DesktopFlyoutLayer.QUICK_SEARCH, delegateEscape = true)
    val state by controller.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(state.request) {
        val request = state.request ?: return@LaunchedEffect
        try {
            delay(QUICK_SEARCH_DEBOUNCE_MS)
            val rows = DesktopSearchClient.searchTypeahead(request.query).getOrThrow()
            currentCoroutineContext().ensureActive()
            controller.complete(request, quickSearchEntries(rows))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            controller.fail(request, failure.message?.take(180)?.takeIf { it.isNotBlank() } ?: "Search failed. Try again.")
        }
    }
    // Hover does not change scrollRevision: stationary pointers must not fight keyboard scrolling.
    LaunchedEffect(state.scrollRevision) {
        val index = state.selectedIndex
        if (index !in state.items.indices) return@LaunchedEffect
        val layout = listState.layoutInfo
        val row = layout.visibleItemsInfo.firstOrNull { it.index == index }
        if (row == null || row.offset < layout.viewportStartOffset || row.offset + row.size > layout.viewportEndOffset) {
            listState.scrollToItem(index)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().semantics { paneTitle = "Quick Search" }) {
        val topInset = (maxHeight * 0.08f).coerceIn(16.dp, 72.dp)
        val availableHeight = (maxHeight - topInset - 16.dp).coerceAtLeast(0.dp)
        val listHeight = (availableHeight - 144.dp).coerceIn(0.dp, 560.dp)
        DesktopFlyoutBackdrop(onDismiss = onDismiss, blockScroll = true)
        DesktopFlyoutCard(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset, start = 16.dp, end = 16.dp)
                .widthIn(max = 650.dp).fillMaxWidth().heightIn(max = availableHeight),
            onBackgroundClick = { focusRequester.requestFocus() },
            frosted = true,
        ) {
            Column(Modifier.fillMaxWidth().padding(22.dp)) {
                val searchShape = RoundedCornerShape(7.dp)
                BasicTextField(
                    value = state.query,
                    onValueChange = controller::edit,
                    singleLine = true,
                    cursorBrush = SolidColor(DesktopAccent),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = DesktopForeground),
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                        .clip(searchShape)
                        .background(DesktopSubtleFill)
                        .border(1.dp, DesktopBarEdge, searchShape)
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = "Quick Search songs" },
                    decorationBox = { innerField ->
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(BitChordIcons.Search, contentDescription = null, tint = DesktopSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.weight(1f)) {
                                if (state.query.isEmpty()) Text(
                                    "What do you want to play?", color = DesktopSecondary,
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                innerField()
                            }
                        }
                    },
                )
                if (state.query.isNotBlank()) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QuickSearchKeycap("\u2191")
                        QuickSearchKeycap("\u2193")
                        Text("Navigate", color = DesktopSecondary, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        QuickSearchKeycap("Enter")
                        Text("Play & close", color = DesktopSecondary, fontSize = 11.sp)
                        Spacer(Modifier.width(8.dp))
                        QuickSearchKeycap("Shift")
                        QuickSearchKeycap("Enter")
                        Text("Play & stay", color = DesktopSecondary, fontSize = 11.sp)
                    }
                    if (state.loading) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = DesktopAccent, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Searching...", color = DesktopSecondary)
                        }
                    } else {
                        state.error?.let { error ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(error, Modifier.weight(1f), color = DesktopSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                if (state.items.isEmpty()) Text(
                                    "Retry", color = DesktopAccent,
                                    modifier = Modifier.padding(start = 12.dp).focusProperties { canFocus = false }
                                        .clickable(onClick = controller::retry).padding(8.dp),
                                )
                            }
                        }
                        if (state.items.isEmpty() && state.error == null) {
                            Text("No tracks or artists found", Modifier.padding(16.dp), color = DesktopSecondary)
                        }
                        if (state.items.isNotEmpty()) Box(Modifier.fillMaxWidth()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxWidth().heightIn(max = listHeight).desktopTopScrollFade(listState).padding(end = 10.dp),
                            ) {
                                itemsIndexed(state.items, key = { _, item -> quickSearchEntryKey(item) }) { index, item ->
                                    QuickSearchRow(
                                        item = item,
                                        active = state.selectedIndex == index,
                                        onHover = { controller.hover(index) },
                                        onSelect = { if (controller.canPlay(item)) onSelect(item) },
                                    )
                                }
                            }
                            Box(Modifier.matchParentSize()) {
                                VerticalScrollbar(
                                    adapter = rememberScrollbarAdapter(listState),
                                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp)
                                        .focusProperties { canFocus = false },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun QuickSearchRow(item: SearchResult, active: Boolean, onHover: () -> Unit, onSelect: () -> Unit) {
    val song = when (item) {
        is SearchResult.TopTrack -> item.song
        is SearchResult.Track -> item.song
        is SearchResult.Browse -> null
    }
    val artist = (item as? SearchResult.Browse)?.item
    val activeBackground by animateColorAsState(
        targetValue = if (active) DesktopForeground.copy(alpha = 0.09f) else Color.Transparent,
        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
        label = "quick-search-active-background",
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
            .background(activeBackground, RoundedCornerShape(5.dp))
            .semantics { selected = active }
            .focusProperties { canFocus = false }
            // Move, rather than Enter, avoids changing selection merely because keyboard scroll
            // places another row underneath a stationary pointer.
            .onPointerEvent(PointerEventType.Move) { onHover() }
            .desktopNoHoverClickable(hoverHighlight = false, onClick = onSelect).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopArtwork(
            song?.thumbnailUrl ?: artist?.thumbnailUrl,
            Modifier.size(40.dp).clip(if (artist != null) CircleShape else RoundedCornerShape(5.dp)),
            px = ROW_ART_PX,
            hoverMode = if (song != null) ArtworkHoverMode.DARKEN_AND_PLAY else ArtworkHoverMode.DARKEN_ONLY,
            hovered = active,
        )
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(song?.title ?: artist?.title.orEmpty(), color = DesktopForeground, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(song?.artist ?: artist?.subtitle.orEmpty(), color = DesktopSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        Text(if (artist != null) "Artist" else "Track", color = DesktopSecondary, fontSize = 11.sp,
            modifier = Modifier.padding(start = 10.dp).background(DesktopForeground.copy(alpha = 0.08f), RoundedCornerShape(3.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp))
    }
}

@Composable
private fun QuickSearchKeycap(label: String) {
    Text(label, color = DesktopSecondary, fontSize = 11.sp,
        modifier = Modifier.border(1.dp, DesktopSecondary.copy(alpha = 0.65f), RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp))
}
