package com.music.bitchord.reworked

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import coil3.compose.AsyncImage
import com.music.bitchord.data.TopPicks
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.*
import com.music.bitchord.data.settings.*
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.LocalPullToRefreshEnabled
import com.music.bitchord.ui.components.contextClick
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.player.*
import com.music.bitchord.ui.screens.*
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.ic_logo
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.painterResource
import java.awt.Desktop
import java.awt.Dimension
import java.net.URI

fun main(args: Array<String>) {
    if (args.contains("--check-platform")) {
        println("BitChord Reworked: ${System.getProperty("os.name")} / Java ${Runtime.version()}")
        org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avformat::class.java)
        println("FFmpeg native decoder loaded")
        return
    }
    if (args.contains("--offline-smoke")) OfflineSmoke.install()
    coil3.SingletonImageLoader.setSafe { context ->
        coil3.ImageLoader.Builder(context).components {
            add(coil3.svg.SvgDecoder.Factory())
            add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { com.music.bitchord.data.Http.client }))
        }.build()
    }
    // This only reads this Reworked profile. OfflineSmoke explicitly installs an
    // empty guest registry and never opens saved account files or an auth client.
    runBlocking(Dispatchers.IO) { MobileYouTubeAccounts.restore() }
    if (!OfflineSmoke.active) InnerTubeXResolver.init(DesktopStore.cache.toFile(), object : InnerTubeXResolver.ConfigStore {
        override fun getString(key: String) = DesktopStore.readText("extractor-${safeKey(key)}.json")
        override fun getLong(key: String) = DesktopStore.preferences.get(key, null)?.toLongOrNull()
        override fun putString(key: String, value: String) = DesktopStore.writeText("extractor-${safeKey(key)}.json", value)
        override fun putLong(key: String, value: Long) = DesktopStore.preferences.putLong(key, value)
    }, poTokenProvider = com.music.bitchord.data.innertube.potoken.PoTokenGenerator(createMinter = { MobilePoToken.create() }).asTokenProvider())
    val analyzer = MobileTrackAnalyzer(resolve = MobileSources::resolve, allowed = { DesktopStore.preferences.getBoolean("automix", false) })
    val player = DesktopPlayback(resolve = { song ->
        if (OfflineSmoke.active) {
            require(song.localUri?.startsWith("file:") == true || song.localPath != null) { "The offline smoke test plays local fixture files only" }
            resolveMobileStream(song)
        } else MobileSources.resolve(song)
    }, analysisFor = analyzer::analysisFor, requestAnalysis = analyzer::request, analysisStateFor = analyzer::analysisState,
        prepareSong = MobilePlaybackPolicy::prepare, mixingAllowed = { !com.music.bitchord.data.listentogether.ListenTogether.state.value.inParty },
        syncedLyrics = { song -> MobileFiles.lyricsFor(song) ?: if (OfflineSmoke.active) null else withTimeoutOrNull(15_000) {
            com.music.bitchord.data.lyrics.LyricsRepository.lyrics(song.videoId, song.title, song.artist,
                song.durationText.orEmpty().split(':').fold(0L) { seconds, part -> seconds * 60 + (part.toLongOrNull() ?: 0) } * 1000,
                album = song.albumName, order = MobilePlayerSettings.lyricsSourceOrder.value,
                prioritizeSyllableSync = MobilePlayerSettings.prioritizeSyllableSync.value)?.lines
        } })
    val host = MobilePlayerHost(player)
    PlayerPlatform.install(host); AppUi.install(host)
    val session = MobileSession(player, host, analyzer)
    MobileYouTubeAccounts.state.value.error?.let(session::notice)
    MobileFiles.start()
    application {
        var mainVisible by remember { mutableStateOf(true) }
        val windowState = rememberWindowState(width = 1220.dp, height = 800.dp)
        val exit: () -> Unit = { session.close(); analyzer.close(); exitApplication() }
        val hasTray = remember { java.awt.SystemTray.isSupported() }
        if (hasTray) Tray(icon = reworkedAppIcon(), tooltip = "BitChord Desktop Reworked", onAction = { mainVisible = true }) {
            Item("Open BitChord", onClick = { mainVisible = true })
            Item("Play / Pause", onClick = player::toggle)
            Item("Next", onClick = player::next)
            Item("Picture in picture", onClick = ReworkedPip::open)
            Separator()
            Item("Quit", onClick = exit)
        }
        Window(
            onCloseRequest = {
                if (ReworkedPipPreferences.onClose.value && player.state.value.song != null) {
                    ReworkedPip.open(); mainVisible = false
                } else if (hasTray && DesktopWindowOptions.keepPlaying) mainVisible = false else exit()
            },
            visible = mainVisible,
            title = "BitChord Desktop Reworked" + if (OfflineSmoke.active) " · Offline smoke test" else "",
            state = windowState,
            icon = reworkedAppIcon(),
        ) {
            ApplyWindowAppearance(window)
            DisposableEffect(window) { window.minimumSize = Dimension(820, 560); onDispose {} }
            DisposableEffect(window, session) {
                val linuxMedia = DesktopMpris(player, { mainVisible = true; window.toFront() }, exit, { MobileLinks.open(session, it) })
                val windowsMedia = WindowsMedia(window, player)
                onDispose { linuxMedia.close(); windowsMedia.close() }
            }
            LaunchedEffect(Unit) { args.filterNot { it.startsWith("--") }.forEach { MobileLinks.open(session, it) } }
            LaunchedEffect(window) {
                while (!window.isShowing) delay(50)
                ReworkedUpdateInstaller.confirmStartup()
            }
            val systemDark = isSystemInDarkTheme()
            SideEffect { ReworkedAppearance.systemDark = systemDark }
            ReworkedTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density * ReworkedAppearance.zoom / 100f, LocalDensity.current.fontScale),
                    LocalPullToRefreshEnabled provides false,
                ) {
                    MobileDesktopApp(session, onExit = exit, onFullscreen = {
                        windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                    })
                }
            }
        }
        val pipLyrics by session.lyrics.collectAsState()
        val pipLyricsLoading by session.lyricsLoading.collectAsState()
        val pipLikes by session.liked.collectAsState()
        val pipRatings by com.music.bitchord.data.LikeState.overrides.collectAsState()
        ReworkedPipHost(player, onRestore = { mainVisible = true; ReworkedPip.close() },
            lyrics = { ReworkedPipLyrics(pipLyrics?.ownerId, pipLyrics?.lines,
                if (pipLyricsLoading) "Loading lyrics…" else null) },
            isLiked = { id -> pipRatings[id]?.let { it == LikeStatus.LIKE } ?: pipLikes.any { it.videoId == id } }, onToggleLike = session::toggleLike)
    }
}

