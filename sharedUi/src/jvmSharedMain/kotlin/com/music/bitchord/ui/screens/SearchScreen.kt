package com.music.bitchord.ui.screens

import com.music.bitchord.ui.icons.BitChordIcons

import com.music.bitchord.ui.icons.SfStyleIcons

import com.music.bitchord.ui.components.PlayingAccent


import com.music.bitchord.ui.components.trackArtSize
import androidx.compose.ui.layout.ContentScale

import com.music.bitchord.ui.components.contextClick
import androidx.compose.ui.unit.Dp
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import com.music.bitchord.ui.components.longPressMenuClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.music.bitchord.ui.components.RetryableAsyncImage
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isSameTrackAs
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ArtworkHover
import com.music.bitchord.ui.components.ArtworkHoverMode
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.components.ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.SearchField
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.SearchPlayingBars
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.songListSkeleton
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: SearchFilter,
    currentSong: Song?,
    isPlaying: Boolean,
    onFilterChange: (SearchFilter) -> Unit,
    results: UiState<List<SearchResult>>?,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    listState: LazyListState,
    scrollResetTrigger: Int,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onTopResultPlay: (Song) -> Unit,
    onTopResultPlaylist: (Song) -> Unit,
    onBrowseClick: (BrowseItem) -> Unit,
    /**
     * Holding an album or playlist hit rather than tapping it — the same menu
     * the shelves open, so a release found by searching can go on the queue
     * without a trip through its page.
     */
    onBrowseLongPress: ((BrowseItem) -> Unit)? = null,
    history: List<SearchHistoryEntity>,
    suggestions: List<String>,
    typeaheadResults: List<SearchResult>,
    onSubmit: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onHistoryClick: (SearchHistoryEntity) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    /** Long-press handler for typeahead rows — opens the song actions sheet. */
    onTypeaheadLongPress: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    /**
     * Room above the search field. The phone's frosted bar floats over the top
     * of the tab, so the field has to clear it; the desktop has no such bar.
     */
    topPadding: Dp,
    /**
     * Whether the page draws its own search field. The desktop's sidebar has
     * the one search box the window needs, so the page there is results only.
     */
    showField: Boolean = true,
    /**
     * Whether the page is searching the device's Local Music folder instead of
     * YouTube. The phone's top bar flips this; the desktop never does.
     */
    searchingLibrary: Boolean = false,
    /** The Library source's tracks for [query]; null while nothing is typed. */
    libraryResults: UiState<List<Song>>? = null,
    /** Files on the device are a running order to play through, not a station seed. */
    onLibrarySongClick: (List<Song>, Int) -> Unit = onSongClick,
    /**
     * The YouTube / Library switcher, full width at the head of the page. Up
     * only while the field is empty: once something is typed the page is
     * about the results, and the switcher would just push them down.
     */
    sourceSwitcher: (@Composable () -> Unit)? = null,
    /** Desktop can keep its own field implementation while sharing the stable page state. */
    searchField: (@Composable (FocusRequester) -> Unit)? = null,
    /** Keep the browse landing mounted while editing, so the input never loses focus on empty/non-empty transitions. */
    showLanding: Boolean = false,
    landingContent: (@Composable () -> Unit)? = null,
    /** Capture only the landing, excluding its sibling field and suggestion surface. */
    landingContentModifier: Modifier = Modifier,
    /** Desktop supplies its frosted/solid surface, following the existing Remove Blur switch. */
    landingPanelDecoration: Modifier = Modifier,
    landingPanelColor: Color = Color(0xFF27272B),
    /** Transparent desktop glass must not cast a Surface shadow over its own blur. */
    landingPanelShadowElevation: Dp = 12.dp,
    /** Desktop owns dismissal across the input and result rows, including focus transfers. */
    landingSuggestionsEnabled: Boolean = true,
    /** Observes presses on the browse landing without consuming category clicks or scrolling. */
    onLandingOutsidePress: (() -> Unit)? = null,
) {
    val currentOnLandingOutsidePress by rememberUpdatedState(onLandingOutsidePress)
    // Observe touches, but do not consume them: the same press can click a mood
    // card, or merely defocus the input when it lands beside the search pill.
    val outsidePressObserver = if (onLandingOutsidePress != null) Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            currentOnLandingOutsidePress?.invoke()
        }
    } else Modifier
    val suggestionScrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Tapping the search tab from the nav bar sets focusRequested;
    // respond by focusing the field and opening the keyboard.
    LaunchedEffect(focusRequested) {
        if (focusRequested && showField) {
            focusRequester.requestFocus()
            keyboardController?.show()
            onFocusHandled()
        }
    }
    // Search keeps one list state while its contents change. Reset it for each
    // new request so choosing a recent search cannot inherit the history's
    // previous scroll position (or a previous result page's position).
    LaunchedEffect(scrollResetTrigger) {
        if (scrollResetTrigger > 0) listState.scrollToItem(0)
    }
    // A non-empty suggestion list means the field is mid-edit — see
    // MainViewModel.suggestions. Nothing below it is worth showing while it is
    // up: the results are for whatever was searched before this edit began,
    // and so are the filter tabs above them.
    val suggesting = suggestions.isNotEmpty() && !searchingLibrary &&
        (!showLanding || landingSuggestionsEnabled)
    // Live media results arrive from the parallel typeahead pipeline; show
    // them only while the user is still typing (suggestions visible), so they
    // appear as a dropdown beneath the text completions rather than floating
    // after the search has committed.
    val showTypeahead = typeaheadResults.isNotEmpty() && suggesting
    LaunchedEffect(listState, results, loadingMore, searchingLibrary) {
        if (results !is UiState.Success || searchingLibrary) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (!loadingMore && total > 0 && lastVisible >= total - 4) onLoadMore()
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        // Search field and filter tabs stay fixed at the top, outside the
        // scrolling list, so they're always reachable rather than scrolling
        // away with the results or recent searches beneath them.
        // The FrostedTopBar is visible on this tab (showing "Search"), so we
        // clear it fully — status bar inset + bar height + breathing gap — so
        // the search field sits cleanly below the bar instead of overlapping it.
        Column(modifier = Modifier.padding(top = topPadding)) {
            if (showField) {
                if (searchField != null) {
                    Box(Modifier.fillMaxWidth()) {
                        // The custom desktop search slot spans the entire row,
                        // but only the centered pill should retain keyboard focus.
                        Box(Modifier.matchParentSize().then(outsidePressObserver))
                        searchField(focusRequester)
                    }
                } else {
                    SearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        onSubmit = onSubmit,
                        focusRequester = focusRequester,
                        placeholder = if (searchingLibrary) {
                            stringResource(Res.string.search_library_hint)
                        } else {
                            stringResource(Res.string.search_hint)
                        },
                        modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 4.dp),
                    )
                }
            }
            if (sourceSwitcher != null) {
                AnimatedVisibility(
                    visible = query.isEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Box(Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 4.dp)) {
                        sourceSwitcher()
                    }
                }
            }
            // The filters only mean something once there is a result set to narrow;
            // they stay up for an empty or failed search too, or picking a filter
            // that finds nothing would take away the control needed to leave it.
            // They are YouTube's categories, so the Library source has none.
            if (results != null && !suggesting && !searchingLibrary) {
                SearchFilterTabs(filter = filter, onFilterChange = onFilterChange)
            }
        }

        if (showLanding && landingContent != null) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Keep the browse landing visible and interactive even while typing.
                // Observing Initial-pass presses lets the first click reach a mood card
                // while the Search field simultaneously releases keyboard focus.
                Box(Modifier.matchParentSize().then(landingContentModifier).then(outsidePressObserver)) {
                    landingContent()
                }
                if (suggesting && query.isNotBlank() && (suggestions.size > 1 || showTypeahead)) {
                    Box(
                        Modifier.fillMaxWidth().zIndex(1f),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(
                            Modifier.widthIn(max = 540.dp).fillMaxWidth().padding(horizontal = 24.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .then(landingPanelDecoration)
                                    // Give the whole panel a hit target so gaps between suggestions
                                    // cannot click category cards behind it. Child rows keep their taps.
                                    .pointerInput(Unit) {
                                        awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }
                                    },
                                shape = RoundedCornerShape(14.dp),
                                color = landingPanelColor,
                                shadowElevation = landingPanelShadowElevation,
                            ) {
                            Column(
                                Modifier
                                    .heightIn(max = 420.dp)
                                    .then(if (LocalDesktopCompactHeadings.current) Modifier.desktopTopScrollFade(suggestionScrollState) else Modifier)
                                    .verticalScroll(suggestionScrollState)
                                    .padding(vertical = 6.dp),
                            ) {
                                suggestions.drop(1).take(3).forEach { term ->
                                    SuggestionRow(
                                        term = term,
                                        isQueryAction = false,
                                        onFill = { onQueryChange(term) },
                                        onClick = {
                                            onSuggestionClick(term)
                                            focusManager.clearFocus()
                                        },
                                    )
                                }
                                if (showTypeahead) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    )
                                    typeaheadResults.take(5).forEach { result ->
                                        when (result) {
                                            is SearchResult.Track -> TypeaheadSongRow(
                                                song = result.song,
                                                onClick = { onTopResultPlay(result.song) },
                                                onLongPress = onTypeaheadLongPress?.let { { it(result.song) } },
                                                isCurrent = result.song.isSameTrackAs(currentSong),
                                                isPlaying = isPlaying && result.song.isSameTrackAs(currentSong),
                                            )
                                            is SearchResult.Browse -> BrowseRow(
                                                item = result.item,
                                                onClick = { onBrowseClick(result.item) },
                                            )
                                            is SearchResult.TopTrack -> TypeaheadSongRow(
                                                song = result.song,
                                                onClick = { onTopResultPlay(result.song) },
                                                onLongPress = onTypeaheadLongPress?.let { { it(result.song) } },
                                                isCurrent = result.song.isSameTrackAs(currentSong),
                                                isPlaying = isPlaying && result.song.isSameTrackAs(currentSong),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                }
            }
        } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
                .then(if (LocalDesktopCompactHeadings.current) Modifier.desktopTopScrollFade(listState) else Modifier),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            when {
                searchingLibrary -> librarySearchResults(
                    query = query.trim(),
                    results = libraryResults,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onSongClick = onLibrarySongClick,
                    onSongLongPress = onSongLongPress,
                    onSongSwipe = onSongSwipe,
                )
                suggesting -> {
                    searchSuggestions(
                        suggestions = suggestions,
                        // Picking one is done typing, so the keyboard comes down
                        // with it and the results get the whole screen.
                        onClick = { term ->
                            onSuggestionClick(term)
                            focusManager.clearFocus()
                        },
                        onFill = onQueryChange,
                    )
                    if (showTypeahead) {
                        searchTypeaheadDropdown(
                            typeaheadResults = typeaheadResults,
                            onSongClick = { song -> onTopResultPlay(song) },
                            onSongLongPress = onTypeaheadLongPress,
                            onBrowseClick = { item ->
                                onBrowseClick(item)
                            },
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                        )
                    }
                }
                results == null -> if (history.isEmpty()) {
                    item { MessageState(stringResource(Res.string.search_empty)) }
                } else {
                    recentSearches(
                        history = history,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        onClick = onHistoryClick,
                        onRemove = onHistoryRemove,
                        onClear = onHistoryClear,
                    )
                }
                results is UiState.Loading -> songListSkeleton(circular = filter == SearchFilter.ARTISTS)
                results is UiState.Error -> item { MessageState(results.message) }
                results is UiState.Success -> {
                    val tracks = results.data
                        .mapNotNull { row -> when (row) {
                            is SearchResult.TopTrack -> row.song
                            is SearchResult.Track -> row.song
                            is SearchResult.Browse -> null
                            else -> null
                        } }
                    val topResult = results.data.filterIsInstance<SearchResult.TopTrack>().firstOrNull()
                    if (filter == SearchFilter.ALL) {
                        val allSongs = results.data.filterIsInstance<SearchResult.Track>().map { it.song }
                        val featuredSong = topResult?.song ?: allSongs.firstOrNull()
                        if (featuredSong != null || allSongs.isNotEmpty()) {
                            item(key = "search:all-featured") {
                                SearchAllFeatured(
                                    topSong = featuredSong,
                                    songs = allSongs,
                                    tracks = tracks,
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                    onTopResultPlay = onTopResultPlay,
                                    onTopResultPlaylist = onTopResultPlaylist,
                                    onSongClick = onSongClick,
                                    onSongLongPress = onSongLongPress,
                                    onSongSwipe = onSongSwipe,
                                )
                            }
                        }
                    }
                    searchSections(results.data, filter).forEach { section ->
                        // All has a compact Songs preview beside its promoted result;
                        // the dedicated Songs tab keeps its original complete list.
                        if (filter == SearchFilter.ALL && section.title == "Songs") return@forEach
                        val browseGrid = section.rows.all {
                            it is SearchResult.Browse && it.item.type in
                                setOf(BrowseType.ARTIST, BrowseType.ALBUM, BrowseType.PLAYLIST)
                        }
                        if (browseGrid && section.rows.isNotEmpty()) {
                            val browseItems = section.rows.filterIsInstance<SearchResult.Browse>().map { it.item }
                            if (filter == SearchFilter.ALL) {
                                item(key = "search-shelf:${section.title}") {
                                    SearchBrowseShelf(
                                        title = section.title.orEmpty(),
                                        items = browseItems,
                                        onBrowseClick = onBrowseClick,
                                        onBrowseLongPress = onBrowseLongPress,
                                    )
                                }
                            } else {
                                section.title?.let { title ->
                                    item(key = "search-section:$title") {
                                        SearchSectionHeading(title)
                                    }
                                }
                                item(key = "search-grid:${section.title ?: filter.label}") {
                                    SearchBrowseGrid(
                                        items = browseItems,
                                        onBrowseClick = onBrowseClick,
                                        onBrowseLongPress = onBrowseLongPress,
                                    )
                                }
                            }
                        } else {
                            section.title?.let { title ->
                                item(key = "search-section:$title") {
                                    SearchSectionHeading(title)
                                }
                            }
                            itemsIndexed(
                                items = section.rows,
                                key = { index, row ->
                                    when (row) {
                                        is SearchResult.TopTrack -> "top_${row.song.videoId}_$index"
                                        is SearchResult.Track -> "track_${row.song.videoId}_$index"
                                        is SearchResult.Browse -> "browse_${row.item.browseId}_$index"
                                    }
                                },
                            ) { index, row ->
                                when (row) {
                                    is SearchResult.TopTrack -> Unit
                                    is SearchResult.Track -> SongRow(
                                        song = row.song,
                                        onClick = {
                                            onSongClick(tracks, tracks.indexOf(row.song).coerceAtLeast(0))
                                        },
                                        onLongPress = { onSongLongPress(row.song) },
                                        onSwipeToQueue = { onSongSwipe(row.song) },
                                        isCurrent = row.song.isSameTrackAs(currentSong),
                                        isPlaying = isPlaying && row.song.isSameTrackAs(currentSong),
                                        searchPlayingStyle = true,
                                        landscapeArtwork = filter == SearchFilter.VIDEOS,
                                    )
                                    is SearchResult.Browse -> BrowseRow(
                                        item = row.item,
                                        onClick = { onBrowseClick(row.item) },
                                        onLongPress = onBrowseLongPress?.let { { it(row.item) } },
                                    )
                                }
                                if (index < section.rows.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                    if (loadingMore) {
                        songListSkeleton(
                            count = 3,
                            keyPrefix = "skeleton:search:more",
                            circular = filter == SearchFilter.ARTISTS,
                        )
                    }
                }
            }
        }
        }
    }
}

private data class SearchSection(val title: String?, val rows: List<SearchResult>)

/** The unfiltered page is useful only when its mixed result types are readable at a glance. */
private fun searchSections(rows: List<SearchResult>, filter: SearchFilter): List<SearchSection> {
    if (filter != SearchFilter.ALL) return listOf(SearchSection(null, rows))
    return listOf(
        SearchSection("Songs", rows.filterIsInstance<SearchResult.Track>()),
        SearchSection("Artists", rows.filterIsInstance<SearchResult.Browse>().filter { it.item.type == BrowseType.ARTIST }),
        SearchSection("Albums", rows.filterIsInstance<SearchResult.Browse>().filter { it.item.type == BrowseType.ALBUM }),
        SearchSection("Playlists", rows.filterIsInstance<SearchResult.Browse>().filter { it.item.type == BrowseType.PLAYLIST }),
        SearchSection("More", rows.filterIsInstance<SearchResult.Browse>().filter { it.item.type == BrowseType.OTHER }),
    ).filter { it.rows.isNotEmpty() }
}

/**
 * The Library source's page: the Local Music folder's tracks that match, as
 * plain song rows. An artist or album hit needs no row of its own — every
 * track under that name matches too, so they are already here.
 */
private fun LazyListScope.librarySearchResults(
    query: String,
    results: UiState<List<Song>>?,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
) {
    when (results) {
        null -> item(key = "library:empty") {
            MessageState(stringResource(Res.string.search_library_empty))
        }
        UiState.Loading -> songListSkeleton(keyPrefix = "skeleton:search:library")
        is UiState.Error -> item(key = "library:error") { MessageState(results.message) }
        is UiState.Success -> if (results.data.isEmpty()) {
            item(key = "library:no-match") {
                MessageState(stringResource(Res.string.search_library_no_results, query))
            }
        } else {
            val tracks = results.data
            itemsIndexed(tracks, key = { index, song -> "library_${song.videoId}_$index" }) { index, song ->
                SongRow(
                    song = song,
                    onClick = { onSongClick(tracks, index) },
                    onLongPress = { onSongLongPress(song) },
                    onSwipeToQueue = { onSongSwipe(song) },
                    isCurrent = song.isSameTrackAs(currentSong),
                    isPlaying = isPlaying && song.isSameTrackAs(currentSong),
                    searchPlayingStyle = true,
                    activeTint = PlayingAccent,

                )
                if (index < tracks.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * The All overview follows the reference layout: one featured result beside a
 * short Songs list on wide windows, stacked vertically at smaller widths.
 * Theme, song-row behaviors and artwork components stay native to BitChord.
 */
@Composable
private fun SearchAllFeatured(
    topSong: Song?,
    songs: List<Song>,
    tracks: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    onTopResultPlay: (Song) -> Unit,
    onTopResultPlaylist: (Song) -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER)) {
        if (maxWidth >= 720.dp && topSong != null && songs.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Top,
            ) {
                SearchTopResultSection(
                    song = topSong,
                    modifier = Modifier.weight(0.31f),
                    onPlay = { onTopResultPlay(topSong) },
                    onPlaylist = { onTopResultPlaylist(topSong) },
                    onLongPress = { onSongLongPress(topSong) },
                )
                SearchAllSongsSection(
                    songs = songs,
                    tracks = tracks,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onSongClick = onSongClick,
                    onSongLongPress = onSongLongPress,
                    onSongSwipe = onSongSwipe,
                    modifier = Modifier.weight(0.69f),
                )
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                if (topSong != null) {
                    SearchTopResultSection(
                        song = topSong,
                        onPlay = { onTopResultPlay(topSong) },
                        onPlaylist = { onTopResultPlaylist(topSong) },
                        onLongPress = { onSongLongPress(topSong) },
                    )
                }
                if (songs.isNotEmpty()) {
                    SearchAllSongsSection(
                        songs = songs,
                        tracks = tracks,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        onSongClick = onSongClick,
                        onSongLongPress = onSongLongPress,
                        onSongSwipe = onSongSwipe,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchSectionHeading(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier.padding(
            start = PAGE_GUTTER,
            end = PAGE_GUTTER,
            top = 16.dp,
            bottom = 6.dp,
        ),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SearchTopResultSection(
    song: Song,
    onPlay: () -> Unit,
    onPlaylist: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        // The column is already inset by SearchAllFeatured.
        SearchSectionHeading("Top result", Modifier.offset(x = -PAGE_GUTTER))
        TopResultCard(
            song = song,
            onPlay = onPlay,
            onPlaylist = onPlaylist,
            onLongPress = onLongPress,
        )
    }
}

@Composable
private fun SearchAllSongsSection(
    songs: List<Song>,
    tracks: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SearchSectionHeading("Songs", Modifier.offset(x = -PAGE_GUTTER))
        songs.take(4).forEachIndexed { index, song ->
            SongRow(
                song = song,
                onClick = { onSongClick(tracks, tracks.indexOf(song).coerceAtLeast(0)) },
                onLongPress = { onSongLongPress(song) },
                onSwipeToQueue = { onSongSwipe(song) },
                isCurrent = song.isSameTrackAs(currentSong),
                isPlaying = isPlaying && song.isSameTrackAs(currentSong),
                searchPlayingStyle = true,
            )
            if (index < songs.take(4).lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** The All response carries its highest-confidence music hit as a promoted card. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopResultCard(
    song: Song,
    onPlay: () -> Unit,
    onPlaylist: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlight by animateColorAsState(
        targetValue = if (hovered) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f),
        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
        label = "search-top-result-highlight",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(highlight)
            .hoverable(interaction)
            .longPressMenuClickable(onClick = onPlay, onLongClick = onLongPress)
            .contextClick(onLongPress)
            .padding(14.dp),
    ) {
        ArtworkHover(
            modifier = Modifier.size(90.dp),
            shape = RoundedCornerShape(9.dp),
            mode = ArtworkHoverMode.DARKEN_AND_PLAY,
            hovered = hovered,
        ) {
            RetryableAsyncImage(
                url = song.thumbnailUrl?.artworkAt(ROW_ART_PX),
                fallbackUrl = song.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = song.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPlaylist) {
                Icon(
                    SfStyleIcons.PlaylistAdd,
                    contentDescription = stringResource(Res.string.playlist_action),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onPlay,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(PlayingAccent),
            ) {
                Icon(
                    SfStyleIcons.Play,
                    contentDescription = stringResource(Res.string.play),
                    tint = Color.White,
                )
            }
            RowMoreButton(onClick = onLongPress)
        }
    }
}

/**
 * What YouTube would complete the half-typed query to, in place of the results
 * while it is being typed.
 *
 * The first row is the text as typed, put there by the view model rather than
 * taken from YouTube's answer, so running exactly what was asked for is always
 * the nearest row to the keyboard rather than something the thumb has to aim
 * past.
 */
private fun LazyListScope.searchSuggestions(
    suggestions: List<String>,
    onClick: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    // This is a list-level inset rather than padding hidden inside the first
    // row. It keeps the gap under the field stable even when that row changes
    // its text or icon treatment.
    item(key = "suggestions:top-inset") { Spacer(Modifier.height(8.dp)) }
    // Skip the echo of the typed text (element 0) — it's already visible in
    // the search field itself — and cap at N so the list stays compact above
    // the playable-media cards.
    itemsIndexed(suggestions.drop(1).take(3), key = { _, term -> "suggest:$term" }) { _, term ->
        SuggestionRow(
            term = term,
            isQueryAction = false,
            onFill = { onFill(term) },
            onClick = { onClick(term) },
        )
    }
}

/**
 * One typeahead row: tap the text to search it, or the arrow to put it in the
 * field and carry on typing — the pair YouTube, Google and every mobile
 * keyboard's own suggestion strip use, and the reason a longer completion
 * isn't a dead end when it's only nearly right.
 */
@Composable
private fun SuggestionRow(
    term: String,
    isQueryAction: Boolean,
    onFill: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val rowHoverInteraction = remember { MutableInteractionSource() }
    val rowHovered by rowHoverInteraction.collectIsHoveredAsState()
    val rowHoverBackground by animateColorAsState(
        targetValue = if (rowHovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f) else Color.Transparent,
        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
        label = "search-suggestion-hover-background",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(rowHoverInteraction)
            .background(rowHoverBackground, RoundedCornerShape(8.dp))
            .clickable(interactionSource = rowHoverInteraction, indication = null, onClick = onClick)
            .padding(
                start = PAGE_GUTTER,
                end = 8.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            BitChordIcons.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            // The first row is the deliberate action to search the exact text
            // in the field, not a server-provided completion. Naming it makes
            // the otherwise duplicated wording read as intentional.
            text = if (isQueryAction) """${stringResource(Res.string.search)} "$term"""" else term,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onFill != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onFill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SfStyleIcons.NorthWest,
                    contentDescription = stringResource(Res.string.recent_search_edit, term),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            // Match the arrow button's full touch target, not only its width.
            // A width-only spacer made the first row shorter than the ones
            // below, so its vertical rhythm looked visibly uneven.
            Spacer(Modifier.size(40.dp))
        }
    }
}

/**
 * Hybrid dropdown shown beneath text suggestions while typing: a horizontal
 * divider, then live media rows (cover art + title + subtitle) that play on
 * tap and open the song menu on long-press.
 */
private fun LazyListScope.searchTypeaheadDropdown(
    typeaheadResults: List<SearchResult>,
    onSongClick: (Song) -> Unit,
    onSongLongPress: ((Song) -> Unit)?,
    onBrowseClick: (BrowseItem) -> Unit,
    currentSong: Song?,
    isPlaying: Boolean,
) {
    item(key = "typeahead:divider") {
        HorizontalDivider(
            modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )
    }
    items(typeaheadResults, key = { result ->
        when (result) {
            is SearchResult.Track -> "ta:track:${result.song.videoId}"
            is SearchResult.Browse -> "ta:browse:${result.item.browseId}"
            is SearchResult.TopTrack -> "ta:top:${result.song.videoId}"
        }
    }) { result ->
        when (result) {
            is SearchResult.Track -> TypeaheadSongRow(
                song = result.song,
                onClick = { onSongClick(result.song) },
                onLongPress = onSongLongPress?.let { { it(result.song) } },
                isCurrent = result.song.isSameTrackAs(currentSong),
                isPlaying = isPlaying && result.song.isSameTrackAs(currentSong),
            )
            is SearchResult.Browse -> BrowseRow(
                item = result.item,
                onClick = { onBrowseClick(result.item) },
                onLongPress = onSongLongPress?.let { { /* browse long-press not applicable */ } },
            )
            is SearchResult.TopTrack -> TypeaheadSongRow(
                song = result.song,
                onClick = { onSongClick(result.song) },
                onLongPress = onSongLongPress?.let { { it(result.song) } },
                isCurrent = result.song.isSameTrackAs(currentSong),
                isPlaying = isPlaying && result.song.isSameTrackAs(currentSong),
            )
        }
    }
}

/**
 * A single media row inside the typeahead dropdown: 52dp cover art, title,
 * and artist/album subtitle. Tap plays the track; long-press opens the menu.
 */
@Composable
private fun TypeaheadSongRow(
    song: Song,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    val rowHoverInteraction = remember { MutableInteractionSource() }
    val rowHovered by rowHoverInteraction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(rowHoverInteraction)
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkHover(
            modifier = Modifier.size(52.dp).thumbnailBorder(RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp),
            mode = ArtworkHoverMode.DARKEN_AND_PLAY,
            hovered = rowHovered,
        ) {
            RetryableAsyncImage(
                url = song.artworkAt(ROW_ART_PX),
                fallbackUrl = song.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOfNotNull(song.artist).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What was searched for before, shown in place of the results while the field
 * is empty — the same spot Spotify and Apple Music put it, and the reason the
 * blank search page isn't just a sentence any more.
 */
private fun LazyListScope.recentSearches(
    history: List<SearchHistoryEntity>,
    currentSong: Song?,
    isPlaying: Boolean,
    onClick: (SearchHistoryEntity) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    item(key = "recent:header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.recent_searches),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.clear),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    items(history, key = { "recent:${it.id}" }) { entity ->
        RecentSearchEntityRow(
            entity = entity,
            isCurrent = entity.entityType == EntityType.TRACK && entity.id == currentSong?.videoId,
            isPlaying = isPlaying,
            onClick = { onClick(entity) },
            onRemove = { onRemove(entity.id) },
        )
    }
}

/**
 * Spotify-style entity row: square thumbnail, bold title, subtitle with type,
 * and a removal button. Tapping navigates to the entity or plays it directly.
 */
@Composable
private fun RecentSearchEntityRow(
    entity: SearchHistoryEntity,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val rowHoverInteraction = remember { MutableInteractionSource() }
    val rowHovered by rowHoverInteraction.collectIsHoveredAsState()
    val rowHoverBackground by animateColorAsState(
        targetValue = if (rowHovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f) else Color.Transparent,
        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
        label = "search-history-hover-background",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(rowHoverInteraction)
            .background(rowHoverBackground, RoundedCornerShape(8.dp))
            .clickable(interactionSource = rowHoverInteraction, indication = null, onClick = onClick)
            .padding(start = PAGE_GUTTER, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkHover(
            modifier = Modifier.size(52.dp).thumbnailBorder(RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp),
            mode = if (entity.entityType == EntityType.TRACK) ArtworkHoverMode.DARKEN_AND_PLAY else ArtworkHoverMode.DARKEN_ONLY,
            hovered = rowHovered,
        ) {
            RetryableAsyncImage(
                url = entity.artworkUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (isCurrent && isPlaying) {
                SearchPlayingBars(Modifier.align(Alignment.Center))

            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entity.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) PlayingAccent else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entity.subtitle.ifBlank { entity.entityType.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) } },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                SfStyleIcons.Close,
                contentDescription = stringResource(Res.string.recent_search_remove, entity.title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseRow(item: BrowseItem, onClick: () -> Unit, onLongPress: (() -> Unit)? = null) {
    val rowHoverInteraction = remember { MutableInteractionSource() }
    val rowHovered by rowHoverInteraction.collectIsHoveredAsState()
    val artworkShape = if (item.type == BrowseType.ARTIST) CircleShape else RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(rowHoverInteraction)
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkHover(
            modifier = Modifier.size(52.dp).thumbnailBorder(artworkShape),
            shape = artworkShape,
            mode = ArtworkHoverMode.DARKEN_ONLY,
            hovered = rowHovered,
        ) {
            RetryableAsyncImage(
                url = item.thumbnailUrl.artworkAt(ROW_ART_PX),
                fallbackUrl = item.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle.ifBlank { item.type.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) } },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Search uses Spotify's content hierarchy but BitChord's existing Material theme.
 * Browse categories become a responsive cover grid, while Songs and Videos remain rows.
 */
@Composable
private fun SearchBrowseShelf(
    title: String,
    items: List<BrowseItem>,
    onBrowseClick: (BrowseItem) -> Unit,
    onBrowseLongPress: ((BrowseItem) -> Unit)?,
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = ((maxWidth - PAGE_GUTTER * 2) / 6f).coerceIn(144.dp, 212.dp)
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = PAGE_GUTTER, end = PAGE_GUTTER),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f).padding(top = 16.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            scrollState.animateScrollTo((scrollState.value - 560).coerceAtLeast(0))
                        }
                    },
                    enabled = scrollState.value > 0,
                ) {
                    Icon(SfStyleIcons.ArrowBack, contentDescription = "Scroll $title left")
                }
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            scrollState.animateScrollTo((scrollState.value + 560).coerceAtMost(scrollState.maxValue))
                        }
                    },
                    enabled = scrollState.value < scrollState.maxValue,
                ) {
                    Icon(SfStyleIcons.ArrowForward, contentDescription = "Scroll $title right")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)
                    .padding(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items.forEach { browse ->
                    SearchBrowseCard(
                        item = browse,
                        modifier = Modifier.width(cardWidth),
                        onClick = { onBrowseClick(browse) },
                        onLongPress = onBrowseLongPress?.let { handler -> { handler(browse) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBrowseGrid(
    items: List<BrowseItem>,
    onBrowseClick: (BrowseItem) -> Unit,
    onBrowseLongPress: ((BrowseItem) -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER)) {
        val columns = (maxWidth.value / 164f).toInt().coerceIn(2, 7)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.chunked(columns).forEach { chunk ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    chunk.forEach { browse ->
                        SearchBrowseCard(
                            item = browse,
                            modifier = Modifier.weight(1f),
                            onClick = { onBrowseClick(browse) },
                            onLongPress = onBrowseLongPress?.let { handler -> { handler(browse) } },
                        )
                    }
                    repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SearchBrowseCard(
    item: BrowseItem,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val isArtist = item.type == BrowseType.ARTIST
    val imageShape = if (isArtist) CircleShape else RoundedCornerShape(9.dp)
    val background by animateColorAsState(
        targetValue = if (hovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f)
        else Color.Transparent,
        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
        label = "search-grid-card-hover",
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .hoverable(interaction)
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress)
            .padding(8.dp),
    ) {
        ArtworkHover(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).thumbnailBorder(imageShape),
            shape = imageShape,
            mode = ArtworkHoverMode.DARKEN_ONLY,
            hovered = hovered,
        ) {
            RetryableAsyncImage(
                url = item.thumbnailUrl?.artworkAt(ROW_ART_PX),
                fallbackUrl = item.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = if (isArtist) "Artist" else item.subtitle.ifBlank {
                item.type.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Filter pills rather than a tab row: squarish rounded rectangles, the selected
 * one inverted. They scroll horizontally so a long label set never squeezes the
 * text, and the gutter padding sits inside the scroll so it scrolls with them.
 */
@Composable
private fun SearchFilterTabs(filter: SearchFilter, onFilterChange: (SearchFilter) -> Unit) {
    val haptics = rememberHaptics()
    // Center the filter group under the desktop search field while keeping
    // horizontal scrolling on narrow windows.
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchFilter.entries.forEach { entry ->
                val selected = entry == filter
                Box(
                    modifier = Modifier
                        .clip(FILTER_PILL_SHAPE)
                        .background(
                            if (selected) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        // Only the pill that isn't already selected has anything to
                        // report — re-tapping the current filter changes nothing, so
                        // buzzing for it would be feedback for a no-op.
                        .clickable {
                            if (!selected) haptics.play(Haptic.Select)
                            onFilterChange(entry)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.background
                        else MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Rounded, but well short of a capsule — the corner reads as a cut, not a curve. */
private val FILTER_PILL_SHAPE = RoundedCornerShape(12.dp)
