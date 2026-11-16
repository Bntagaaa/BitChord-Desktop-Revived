package com.music.bitchord.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.screens.desktopTopScrollFade

/** Full grid opened by a shelf's chevron; playlist see-all targets use the collection page instead. */
@Composable
internal fun DesktopShelfBrowsePage(
    title: String,
    state: UiState<List<HomeShelf>>,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val shelfItems = (state as? UiState.Success)?.data.orEmpty()
        .flatMap(HomeShelf::items)
        .distinctBy { it.browseId ?: it.videoId ?: "${it.title}\n${it.subtitle}" }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 176.dp),
        state = gridState,
        modifier = modifier.fillMaxSize().desktopTopScrollFade(gridState),
        contentPadding = PaddingValues(
            start = 24.dp,
            top = 20.dp,
            end = 24.dp,
            bottom = contentPadding.calculateBottomPadding() + 30.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "browse-title") {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 4.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when (state) {
            is UiState.Loading -> item(span = { GridItemSpan(maxLineSpan) }, key = "browse-loading") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading more music...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            is UiState.Error -> item(span = { GridItemSpan(maxLineSpan) }, key = "browse-error") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRetry) { Text("Try again") }
                }
            }

            is UiState.Success -> {
                if (shelfItems.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "browse-empty") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Nothing here yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onRetry) { Text("Refresh") }
                        }
                    }
                } else {
                    items(
                        items = shelfItems,
                        key = { it.browseId ?: it.videoId ?: "${it.title}-${it.subtitle}" },
                    ) { item ->
                        DesktopBrowseGridCard(
                            item = item,
                            onClick = { onItemClick(item) },
                            onLongPress = onItemLongPress?.let { callback -> { callback(item) } },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopBrowseGridCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val video = item.videoId != null && item.browseId == null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .combinedClickable(onClick = onClick, onLongClick = { onLongPress?.invoke() }),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (video) 16f / 9f else 1f)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        ) {
            AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = item.title,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                text = item.subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