private fun safeKey(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileDesktopApp(session: MobileSession, onExit: () -> Unit, onFullscreen: () -> Unit) {
    val integrations = remember(session) { session.integrations }
    var showParty by remember { mutableStateOf(false) }
    var showScrobbling by remember { mutableStateOf(false) }
    var showReplay by remember { mutableStateOf(false) }
    val route by session.route.collectAsState()
    val playback by session.player.state.collectAsState()
    val switchingVersion by session.switchingVersion.collectAsState()
    val squareVideo by MobilePlayerSettings.squareVideoThumbnails.collectAsState()
    SideEffect { com.music.bitchord.ui.components.VideoThumbnails.square = squareVideo }
    val liked by session.liked.collectAsState()
    val ratings by com.music.bitchord.data.LikeState.overrides.collectAsState()
    val signedIn by session.signedIn.collectAsState()
    val accountState by session.accounts.collectAsState()
    val listenerRevision by session.listenerRevision.collectAsState()
    val playlists by session.playlists.collectAsState()
    val detail by session.detail.collectAsState()
    val fetchedLyrics by session.lyrics.collectAsState()
    val lyrics = fetchedLyrics?.takeIf { it.ownerId == playback.song?.videoId }
    val lyricsLoading by session.lyricsLoading.collectAsState()
    val providers by session.providerStates.collectAsState()
    var expandedPlayer by remember { mutableStateOf(false) }
    var lyricsOffsetOpen by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(DesktopStore.preferences.getBoolean("details_open", false)) }
    var quickSearch by remember { mutableStateOf(false) }
    var shortcuts by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }
    var importSpotify by remember { mutableStateOf(false) }
    var playlistDialog by remember { mutableStateOf(false) }
    var librarySortOpen by remember { mutableStateOf(false) }
    var playlistSong by remember { mutableStateOf<Song?>(null) }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var collectionMenu by remember { mutableStateOf<DetailPage?>(null) }
    LaunchedEffect(listenerRevision) {
        menuSong = null; collectionMenu = null; playlistDialog = false; playlistSong = null
        importSpotify = false; quickSearch = false; librarySortOpen = false
    }
    val homeState = rememberLazyListState()
    val newState = rememberLazyListState()
    val exploreState = rememberLazyListState()
    val searchState = rememberLazyListState()
    val libraryState = rememberLazyListState()
    val padding = PaddingValues(horizontal = 24.dp, vertical = 24.dp)
    val snackbar = remember { SnackbarHostState() }
    val notice by session.host.messages.collectAsState()
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); session.host.messages.value = null } }
    LaunchedEffect(playback.error) { playback.error?.let(session::notice) }
    LaunchedEffect(expandedPlayer) { if (!expandedPlayer) lyricsOffsetOpen = false }

    Surface(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            event.isCtrlPressed && event.key == Key.K -> { quickSearch = true; true }
            event.isCtrlPressed && event.key == Key.Slash -> { shortcuts = true; true }
            event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed && event.key == Key.L -> { linkDialog = true; true }
            event.key == Key.F11 -> { onFullscreen(); true }
            event.key == Key.MediaPlayPause -> { session.player.toggle(); true }
            event.key == Key.MediaNext -> { session.player.next(); true }
            event.key == Key.MediaPrevious -> { session.player.previous(); true }
            event.key == Key.Escape && expandedPlayer -> { if (!PlayerBack.dispatch()) expandedPlayer = false; true }
            event.isAltPressed && event.key == Key.DirectionLeft -> { session.back(); true }
            else -> false
        }
    }.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false else when {
            event.isCtrlPressed && event.isAltPressed && event.key == Key.L -> { MobilePlayerSettings.setLastPlayerScreen(LastPlayerScreen.LYRICS); expandedPlayer = true; true }
            event.isCtrlPressed && event.isShiftPressed && event.key == Key.L -> { session.navigate(Destination.SEARCH); true }
            event.isAltPressed && event.isShiftPressed && event.key == Key.H -> { session.navigate(Destination.HOME); true }
            event.isCtrlPressed && event.key == Key.DirectionRight -> { session.player.next(); true }
            event.isCtrlPressed && event.key == Key.DirectionLeft -> { session.player.previous(); true }
            event.isShiftPressed && event.key == Key.DirectionRight -> { session.player.seek(playback.positionMs + 10_000); true }
            event.isShiftPressed && event.key == Key.DirectionLeft -> { session.player.seek((playback.positionMs - 10_000).coerceAtLeast(0)); true }
            event.isAltPressed && event.key == Key.DirectionUp -> { session.player.setVolume(playback.volume + 0.05f); true }
            event.isAltPressed && event.key == Key.DirectionDown -> { session.player.setVolume(playback.volume - 0.05f); true }
            event.isAltPressed && event.key == Key.S -> { session.player.toggleShuffle(); true }
            event.isAltPressed && event.key == Key.R -> { session.player.cycleRepeat(); true }
            event.key == Key.Spacebar && !event.isCtrlPressed && !event.isAltPressed -> { session.player.toggle(); true }
            else -> false
        }
    }, color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                DesktopNavigation(route, playlists, session, onSearch = { quickSearch = true }, onNew = { playlistDialog = true }, onImport = { importSpotify = true })
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (!ReworkedAppearance.bottomPlayer) MobileMiniPlayer(session, { expandedPlayer = true }, onFullscreen, { menuSong = it })
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val canBack by session.canGoBack.collectAsState()
                        IconButton(session::back, enabled = canBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        Text(route.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        MobileYouTubeProfileButton(Modifier.widthIn(max = 180.dp))
                        TextButton(onClick = { quickSearch = true }) { Icon(BitChordIcons.Search, null, Modifier.size(18.dp)); Text("  Search · Ctrl+K") }
                        if (route == Destination.SEARCH) {
                            val sourceSearch by session.sourceSearch.collectAsState()
                            TextButton(onClick = { session.sourceSearch.value = !sourceSearch; session.searchResults.value = null; if (session.query.value.isNotBlank()) session.search() }) { Text(if (sourceSearch) "Source: Addons" else "Source: YouTube Music") }
                        }
                        if (route == Destination.LIBRARY) Box {
                            TextButton(onClick = { librarySortOpen = true }) { Text("Sort") }
                            DropdownMenu(librarySortOpen, onDismissRequest = { librarySortOpen = false }) {
                                LibrarySort.entries.forEach { order -> DropdownMenuItem(text = { Text(when (order) { LibrarySort.DEFAULT -> "Default / pinned first"; LibrarySort.TITLE_ASC -> "Title A–Z"; LibrarySort.TITLE_DESC -> "Title Z–A" }) }, onClick = { session.host.setLibrarySort(order); librarySortOpen = false }) }
                            }
                        }
                        if (playback.song != null) IconButton(onClick = { showDetails = !showDetails; DesktopStore.preferences.putBoolean("details_open", showDetails) }) { Icon(Icons.Rounded.Info, "Now playing details") }
                        IconButton(onClick = { session.navigate(Destination.SETTINGS) }) { Icon(Icons.Rounded.Settings, "Settings") }
                    }
                    HorizontalDivider()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (route) {
                            Destination.HOME -> {
                                val home by session.home.collectAsState(); val picks by session.topPicks.collectAsState()
                                val hideRecents by MobilePlayerSettings.hideRecents.collectAsState()
                                val hideVideos by MobilePlayerSettings.hideHomeVideos.collectAsState()
                                val hidePicks by MobilePlayerSettings.hideTopPicks.collectAsState()
                                val hideMoods by MobilePlayerSettings.hideHomeMoods.collectAsState()
                                val visibleHome = remember(home, hideRecents, hideVideos) {
                                    (home as? UiState.Success)?.let { loaded ->
                                        var shelves = loaded.data
                                        if (hideRecents) shelves = shelves.filterNot { it.title.equals(YtMusicRepository.RECENT_TITLE, ignoreCase = true) || it.title.equals("Recently played", ignoreCase = true) || it.title.equals("Recents", ignoreCase = true) }
                                        if (hideVideos) shelves = shelves.withoutVideos()
                                        UiState.Success(shelves)
                                    } ?: home
                                }
                                val picksLoading by session.topPicksLoading.collectAsState(); val more by session.loadingMoreHome.collectAsState()
                                val recentLoading by session.loadingRecents.collectAsState()
                                HomeScreen(visibleHome, homeState, session::openShelf, session::refreshHome, false, session::refreshHome,
                                    rememberPullToRefreshState(), contentPadding = padding, title = "Listen now", signedIn = signedIn,
                                    onSignIn = session::signIn, onItemLongPress = { item ->
                                        if (item.videoId != null) menuSong = item.toSong() else collectionMenu = item.collectionPreview()
                                    }, onLoadMore = session::moreHome, loadingMore = more, recentlyPlayedLoading = recentLoading && !hideRecents, currentSong = playback.song, isPlaying = playback.isPlaying,
                                    topPicks = picks, topPicksLoading = picksLoading, onTopPickClick = if (hidePicks) null else session::topPick,
                                    onMoodClick = if (hideMoods) null else { mood, title -> session.topPick(TopPicks.moodStation(mood), title) },
                                    onMoodsShowAll = { session.navigate(Destination.EXPLORE) }, artworkMotion = MobilePlayerSettings.animatedCanvas.collectAsState().value)
                            }
                            Destination.NEW -> {
                                val state by session.newReleases.collectAsState()
                                HomeScreen(state, newState, session::openShelf, session::refreshNew, false, session::refreshNew,
                                    rememberPullToRefreshState(), contentPadding = padding, title = "New releases", currentSong = playback.song, isPlaying = playback.isPlaying)
                            }
                            Destination.EXPLORE -> {
                                val state by session.explore.collectAsState()
                                val openMood: (MoodGenre) -> Unit = { mood ->
                                    session.scope.launch { session.accountRead(listenerRevision) { YtMusicRepository.moodGenreShelves(mood.browseId, mood.params) }.onSuccess { shelves ->
                                        session.showCollection(DetailPage(mood.browseId, mood.title, "", mood.thumbnailUrl, UiState.Success(emptyList()), sections = shelves))
                                    }.onFailure { session.notice(it.message ?: "Could not load this mood") } }
                                }
                                if (DesktopAppearanceExtras.classicExplore) ClassicExplorePage(state, openMood, session::refreshExplore)
                                else ExploreScreen(state, exploreState, onCategoryClick = openMood, onRetry = session::refreshExplore, refreshing = false, onRefresh = session::refreshExplore,
                                    pullState = rememberPullToRefreshState(), contentPadding = padding)
                            }
                            Destination.SEARCH -> {
                                val sourceSearch by session.sourceSearch.collectAsState()
                                if (sourceSearch) MobileSourceSearch(session, { menuSong = it }) else {
                                val query by session.query.collectAsState(); val filter by session.filter.collectAsState()
                                val results by session.searchResults.collectAsState(); val more by session.loadingMoreSearch.collectAsState()
                                val suggestions by session.suggestions.collectAsState(); val typeahead by session.typeahead.collectAsState()
                                val history by session.recentSearches.collectAsState()
                                SearchScreen(query, { session.query.value = it }, filter, playback.song, playback.isPlaying,
                                    onFilterChange = { session.filter.value = it; session.search() }, results = results, loadingMore = more,
                                    onLoadMore = session::moreSearch, listState = searchState, scrollResetTrigger = 0, focusRequested = true, onFocusHandled = {},
                                    onSongClick = session::playFromSearch, onSongLongPress = { menuSong = it },
                                    onSongSwipe = session.player::playNext, onTopResultPlay = session::playSearchSong, onTopResultPlaylist = { playlistSong = it; playlistDialog = true },
                                    onBrowseClick = session::openBrowse, history = history, suggestions = suggestions, typeaheadResults = typeahead,
                                    onSubmit = { session.search() }, onSuggestionClick = session::search,
                                    onHistoryClick = session::openHistory, onHistoryRemove = session::removeHistory, onHistoryClear = session::clearSearchHistory,
                                    contentPadding = padding, topPadding = 0.dp)
                                }
                            }
                            Destination.LIBRARY -> {
                                val library by session.library.collectAsState()
                                val deviceItems = listOf(ShelfItem("Liked songs", "${liked.size} songs", liked.firstOrNull()?.thumbnailUrl, null, "device:liked")) +
                                    playlists.map { ShelfItem(it.title, "${it.songs.size} songs", it.songs.firstOrNull()?.thumbnailUrl, null, it.id) }
                                CompositionLocalProvider(LocalShelfCardWidth provides 112.dp) { LibraryScreen(signedIn, library, libraryState, onShelfItemClick = { session.openShelf(it) }, onShelfItemLongPress = { collectionMenu = it.collectionPreview() },
                                    onNewPlaylist = { playlistDialog = true }, onImportSpotifyPlaylist = { importSpotify = true },
                                    onShowAll = { shelf -> session.showCollection(DetailPage("shelf:${shelf.title}", shelf.title, "", null, UiState.Success(emptyList()), sections = listOf(shelf))) },
                                    replay = { TextButton(onClick = { showReplay = true }) { Icon(Icons.Rounded.BarChart, null); Text("  Your Replay") } }, onSignIn = session::signIn, onRetry = session::refreshLibrary,
                                    refreshing = false, onRefresh = session::refreshLibrary, pullState = rememberPullToRefreshState(), contentPadding = padding,
                                    links = emptyList(), deviceItems = deviceItems) }
                            }
                            Destination.SPOTIFY -> ReworkedSpotifyPage(session::openSpotifyPlaylist, { importSpotify = true }, { DesktopBrowserAuth.spotify { session.notice("Spotify connected. Refresh your library.") } })
                            Destination.LOCAL -> MobileLocalLibrary(session, downloaded = false, onMenu = { menuSong = it })
                            Destination.REMOTE -> MobileRemotePage(session)
                            Destination.DOWNLOADS -> MobileLocalLibrary(session, downloaded = true, onMenu = { menuSong = it })
                            Destination.HISTORY -> { val songs by session.history.collectAsState(); SongListPage("History", songs, session, { menuSong = it }) }
                            Destination.SETTINGS -> MobileSettingsPage(session, onExit, { showParty = true }, { showScrobbling = true }, { showReplay = true })
                            Destination.DETAILS -> detail?.let { page -> CollectionPage(page, session, { menuSong = it }, { collectionMenu = it }) }
                        }
                    }
                    if (ReworkedAppearance.bottomPlayer) MobileMiniPlayer(session, { expandedPlayer = true }, onFullscreen, { menuSong = it })
                }
                if (showDetails && playback.song != null) MobileNowPlayingDetails(session, { MobilePlayerSettings.setLastPlayerScreen(LastPlayerScreen.LYRICS); expandedPlayer = true }, Modifier.width(300.dp))
            }
            if (expandedPlayer && playback.song != null) {
                BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val song = playback.song!!
                    val autoplay by session.autoplay.collectAsState()
                    NowPlayingScreen(song = song, isPlaying = playback.isPlaying, isLoading = playback.isLoading,
                        position = session.position, durationMs = playback.durationMs, audioVersionSwitching = switchingVersion, qualityUpgraded = false,
                        queue = playback.queue, queueIndex = playback.queueIndex, hasPrevious = playback.queueIndex > 0 || playback.positionMs > 0,
                        hasNext = playback.queueIndex < playback.queue.lastIndex, repeatMode = playback.repeat, shuffleEnabled = playback.shuffle,
                        autoplayEnabled = autoplay, signedIn = signedIn, accountName = accountState.activeProfile?.name,
                        likeStatus = ratings[song.videoId] ?: if (liked.any { it.videoId == song.videoId }) LikeStatus.LIKE else LikeStatus.INDIFFERENT,
                        onToggleLike = { session.toggleLike(song) }, onPlayPause = session.player::toggle, onNext = session.player::next,
                        onPrevious = session.player::previous, onBlockedControl = { session.notice("Playback controls are locked by the party host") },
                        onSeek = session.player::seek, onSeekFraction = { session.player.seek((session.player.state.value.durationMs * it).toLong()) },
                        onToggleShuffle = session.player::toggleShuffle, onCycleRepeat = session.player::cycleRepeat,
                        onToggleAutoplay = { session.autoplay.value = !autoplay; DesktopStore.preferences.putBoolean("autoplay", !autoplay) },
                        onJumpTo = session.player::jump, onRemoveFromQueue = session.player::remove, onMoveInQueue = session.player::move,
                        onClearQueue = session.player::clearQueue, onOpenMenu = { menuSong = song },
                        onOpenAlbum = { id -> expandedPlayer = false; session.openBrowse(BrowseItem(id, song.albumName.orEmpty(), song.artist, song.thumbnailUrl, BrowseType.ALBUM)) },
                        onOpenArtist = { id, name -> expandedPlayer = false; if (id != null) session.openBrowse(BrowseItem(id, name, "", null, BrowseType.ARTIST)) else session.search(name) },
                        onOpenPlaybackSource = { expandedPlayer = false; session.openPlaybackSource(song) { showReplay = true } }, onListenTogether = { showParty = true },
                        lyrics = lyrics?.lines, lyricsSource = lyrics?.source, lyricsProviderStates = providers,
                        onSelectLyricsProvider = { session.lyricsProvider.value = it }, lyricsUnavailable = !lyricsLoading && lyrics == null,
                        lyricsOffsetOpen = lyricsOffsetOpen, onDismissLyricsOffset = { lyricsOffsetOpen = false }, windowWidth = maxWidth, windowHeight = maxHeight,
                        menuOpen = { menuSong != null }, modifier = Modifier.fillMaxSize())
                    IconButton(onClick = { expandedPlayer = false }, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Close player", tint = androidx.compose.ui.graphics.Color.White) }
                    IconButton(onClick = onFullscreen, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) { Icon(Icons.Rounded.Fullscreen, "Fullscreen", tint = androidx.compose.ui.graphics.Color.White) }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
        }
    }
    if (linkDialog) AlertDialog(onDismissRequest = { linkDialog = false }, title = { Text("Open music link or file") }, text = { OutlinedTextField(linkText, { linkText = it }, singleLine = true, label = { Text("YouTube Music URL or audio file path") }) }, confirmButton = { TextButton(enabled = linkText.isNotBlank(), onClick = { MobileLinks.open(session, linkText); linkDialog = false }) { Text("Open") } }, dismissButton = { TextButton(onClick = { linkDialog = false }) { Text("Cancel") } })
    if (showParty) MobileListenTogetherDialog(integrations) { showParty = false }
    if (showScrobbling) MobileScrobbleDialog(integrations) { showScrobbling = false }
    if (showReplay) MobileReplayDialog(integrations) { showReplay = false }
    if (quickSearch) ReworkedQuickSearch({ quickSearch = false }, session::playSearchSong, session::openBrowse, session::search)
    if (shortcuts) AlertDialog(onDismissRequest = { shortcuts = false }, title = { Text("Keyboard shortcuts") },
        text = { Text("Ctrl+K   Quick Search\nCtrl+/   Shortcuts\nCtrl+L   Open link or audio file\nSpace   Play / Pause\nCtrl+← / →   Previous / Next\nShift+← / →   Seek 10 seconds\nAlt+↑ / ↓   Volume\nAlt+S / R   Shuffle / Repeat\nCtrl+Alt+L   Lyrics\nCtrl+Shift+L   Search\nAlt+Shift+H   Home\nAlt+Left   Back\nF11   Fullscreen\nEsc   Close player or dialog\nMedia keys   Playback\nQuick Search: ↑/↓ choose, Enter open, Shift+Enter play") },
        confirmButton = { TextButton(onClick = { shortcuts = false }) { Text("Close") } })
    if (importSpotify) ReworkedSpotifyImportDialog(signedIn, { importSpotify = false }, session::savePlaylist)
    if (playlistDialog) PlaylistDialog(session, playlistSong, { playlistDialog = false; playlistSong = null }, { playlistDialog = false; importSpotify = true })
    menuSong?.let { song -> SongMenu(song, session, { menuSong = null }, { playlistSong = song; playlistDialog = true; menuSong = null },
        onLyricsTiming = { expandedPlayer = true; lyricsOffsetOpen = true }) }
    collectionMenu?.let { page -> CollectionMenu(page, session, { collectionMenu = null }) }
}

