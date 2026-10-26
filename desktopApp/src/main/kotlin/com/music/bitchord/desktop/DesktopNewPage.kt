package com.music.bitchord.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.components.ArtworkHover
import com.music.bitchord.ui.components.ArtworkHoverMode
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.screens.CompactTrackRow
import com.music.bitchord.ui.screens.LocalDesktopCompactHeadings
import com.music.bitchord.ui.icons.BitChordIcons

/**
 * The desktop version of YouTube Music's New page: new releases, a compact
 * multi-column trending list, and wide music-video artwork. It intentionally
 * uses the desktop theme and existing shelf data instead of embedding the web UI.
 */
@Composable
internal fun DesktopNewPage(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    onRetry: () -> Unit,
    onOpenMore: (HomeShelf) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val shelves = (state as? UiState.Success)?.data.orEmpty().filter { it.items.isNotEmpty() }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item(key = "new-page-title") {
            Text(
                text = "New",
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                style = if (LocalDesktopCompactHeadings.current) {
                    MaterialTheme.typography.headlineLarge
                } else {
                    MaterialTheme.typography.displayLarge
                },
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
        }

        when (state) {
            is UiState.Loading -> item(key = "new-loading") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PAGE_GUTTER, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Finding new music...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            is UiState.Error -> item(key = "new-error") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PAGE_GUTTER, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRetry) { Text("Try again") }
                }
            }

            is UiState.Success -> {
                if (shelves.isEmpty()) {
                    item(key = "new-empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PAGE_GUTTER, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "Nothing new to show right now.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onRetry) { Text("Refresh") }
                        }
                    }
                } else {
                    itemsIndexed(
                        items = shelves,
                        key = { index, shelf -> "${shelf.title}-$index" },
                    ) { _, shelf ->
                        when {
                            shelf.title.contains("trending", ignoreCase = true) ||
                                shelf.moreBrowseId?.startsWith("VL") == true ->
                                NewTrendingShelf(
                                    shelf = shelf,
                                    onItemClick = onItemClick,
                                    onItemLongPress = onItemLongPress,
                                    onOpenMore = onOpenMore,
                                )

                            shelf.title.contains("video", ignoreCase = true) ||
                                shelf.moreBrowseId == "FEmusic_new_releases_videos" ->
                                NewVideoShelf(
                                    shelf = shelf,
                                    onItemClick = onItemClick,
                                    onItemLongPress = onItemLongPress,
                                    onOpenMore = onOpenMore,
                                )

                            else -> NewAlbumShelf(
                                shelf = shelf,
                                onItemClick = onItemClick,
                                onItemLongPress = onItemLongPress,
                                onOpenMore = onOpenMore,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewSectionHeader(
    shelf: HomeShelf,
    onOpenMore: (HomeShelf) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
            .combinedClickable(
                enabled = shelf.moreBrowseId != null,
                onClick = { onOpenMore(shelf) },
                onLongClick = {},
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = shelf.title,
            style = if (LocalDesktopCompactHeadings.current) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.headlineMedium
            },
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (shelf.moreBrowseId != null) {
            Icon(
                imageVector = BitChordIcons.ChevronRight,
                contentDescription = "Open ${shelf.title}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewAlbumShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    onOpenMore: (HomeShelf) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
    ) {
        NewSectionHeader(shelf, onOpenMore)
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(
                items = shelf.items,
                key = { it.browseId ?: it.videoId ?: "${it.title}-${it.subtitle}" },
            ) { item ->
                val hoverInteraction = remember { MutableInteractionSource() }
                val hovered by hoverInteraction.collectIsHoveredAsState()
                Column(
                    modifier = Modifier
                        .width(208.dp)
                        .hoverable(hoverInteraction)
                        .combinedClickable(
                            interactionSource = hoverInteraction,
                            indication = null,
                            onClick = { onItemClick(item, shelf.title) },
                            onLongClick = { onItemLongPress?.invoke(item) },
                        ),
                ) {
                    ArtworkHover(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        shape = RoundedCornerShape(12.dp),
                        mode = ArtworkHoverMode.DARKEN_ONLY,
                        hovered = hovered,
                    ) {
                        AsyncImage(
                            model = item.thumbnailUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = item.title,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewTrendingShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    onOpenMore: (HomeShelf) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
    ) {
        NewSectionHeader(shelf, onOpenMore)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER),
        ) {
            val columnCount = (maxWidth / 290.dp).toInt().coerceIn(1, minOf(4, shelf.items.size))
            val rowsPerColumn = (shelf.items.size + columnCount - 1) / columnCount
            val columns = shelf.items.chunked(rowsPerColumn)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                columns.forEach { columnItems ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        columnItems.forEach { item ->
                            CompactTrackRow(
                                title = item.title,
                                subtitle = item.subtitle,
                                thumbnailUrl = item.thumbnailUrl,
                                onClick = { onItemClick(item, shelf.title) },
                                onLongPress = onItemLongPress?.let { callback -> { callback(item) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewVideoShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    onOpenMore: (HomeShelf) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 22.dp),
    ) {
        NewSectionHeader(shelf, onOpenMore)
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(
                items = shelf.items,
                key = { it.browseId ?: it.videoId ?: "${it.title}-${it.subtitle}" },
            ) { item ->
                val hoverInteraction = remember { MutableInteractionSource() }
                val hovered by hoverInteraction.collectIsHoveredAsState()
                Column(
                    modifier = Modifier
                        .width(260.dp)
                        .hoverable(hoverInteraction)
                        .combinedClickable(
                            interactionSource = hoverInteraction,
                            indication = null,
                            onClick = { onItemClick(item, shelf.title) },
                            onLongClick = { onItemLongPress?.invoke(item) },
                        ),
                ) {
                    ArtworkHover(
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        shape = RoundedCornerShape(12.dp),
                        mode = ArtworkHoverMode.DARKEN_ONLY,
                        hovered = hovered,
                    ) {
                        AsyncImage(
                            model = item.thumbnailUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = item.title,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
