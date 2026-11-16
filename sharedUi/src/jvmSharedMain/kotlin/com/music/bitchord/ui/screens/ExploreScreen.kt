package com.music.bitchord.ui.screens

import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.ShimmerBox
import com.music.bitchord.ui.components.feedSkeleton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    state: UiState<List<MoodGenreSection>>,
    listState: LazyListState,
    onCategoryClick: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** The big "Explore" heading; the desktop's pages carry none. */
    showTitle: Boolean = true,
    /** Render each server-provided category section with its own heading. */
    showSectionHeadings: Boolean = false,
    /** Keep the current v1.8 card treatment by default; desktop users may opt into v1.7. */
    useOldCards: Boolean = false,
) {
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compactHeadings = LocalDesktopCompactHeadings.current
            val columns = moodColumns(maxWidth)
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize()
                    .then(if (compactHeadings) Modifier.desktopTopScrollFade(listState) else Modifier),
            ) {
                if (showTitle) {
                    item {
                        Text(
                            text = stringResource(Res.string.explore),
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            // 14dp under the title, the same gap Library leaves
                            // above its first card (8dp title + 6dp Replay row).
                            modifier = Modifier.padding(
                                start = PAGE_GUTTER,
                                end = PAGE_GUTTER,
                                top = 8.dp,
                                bottom = 14.dp,
                            ),
                        )
                    }
                }
                when (state) {
                    UiState.Loading -> item { ExploreSkeletonRows(columns) }
                    is UiState.Error -> item {
                        MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                    }
                    is UiState.Success -> {
                        if (showSectionHeadings) {
                            state.data.forEach { section ->
                                item(key = "mood-section:${section.title}") {
                                    Text(
                                        text = section.title,
                                        style = if (compactHeadings) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                                        fontWeight = if (compactHeadings) FontWeight.Bold else FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.padding(
                                            start = PAGE_GUTTER,
                                            end = PAGE_GUTTER,
                                            top = 14.dp,
                                            bottom = 10.dp,
                                        ),
                                    )
                                }
                                val rows = section.items
                                    .distinctBy { it.browseId to it.params }
                                    .chunked(columns)
                                items(
                                    rows,
                                    key = { row -> "${section.title}:${row.first().browseId}|${row.first().params}" },
                                ) { row ->
                                    MoodGenreRow(row = row, columns = columns, onCategoryClick = onCategoryClick, useOldCards = useOldCards)
                                }
                            }
                        } else {
                            // The default Explore page keeps moods and genres in one continuous grid.
                            val rows = state.data.flatMap(MoodGenreSection::items)
                                .distinctBy { it.browseId to it.params }
                                .chunked(columns)
                            items(rows, key = { row -> row.first().let { "${it.browseId}|${it.params}" } }) { row ->
                                MoodGenreRow(row = row, columns = columns, onCategoryClick = onCategoryClick, useOldCards = useOldCards)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodGenreRow(
    row: List<MoodGenre>,
    columns: Int,
    onCategoryClick: (MoodGenre) -> Unit,
    useOldCards: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = MOOD_SPACING),
    ) {
        row.forEach { item ->
            MoodGenreCard(
                item = item,
                onClick = { onCategoryClick(item) },
                modifier = Modifier.weight(1f),
                useOldCards = useOldCards,
            )
        }
        // Short rows keep their cards the size of a full row's.
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * Both card designs share one Explore grid, category click behavior and artwork data.
 * The preference only affects drawing; switching it never reloads the Explore feed.
 */
@Composable
private fun MoodGenreCard(
    item: MoodGenre,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    useOldCards: Boolean,
) {
    if (useOldCards) {
        OldMoodGenreCard(item = item, onClick = onClick, modifier = modifier)
    } else {
        ModernMoodGenreCard(item = item, onClick = onClick, modifier = modifier)
    }
}

/**
 * v1.7-style Explore card: one uninterrupted colour gradient with a cropped,
 * tilted album sleeve in the lower-right corner. The v1.8 duotone split made
 * artwork look pasted onto a separate solid panel on desktop.
 */
@Composable
private fun OldMoodGenreCard(
    item: MoodGenre,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = moodColor(item.title)
    Box(
        modifier = modifier
            .aspectRatio(MOOD_CARD_ASPECT)
            .clip(MOOD_CARD_SHAPE)
            .background(
                Brush.linearGradient(
                    listOf(
                        color,
                        color.copy(
                            red = color.red * .68f,
                            green = color.green * .68f,
                            blue = color.blue * .68f,
                        ),
                    ),
                ),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // v1.7 used an 82dp sleeve in a 100dp card. Scale that proportion
            // with the responsive desktop card height instead of fixing dp.
            val artworkSize = (maxHeight + 24.dp) * MOOD_ARTWORK_HEIGHT_FRACTION
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 10.dp, y = 12.dp)
                    .size(artworkSize)
                    .graphicsLayer { rotationZ = 16f }
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White.copy(alpha = .22f)),
            ) {
                item.thumbnailUrl?.let { artwork ->
                    AsyncImage(
                        model = artwork,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).padding(end = 48.dp),
        )
    }
}

/** Original v1.7 palette, independent of fluctuating remote artwork colours. */
private fun moodColor(title: String): Color = when ((title.hashCode() and Int.MAX_VALUE) % 8) {
    0 -> Color(0xFFE64A19)
    1 -> Color(0xFFEC0B65)
    2 -> Color(0xFF8664AC)
    3 -> Color(0xFF6B4EFF)
    4 -> Color(0xFFBE6100)
    5 -> Color(0xFF233C78)
    6 -> Color(0xFF4D97E5)
    else -> Color(0xFFAA267E)
}

/**
 * A solid stripe down the left and the category's cover filling the rest,
 * redrawn as a duotone of the stripe's colour so every card reads as one
 * tinted sleeve rather than a photo pasted onto a swatch.
 */
@Composable
private fun ModernMoodGenreCard(
    item: MoodGenre,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = remember(item.stripeColor, item.title) { moodTone(item) }
    Box(
        modifier = modifier
            .aspectRatio(MOOD_CARD_ASPECT)
            .clip(MOOD_CARD_SHAPE)
            .background(tone.stripe)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(1f - MOOD_STRIPE_FRACTION)
                .background(tone.placeholder),
        ) {
            item.thumbnailUrl?.let { artwork ->
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = tone.duotone,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // Pale stripes (Energize's cream, Feel good's mint) would swallow
        // white type; a soft shade under the title keeps it legible.
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .24f), .62f to Color.Transparent)),
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium.copy(
                shadow = Shadow(Color.Black.copy(alpha = .35f), offset = Offset(0f, 1f), blurRadius = 6f),
            ),
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

private class MoodTone(val stripe: Color, val placeholder: Color, val duotone: ColorFilter)

private fun moodTone(item: MoodGenre): MoodTone {
    val base = item.stripeColor?.let { Color(it.toInt()) } ?: fallbackMoodColor(item.title)
    // YouTube's palette is already pastel; a touch of black keeps it from
    // glowing against the black page.
    val stripe = lerp(base, Color.Black, .08f)
    val shadow = lerp(base, Color.Black, .68f)
    val highlight = lerp(base, Color.White, .32f)
    return MoodTone(
        stripe = stripe,
        placeholder = lerp(shadow, highlight, .45f),
        duotone = duotone(shadow, highlight),
    )
}

/**
 * Maps each pixel's luminance onto the ramp [shadow] to [highlight]. Offsets
 * are in 0..255, which Compose honours on Skia too (it rescales them).
 */
private fun duotone(shadow: Color, highlight: Color): ColorFilter {
    fun channel(from: Float, to: Float): FloatArray {
        val span = to - from
        return floatArrayOf(span * .299f, span * .587f, span * .114f, 0f, from * 255f)
    }
    return ColorFilter.colorMatrix(
        ColorMatrix(
            channel(shadow.red, highlight.red) +
                channel(shadow.green, highlight.green) +
                channel(shadow.blue, highlight.blue) +
                floatArrayOf(0f, 0f, 0f, 1f, 0f),
        ),
    )
}

/** For a button that came without YouTube's colour: muted, never neon. */
private fun fallbackMoodColor(title: String): Color = when ((title.hashCode() and Int.MAX_VALUE) % 8) {
    0 -> Color(0xFFCC6A55)
    1 -> Color(0xFFC07A92)
    2 -> Color(0xFF9C8AC0)
    3 -> Color(0xFF8090C8)
    4 -> Color(0xFFD0A060)
    5 -> Color(0xFF6A88B0)
    6 -> Color(0xFF7AAED0)
    else -> Color(0xFF86B890)
}

/** Gap between mood cards, in both directions. */
private val MOOD_SPACING = 12.dp

private val MOOD_CARD_SHAPE = RoundedCornerShape(18.dp)

/** Width over height; a two-column phone row lands at about 100dp tall. */
private const val MOOD_CARD_ASPECT = 1.72f

/** Size of the v1.7 sleeve relative to the card height (82dp / 100dp). */
private const val MOOD_ARTWORK_HEIGHT_FRACTION = .82f

/** The modern card places a solid stripe to the left of its tinted image. */
private const val MOOD_STRIPE_FRACTION = .28f

/**
 * The narrowest a mood card is let get before the row drops a column.
 *
 * Pitched so every phone still gets its two (the widest, 448dp, has room for
 * 1.8 of these), while a tablet or a window lays out as many as fit rather
 * than two cards each half a screen wide.
 */
private val MOOD_MIN_CARD_WIDTH = 220.dp

private const val MOOD_MAX_COLUMNS = 6

private fun moodColumns(available: Dp): Int {
    val row = available - PAGE_GUTTER * 2
    return ((row + MOOD_SPACING) / (MOOD_MIN_CARD_WIDTH + MOOD_SPACING)).toInt().coerceIn(2, MOOD_MAX_COLUMNS)
}

@Composable
private fun ExploreSkeletonRows(columns: Int) {
    Column(Modifier.padding(horizontal = PAGE_GUTTER)) {
        repeat(6) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
                modifier = Modifier.padding(bottom = MOOD_SPACING),
            ) {
                repeat(columns) {
                    ShimmerBox(Modifier.weight(1f).aspectRatio(MOOD_CARD_ASPECT), MOOD_CARD_SHAPE)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodGenrePlaylistsScreen(
    title: String,
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val compactHeadings = LocalDesktopCompactHeadings.current
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize()
            .then(if (compactHeadings) Modifier.desktopTopScrollFade(listState) else Modifier),
    ) {
        item {
            Text(
                text = title,
                style = if (compactHeadings) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        when (state) {
            UiState.Loading -> feedSkeleton()
            is UiState.Error -> item {
                MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
            }
            is UiState.Success -> items(state.data, key = { it.title }) { shelf ->
                Shelf(shelf = shelf, onItemClick = onItemClick)
            }
        }
    }
}