@Composable
private fun DesktopNavigation(route: Destination, playlists: List<LocalPlaylist>, session: MobileSession, onSearch: () -> Unit, onNew: () -> Unit, onImport: () -> Unit) {
    val collapsed = ReworkedAppearance.collapsedSidebar
    Surface(Modifier.width(if (collapsed) 66.dp else 208.dp).fillMaxHeight(), color = if (DesktopAppearanceExtras.sidebarTint && !ReworkedAppearance.black && DesktopAppearanceExtras.wallpaperColor != null) androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, DesktopAppearanceExtras.wallpaperColor!!, 0.14f) else MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Res.drawable.ic_logo), "BitChord", Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurface)
                if (!collapsed) { Spacer(Modifier.width(8.dp)); Text("BitChord", style = MaterialTheme.typography.titleLarge) }
            }
            IconButton(onClick = { ReworkedAppearance.setCollapsedSidebar(!collapsed) }) { Icon(Icons.Rounded.Menu, "Toggle sidebar") }
            val links = listOf(Destination.HOME to BitChordIcons.Home, Destination.NEW to Icons.Rounded.AutoAwesome,
                Destination.EXPLORE to BitChordIcons.Explore, Destination.SEARCH to BitChordIcons.Search, Destination.LIBRARY to BitChordIcons.Library,
                Destination.SPOTIFY to Icons.Rounded.LibraryMusic, Destination.LOCAL to Icons.Rounded.Folder, Destination.REMOTE to Icons.Rounded.Cloud,
                Destination.DOWNLOADS to BitChordIcons.Download, Destination.HISTORY to BitChordIcons.Clock)
            val pinned by session.host.pinnedPlaylists.collectAsState()
            val library by session.library.collectAsState()
            val saved = (library as? UiState.Success)?.data?.shelves.orEmpty().flatMap { it.items }.filter { it.browseId != null && (it.browseId!!.startsWith("VL") || it.browseId in pinned) }.distinctBy { it.browseId }
            // Routes and collections share the scroll area: large UI scaling or a
            // short window must not consume the entire saved-playlist viewport.
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(links, key = { "route:${it.first.name}" }) { (destination, icon) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (route == destination) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { if (destination == Destination.SEARCH) onSearch() else session.navigate(destination) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, destination.label, Modifier.size(20.dp), tint = if (route == destination) ReworkedAppearance.accent else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!collapsed) Text(destination.label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item("divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                if (!collapsed) {
                    item("new-playlist") { TextButton(onClick = onNew) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text(" New playlist") } }
                    item("import-playlist") { TextButton(onClick = onImport) { Text("Import Spotify playlist") } }
                    items(saved.sortedBy { if (it.browseId in pinned) 0 else 1 }, key = { "saved:${it.browseId}" }) { item ->
                        Text(item.title, Modifier.fillMaxWidth().clickable { session.openShelf(item) }.padding(vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    items(playlists.sortedBy { if (it.id in pinned) 0 else 1 }, key = { "local:${it.id}" }) { list ->
                        Text(list.title, Modifier.fillMaxWidth().clickable { session.openBrowse(BrowseItem(list.id,list.title,"",null,BrowseType.PLAYLIST)) }
                            .contextClick { session.host.togglePin(list.id) }.padding(vertical = 8.dp, horizontal = 10.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            IconButton(onClick = { session.navigate(Destination.SETTINGS) }) { Icon(Icons.Rounded.Settings, "Settings") }
        }
    }
}

@Composable
private fun MobileMiniPlayer(session: MobileSession, onExpand: () -> Unit, onFullscreen: () -> Unit, onMenu: (Song) -> Unit) {
    val state by session.player.state.collectAsState(); val liked by session.liked.collectAsState()
    Surface(Modifier.fillMaxWidth().height(58.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val song = state.song
            if (song == null) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Icon(painterResource(Res.drawable.ic_logo), "No music playing", Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                AsyncImage(song.thumbnailUrl, null, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onExpand))
                Column(Modifier.weight(1f).clickable(onClick = onExpand).padding(horizontal = 10.dp)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { session.toggleLike(song) }) { Icon(if (liked.any { it.videoId == song.videoId }) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Like") }
                IconButton(onClick = session.player::previous) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                IconButton(onClick = session.player::toggle) { if (state.isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause") }
                IconButton(onClick = session.player::next) { Icon(Icons.Rounded.SkipNext, "Next") }
                Slider(value = if (state.durationMs > 0) state.positionMs.toFloat()/state.durationMs else 0f,
                    onValueChange = { session.player.seek((state.durationMs * it).toLong()) }, modifier = Modifier.widthIn(min = 80.dp, max = 180.dp).weight(0.5f))
                IconButton(onClick = ReworkedPip::open) { Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture") }
                IconButton(onClick = onFullscreen) { Icon(Icons.Rounded.Fullscreen, "Fullscreen") }
                IconButton(onClick = { onMenu(song) }) { Icon(Icons.Rounded.MoreHoriz, "Song actions") }
            }
        }
    }
}

@Composable
internal fun SongListPage(title: String, songs: List<Song>, session: MobileSession, onMenu: (Song) -> Unit, action: @Composable () -> Unit = {}) {
    val playback by session.player.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge); action()
        if (songs.isEmpty()) Text("No songs yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(songs, key = { index,song -> "${song.videoId}:$index" }) { index, song ->
                CompactTrackRow(song.title, song.artist, song.thumbnailUrl, { session.player.playSongs(songs.map { track ->
                    if (session.route.value == Destination.HISTORY) track.copy(playbackSource = title, playbackSourceType = PlaybackSourceType.HISTORY) else track
                },index) }, { onMenu(song) },
                    isCurrent = playback.song?.videoId == song.videoId, isPlaying = playback.isPlaying, isVideo = song.isVideo)
            }
        }
    }
}

internal fun openExternal(url: String) { runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url)) } }
