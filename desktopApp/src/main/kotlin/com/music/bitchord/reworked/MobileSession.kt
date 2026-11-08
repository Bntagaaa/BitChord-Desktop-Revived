package com.music.bitchord.reworked

import com.music.bitchord.data.TopPicks
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.lyrics.*
import com.music.bitchord.data.model.*
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.LyricsProviderState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

internal enum class Destination(val label: String) {
    HOME("Home"), NEW("New"), EXPLORE("Explore"), SEARCH("Search"), LIBRARY("Library"),
    SPOTIFY("Spotify"), LOCAL("Local music"), REMOTE("Remote music"), DOWNLOADS("Downloads"), HISTORY("History"), SETTINGS("Settings"), DETAILS("Collection"),
}

@Serializable
internal data class LocalPlaylist(val id: String = "local:" + UUID.randomUUID(), val title: String, @Serializable(with = SongListSerializer::class) val songs: List<Song> = emptyList())

internal data class SessionLyrics(val ownerId: String, val source: LyricsSource?, val lines: List<LyricLine>)

@Serializable
internal data class SavedQueue(@Serializable(with = SongListSerializer::class) val songs: List<Song>, val index: Int, val positionMs: Long,
    val volume: Float = 1f, val speed: Float = 1f, val shuffle: Boolean = false, val repeat: Int = 0)

/**
 * The mobile view-model's repository and queue contracts, hosted by a new JVM lifecycle.
 * Screens use upstream sharedUi directly; Android Context/MediaController never enter this module.
 */
@OptIn(FlowPreview::class)
internal class MobileSession(val player: DesktopPlayback, val host: MobilePlayerHost, val analyzer: MobileTrackAnalyzer? = null) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val route = MutableStateFlow(Destination.HOME)
    private val backStack = ArrayDeque<Pair<Destination, DetailPage?>>()
    val canGoBack = MutableStateFlow(false)
    val home = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val newReleases = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val explore = MutableStateFlow<UiState<List<MoodGenreSection>>>(UiState.Loading)
    val library = MutableStateFlow<UiState<LibraryPage>>(UiState.Loading)
    val topPicks = MutableStateFlow<List<TopPick>?>(null)
    val topPicksLoading = MutableStateFlow(false)
    val loadingMoreHome = MutableStateFlow(false)
    val loadingRecents = MutableStateFlow(false)
    private var homeToken: String? = null
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(SearchFilter.ALL)
    val sourceSearch = MutableStateFlow(false)
    val searchResults = MutableStateFlow<UiState<List<SearchResult>>?>(null)
    val suggestions = MutableStateFlow<List<String>>(emptyList())
    val typeahead = MutableStateFlow<List<SearchResult>>(emptyList())
    val loadingMoreSearch = MutableStateFlow(false)
    private var searchToken: String? = null
    val recentSearches = MutableStateFlow(read<List<SearchHistoryEntity>>("search-history.json") ?: emptyList())
    val detail = MutableStateFlow<DetailPage?>(null)
    val detailFilter = MutableStateFlow("")
    val loadingMoreDetail = MutableStateFlow(false)
    val accounts = MobileYouTubeAccounts.state
    val listenerRevision = MutableStateFlow(accounts.value.revision)
    private var listenerKey = accounts.value.listenerKey
    val liked = MutableStateFlow(readSongs(listenerFile("liked")) ?: emptyList())
    val history = MutableStateFlow(readSongs(listenerFile("history")) ?: emptyList())
    val playlists = MutableStateFlow(read<List<LocalPlaylist>>("playlists.json") ?: emptyList())
    val signedIn = MutableStateFlow(accounts.value.signedIn)
    val position = PlaybackPosition()
    val lyrics = MutableStateFlow<SessionLyrics?>(null)
    val lyricsLoading = MutableStateFlow(false)
    val lyricsProvider = MutableStateFlow<LyricsSource?>(null)
    val providerStates = MutableStateFlow<Map<LyricsSource, LyricsProviderState>>(emptyMap())
    val autoplay = MutableStateFlow(DesktopStore.preferences.getBoolean("autoplay", false))
    val switchingVersion = MutableStateFlow(false)
    private val alternateVersions = mutableMapOf<String, Song>()
    private val heardThisSession = mutableSetOf<String>()
    val integrations by lazy { MobileIntegrations(this) }
    private var browseJob: Job? = null
    private var searchJob: Job? = null
    private var homeJob: Job? = null
    private var spotifyJob: Job? = null
    private var libraryJob: Job? = null
    private var likedSyncJob: Job? = null
    private var listenerJob = SupervisorJob(scope.coroutineContext[Job])
    private var listenerScope = CoroutineScope(scope.coroutineContext + listenerJob)
    private val listenerSnapshots = mutableMapOf<String, Pair<List<Song>, List<Song>>>()
    private var submittedQuery: String? = null
    private val persistenceLock = Any()
    private val persistenceVersions = mutableMapOf<String, Long>()
    private var closed = false

    init {
        read<SavedQueue>("queue.json")?.let { saved ->
            player.restoreQueue(saved.songs, saved.index, saved.positionMs)
            player.setVolume(saved.volume); player.setSpeed(saved.speed)
            if (saved.shuffle) player.toggleShuffle()
            repeat(saved.repeat.coerceIn(0, 2)) { player.cycleRepeat() }
        }
        player.setMixBars(DesktopStore.preferences.getInt("mix_bars", 0))
        player.setCrossfadeSeconds(DesktopStore.preferences.getInt("crossfade_seconds", 0))
        player.setAutomixEnabled(DesktopStore.preferences.getBoolean("automix", false))
        player.setAdvancedAutomixEnabled(DesktopStore.preferences.getBoolean("advanced_automix", false))
        player.setSkipSilence(DesktopStore.preferences.getBoolean("skip_silence", false))
        PaxSenix.setApiKey(DesktopStore.readText("paxsenix-key.txt").orEmpty())
        // Main restores the original-mobile account registry before constructing
        // this session. Reassigning a raw cookie here would erase its page scope.
        SpotifyLibrary.auth = SpotifyLibrary.Auth {
            withContext(Dispatchers.IO) { DesktopBrowserAuth.spotifyTokens()?.let { SpotifyLibrary.Tokens(it.bearer, com.music.bitchord.data.canvas.SpotifyToken.clientToken()) } }
        }
        LyricsTranslation.cacheDir = DesktopStore.cache.toFile()
        scope.launch {
            accounts.map { it.revision }.distinctUntilChanged().collect { revision ->
                if (revision != listenerRevision.value) changeListener(accounts.value)
            }
        }
        scope.launch {
            player.state.collect { state ->
                position.report(state.positionMs, state.sampledAtNanos.takeIf { it > 0 } ?: System.nanoTime())
                position.advancing = state.isPlaying && !state.isLoading
                position.seeks = state.discontinuity.toInt()
                MobilePlayerSettings.smartFadeEnabled.value = state.automix
                MobilePlayerSettings.smartMixInProgress.value = state.transition != null
                MobilePlayerSettings.smartMixProgress.value = state.transitionProgress
                host.outputFormat.value = com.music.bitchord.ui.player.OutputFormatUi(state.format.orEmpty(), bitPerfect = false)
                host.volume.refresh()
            }
        }
        scope.launch {
            player.state.map { it.song }.distinctUntilChangedBy { it?.videoId }.collectLatest { song ->
                if (song == null) return@collectLatest
                if (!isListener(listenerRevision.value)) return@collectLatest
                heardThisSession += song.videoId
                history.update { (listOf(song) + it.filterNot { row -> row.videoId == song.videoId }).take(500) }
                persistSongs(listenerFile("history"), history.value)
            }
        }
        scope.launch {
            combine(player.state.map { it.song to it.durationMs }.distinctUntilChanged(), lyricsProvider, MobilePlayerSettings.syncedLyrics,
                MobilePlayerSettings.lyricsSourceOrder, MobilePlayerSettings.prioritizeSyllableSync) { track, provider, enabled, _, _ -> Triple(track, provider, enabled) }
                .collectLatest { (track, provider, enabled) ->
                    val song = track.first
                    lyrics.value = null; providerStates.value = emptyMap()
                    if (song == null || !enabled) { lyricsLoading.value = false; return@collectLatest }
                    lyricsLoading.value = true
                    try {
                        val embedded = MobileFiles.lyricsFor(song)
                        if (!embedded.isNullOrEmpty()) { lyrics.value = SessionLyrics(song.videoId, null, embedded); return@collectLatest }
                        if (OfflineSmoke.active) return@collectLatest
                        lyrics.value = LyricsRepository.lyrics(song.videoId, song.title, song.artist, track.second,
                            album = song.albumName, sources = provider?.let(::setOf) ?: LyricsSource.offered.toSet(),
                            order = MobilePlayerSettings.lyricsSourceOrder.value,
                            prioritizeSyllableSync = MobilePlayerSettings.prioritizeSyllableSync.value,
                            onSourceStarted = { source -> providerStates.update { it + (source to LyricsProviderState.FETCHING) } },
                            onSourceResult = { source, answer -> providerStates.update { it + (source to if (answer == null) LyricsProviderState.NOT_FOUND else LyricsProviderState.FOUND) } })?.let { SessionLyrics(song.videoId, it.source, it.lines) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { notice(error.message ?: "Lyrics are unavailable") }
                    finally { lyricsLoading.value = false }
                }
        }
        scope.launch {
            combine(query, listenerRevision) { text, revision -> text to revision }.debounce(220).collectLatest { (text, revision) ->
                if (OfflineSmoke.active || text.isBlank() || text == submittedQuery || sourceSearch.value) { suggestions.value = emptyList(); typeahead.value = emptyList(); return@collectLatest }
                suggestions.value = listOf(text) + accountRead(revision) { YtMusicRepository.searchSuggestions(text) }.getOrDefault(emptyList())
                typeahead.value = accountRead(revision) { YtMusicRepository.searchTypeahead(text) }.getOrNull()?.rows.orEmpty()
            }
        }
        scope.launch { MobilePlayerSettings.mixViewOpen.collect(player::setMixViewOpen) }
        scope.launch { player.mixUi.collect { mix ->
            MobilePlayerSettings.smartAnalysis.value = mix.analysis
            MobilePlayerSettings.smartTransitionWindow.value = mix.window
            MobilePlayerSettings.smartMixPreview.value = mix.preview
            MobilePlayerSettings.smartMixView.value = mix.view
            MobilePlayerSettings.smartMixBlend.value = mix.blend
            MobilePlayerSettings.automixDetail.value = mix.detail
        } }
        scope.launch { com.music.bitchord.data.listentogether.ListenTogether.state.collect { party ->
            host.party.value = com.music.bitchord.ui.player.PartyUi(party.inParty, party.controlsLocked, party.members, party.you, party.code)
        } }
        scope.launch { MobileFiles.downloads.collect { host.downloaded.value = it.map(Song::videoId).toSet() } }
        scope.launch { MobileFiles.message.collect { it?.let(::notice) } }
        scope.launch { MobileFiles.errors.collect { errors -> errors.values.lastOrNull()?.let(::notice) } }
        scope.launch {
            player.state.map { state -> SavedQueue(state.queue, state.queueIndex, state.positionMs / 5000 * 5000, state.volume, state.speed, state.shuffle, state.repeat) }
                .distinctUntilChanged().collect { persist("queue.json", it) }
        }
        scope.launch {
            var attemptedSeed: Pair<Long, String>? = null
            combine(player.state, autoplay, listenerRevision) { state, enabled, revision -> Triple(state, enabled, revision) }
                .collect { (state, enabled, revision) ->
                    val song = state.song ?: return@collect
                    if (!enabled || !state.isPlaying || com.music.bitchord.data.listentogether.ListenTogether.state.value.inParty || state.queue.size - state.queueIndex > 4 || song.localUri != null ||
                        state.repeat != 0 || (revision to song.videoId) == attemptedSeed || state.isLoading || OfflineSmoke.active) return@collect
                    attemptedSeed = revision to song.videoId
                    try { accountRead(revision) { YtMusicRepository.radio(song.videoId) }.onSuccess { songs ->
                        if (!autoplay.value || player.state.value.song?.videoId != song.videoId) return@onSuccess
                        val known = player.state.value.queue.map(Song::videoId).toSet() + if (MobilePlayerSettings.dontRepeatSuggestions.value) heardThisSession else emptySet()
                        songs.filterNot { it.videoId in known }.distinctBy(Song::videoId).take(25).forEach {
                            player.addToQueue(it.copy(queueTier = QueueTier.AUTOPLAY, playbackSource = "Autoplay"))
                        }
                    }.onFailure { notice("Autoplay: ${it.message ?: "Could not load recommendations"}") } }
                    catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
                }
        }
        refreshHome(); refreshLibrary()
        refreshExplore(); refreshNew()
    }

    private fun listenerFile(kind: String) = "$kind-$listenerKey.json"
    fun isListener(revision: Long) = revision == listenerRevision.value && revision == accounts.value.revision
    fun requireListener(revision: Long) {
        if (!isListener(revision)) throw CancellationException("The YouTube profile changed")
    }
    suspend fun <T> accountRead(revision: Long = listenerRevision.value, action: suspend () -> T): T {
        currentCoroutineContext().ensureActive(); requireListener(revision)
        val result = MobileYouTubeAccounts.withListener(revision, action)
        currentCoroutineContext().ensureActive(); requireListener(revision)
        return result
    }
    suspend fun <T> accountWrite(revision: Long = listenerRevision.value, action: suspend () -> T): T {
        OfflineSmoke.requireNetwork()
        currentCoroutineContext().ensureActive(); requireListener(revision)
        val result = MobileYouTubeAccounts.withCurrentProfile(revision, action)
        currentCoroutineContext().ensureActive(); requireListener(revision)
        return result
    }
    private fun listenerLaunch(block: suspend CoroutineScope.() -> Unit): Job = listenerScope.launch {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { notice(error.message ?: "The account request could not be completed") }
    }
    private fun changeListener(account: YouTubeAccountState) {
        // Snapshot filenames are bound to the departing identity before queued
        // persistence is dispatched. A late write cannot enter the next profile.
        listenerSnapshots[listenerKey] = liked.value to history.value
        persistSongs(listenerFile("liked"), liked.value)
        persistSongs(listenerFile("history"), history.value)
        listenerJob.cancel()
        homeJob?.cancel(); searchJob?.cancel(); browseJob?.cancel(); libraryJob?.cancel(); likedSyncJob?.cancel()
        // Also clear on the UI dispatcher: an already-dispatched optimistic
        // click can finish between the backend's IO clear and this collector.
        com.music.bitchord.data.LikeState.clear()
        listenerJob = SupervisorJob(scope.coroutineContext[Job])
        listenerScope = CoroutineScope(scope.coroutineContext + listenerJob)
        homeToken = null; searchToken = null; submittedQuery = null
        home.value = UiState.Loading; library.value = UiState.Loading; newReleases.value = UiState.Loading
        topPicks.value = null; topPicksLoading.value = false; loadingRecents.value = false; loadingMoreHome.value = false
        searchResults.value = null; suggestions.value = emptyList(); typeahead.value = emptyList(); query.value = ""
        loadingMoreSearch.value = false; detail.value = null; detailFilter.value = ""; loadingMoreDetail.value = false
        backStack.clear(); canGoBack.value = false; heardThisSession.clear(); alternateVersions.clear()
        listenerKey = account.listenerKey
        val cached = listenerSnapshots[listenerKey]
        liked.value = cached?.first ?: readSongs(listenerFile("liked")) ?: emptyList()
        history.value = cached?.second ?: readSongs(listenerFile("history")) ?: emptyList()
        signedIn.value = account.signedIn
        listenerRevision.value = account.revision
        if (route.value in setOf(Destination.HOME, Destination.LIBRARY, Destination.SEARCH, Destination.DETAILS, Destination.HISTORY)) route.value = Destination.HOME
        refreshHome(); refreshLibrary(); refreshExplore(); refreshNew()
    }

    private fun readSongs(name: String): List<Song>? = DesktopStore.readText(name)?.let { runCatching { json.decodeFromString(SongListSerializer, it) }.getOrNull() }
    private fun persistSongs(name: String, songs: List<Song>) = persistText(name, json.encodeToString(SongListSerializer, songs))
    private inline fun <reified T> read(name: String): T? = DesktopStore.readText(name)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }
    private inline fun <reified T> persist(name: String, value: T) = persistText(name, json.encodeToString(value))
    private fun persistText(name: String, text: String) {
        val version = synchronized(persistenceLock) {
            if (closed) return
            ((persistenceVersions[name] ?: 0) + 1).also { persistenceVersions[name] = it }
        }
        scope.launch(Dispatchers.IO) {
            synchronized(persistenceLock) {
                // A delayed older write must never replace a newer queue or playlist snapshot.
                if (!closed && persistenceVersions[name] == version) DesktopStore.writeText(name, text)
            }
        }
    }
    fun notice(message: String) = host.showMessage(message)
    fun toggleVersion(song: Song) {
        if (switchingVersion.value || player.state.value.song?.videoId != song.videoId || song.localUri != null) return
        if (com.music.bitchord.data.listentogether.ListenTogether.state.value.inParty) { notice("Leave the listening party before changing the recording version"); return }
        val entry = player.state.value.song?.queueEntryId
        switchingVersion.value = true
        listenerLaunch {
            try {
                val resolved = alternateVersions[song.videoId] ?: withTimeoutOrNull(25_000) {
                    accountRead { if (song.isVideo) YtMusicRepository.resolveAudio(song) else YtMusicRepository.resolveVideo(song) }
                }
                if (resolved == null || resolved.videoId == song.videoId) { notice(if (song.isVideo) "No matching audio version was found" else "No matching music video was found"); return@listenerLaunch }
                var current = player.state.value
                if (current.song?.queueEntryId != entry || current.song?.videoId != song.videoId) return@listenerLaunch
                val target = resolved.copy(isVideoOrigin = song.isVideo || resolved.isVideoOrigin)
                val map = accountRead { YtMusicRepository.versionTimeMap(song.videoId, target.videoId) }
                val offset = if (map == null && MobilePlayerSettings.smartVersionAlignment.value) {
                    MobilePlayerSettings.versionAlignmentInProgress.value = true
                    withTimeoutOrNull(35_000) { MobileVersionAligner.findOffsetMs(song, target, resolve = MobileSources::resolve,
                        cancelled = { player.state.value.song?.queueEntryId != entry || player.state.value.song?.videoId != song.videoId }) }
                } else null
                current = player.state.value
                if (current.song?.queueEntryId != entry || current.song?.videoId != song.videoId) return@listenerLaunch
                val position = map?.map(current.positionMs) ?: (current.positionMs + (offset ?: 0)).coerceAtLeast(0)
                if (player.swapToVersion(target, position, entry)) {
                    alternateVersions[song.videoId] = target; alternateVersions[target.videoId] = song
                    host.showVideo.value = true
                    host.manualVideoId.value = target.videoId.takeIf { target.isVideo }
                    if (map == null && offset == null && MobilePlayerSettings.smartVersionAlignment.value) notice("Changed version; no reliable timing match was available")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notice(error.message ?: "Could not change the recording version") }
            finally { switchingVersion.value = false; MobilePlayerSettings.versionAlignmentInProgress.value = false }
        }
    }
    fun navigate(destination: Destination) {
        if (destination != Destination.DETAILS) spotifyJob?.cancel()
        if (route.value != destination) { backStack.addLast(route.value to detail.value); canGoBack.value = true }
        route.value = destination
    }
    fun back() { browseJob?.cancel(); backStack.removeLastOrNull()?.let { route.value = it.first; detail.value = it.second }; canGoBack.value = backStack.isNotEmpty() }
    fun refreshHome() {
        if (OfflineSmoke.active) { home.value = UiState.Success(emptyList()); topPicks.value = emptyList(); return }
        homeJob?.cancel(); home.value = UiState.Loading
        homeJob = listenerLaunch {
            supervisorScope {
                launch {
                    accountRead { YtMusicRepository.home() }.fold(onSuccess = { feed ->
                        publishHome(feed.shelves, prepend = true); homeToken = feed.continuation
                        topPicksLoading.value = true
                        try { topPicks.value = accountRead { TopPicks.build(feed.shelves, TopPicks.LocalTaste(songs = history.value.take(50))) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { topPicks.value = emptyList() }
                        finally { topPicksLoading.value = false }
                    }, onFailure = { if (home.value !is UiState.Success) home.value = UiState.Error(it.message ?: "Home is unavailable") })
                }
                loadingRecents.value = signedIn.value
                if (signedIn.value) launch {
                    try { accountRead { YtMusicRepository.homeRecentlyPlayed() }.onSuccess { it?.let { shelf -> publishHome(listOf(shelf), prepend = true) } } }
                    finally { loadingRecents.value = false }
                }
                YtMusicRepository.HOME_SUPPLEMENT_BROWSE_IDS.forEach { browseId -> launch {
                    accountRead { YtMusicRepository.homeSupplement(browseId) }.onSuccess { publishHome(it) }
                } }
            }
        }
    }
    private fun publishHome(shelves: List<HomeShelf>, prepend: Boolean = false) {
        if (shelves.isEmpty()) return
        val previous = (home.value as? UiState.Success)?.data.orEmpty()
        val combined = if (prepend) shelves + previous else previous + shelves
        home.value = UiState.Success(combined.distinctBy { it.title.lowercase(java.util.Locale.ROOT) }
            .sortedBy { if (it.title == YtMusicRepository.RECENT_TITLE) 0 else 1 })
    }
    fun moreHome() {
        val token = homeToken ?: return
        if (loadingMoreHome.value) return
        loadingMoreHome.value = true
        listenerLaunch { try { accountRead { YtMusicRepository.moreHome(token) }.onSuccess { page ->
            publishHome(page.shelves); homeToken = page.continuation
        }.onFailure { notice(it.message ?: "Could not load more") } } finally { loadingMoreHome.value = false } }
    }
    fun refreshLibrary() {
        if (OfflineSmoke.active) { library.value = UiState.Error("Offline test · use Local music"); return }
        libraryJob?.cancel(); likedSyncJob?.cancel()
        if (!signedIn.value) { library.value = UiState.Error("Sign in to YouTube Music to load your library."); return }
        library.value = UiState.Loading
        libraryJob = listenerLaunch { library.value = accountRead { YtMusicRepository.library() }.fold({ page ->
            liked.update { current -> (current + page.likedSongs).distinctBy(Song::videoId) }
            persistSongs(listenerFile("liked"), liked.value)
            likedSyncJob = listenerLaunch {
                var token = page.likedContinuation
                val seen = mutableSetOf<String>()
                while (token != null && seen.add(token)) {
                    val currentToken = token
                    val next = accountRead {
                        val result = YtMusicRepository.moreSongs(currentToken).getOrNull()
                        currentCoroutineContext().ensureActive()
                        result?.let { com.music.bitchord.data.LikeState.seedLiked(it.songs.mapTo(HashSet(), Song::videoId)) }
                        result
                    } ?: break
                    token = next.continuation
                }
            }
            UiState.Success(page)
        }, { UiState.Error(it.message ?: "Library is unavailable") }) }
    }
    fun refreshExplore() { if (OfflineSmoke.active) { explore.value = UiState.Success(emptyList()); return }; explore.value = UiState.Loading; listenerLaunch { explore.value = accountRead { YtMusicRepository.moodAndGenres() }.fold({ UiState.Success(it) }, { UiState.Error(it.message ?: "Explore is unavailable") }) } }
    fun refreshNew() { if (OfflineSmoke.active) { newReleases.value = UiState.Success(emptyList()); return }; newReleases.value = UiState.Loading; listenerLaunch { newReleases.value = accountRead { YtMusicRepository.homeSupplement("FEmusic_new_releases") }.fold({ UiState.Success(it) }, { UiState.Error(it.message ?: "New releases are unavailable") }) } }
    fun signIn() = DesktopBrowserAuth.youtubeSession(onError = ::notice)
    fun signOut() { scope.launch(Dispatchers.IO) { MobileYouTubeAccounts.signOutCurrent().onFailure { notice(it.message ?: "Could not remove this saved account") } } }
    /** Original mobile MainActivity source-link routing, with desktop local/remote pages. */
    fun openPlaybackSource(song: Song, onReplay: () -> Unit) {
        backStack.clear(); canGoBack.value = false
        when (song.playbackSourceType ?: PlaybackSourceType.QUEUE) {
            PlaybackSourceType.HOME -> navigate(Destination.HOME)
            PlaybackSourceType.SEARCH -> navigate(Destination.SEARCH)
            PlaybackSourceType.HISTORY -> navigate(Destination.HISTORY)
            PlaybackSourceType.REPLAY -> onReplay()
            PlaybackSourceType.EXPLORE -> navigate(Destination.EXPLORE)
            PlaybackSourceType.SHARED_LINK -> openExternal("https://music.youtube.com/watch?v=${song.playbackSourceId ?: song.videoId}")
            PlaybackSourceType.BROWSE -> {
                val id = song.playbackSourceId ?: return
                when (id) {
                    "local:device" -> navigate(Destination.LOCAL)
                    "local:downloads" -> navigate(Destination.DOWNLOADS)
                    "local:webdav", "local:smb" -> navigate(Destination.REMOTE)
                    "FEmusic_library_landing", "FEmusic_library" -> navigate(Destination.LIBRARY)
                    else -> openBrowse(BrowseItem(id, song.playbackSource ?: song.albumName ?: "Collection", "", song.thumbnailUrl,
                        when { id.startsWith("UC") -> BrowseType.ARTIST; id.startsWith("MPRE") -> BrowseType.ALBUM; else -> BrowseType.PLAYLIST }))
                }
            }
            PlaybackSourceType.QUEUE -> Unit // Shared NowPlayingScreen opens its own queue for this case.
        }
        backStack.clear(); canGoBack.value = false
    }
    fun search(text: String = query.value) {
        if (text.isBlank()) return
        submittedQuery = text; query.value = text; suggestions.value = emptyList(); typeahead.value = emptyList()
        navigate(Destination.SEARCH); searchJob?.cancel(); searchResults.value = UiState.Loading
        searchToken = null
        searchJob = listenerLaunch {
            if (sourceSearch.value) {
                try { searchResults.value = UiState.Success(MobileSources.search(text).map { SearchResult.Track(it) }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { searchResults.value = UiState.Error(error.message ?: "Source search failed") }
            } else accountRead { YtMusicRepository.searchPage(text, filter.value) }.fold(
                { searchResults.value = UiState.Success(it.rows); searchToken = it.continuation },
                { searchResults.value = UiState.Error(it.message ?: "Search failed") })
        }
    }
    fun moreSearch() {
        val token = searchToken ?: return
        if (loadingMoreSearch.value) return
        loadingMoreSearch.value = true
        listenerLaunch { try { accountRead { YtMusicRepository.searchContinuation(token, filter.value) }.onSuccess { page ->
            searchResults.value = UiState.Success((searchResults.value as? UiState.Success)?.data.orEmpty() + page.rows); searchToken = page.continuation
        } } finally { loadingMoreSearch.value = false } }
    }
    fun openShelf(item: ShelfItem, source: String = "") {
        val id = item.browseId
        if (id != null) openBrowse(BrowseItem(id, item.title, item.subtitle, item.thumbnailUrl,
            when { id.startsWith("UC") -> BrowseType.ARTIST; id.startsWith("MPRE") -> BrowseType.ALBUM; else -> BrowseType.PLAYLIST }))
        else item.toSong()?.let { player.play(it.copy(playbackSource = source, playbackSourceType =
            if (route.value == Destination.EXPLORE || route.value == Destination.NEW) PlaybackSourceType.EXPLORE else PlaybackSourceType.HOME)) }
    }
    fun showCollection(page: DetailPage) { navigate(Destination.DETAILS); detail.value = page }
    fun openSpotifyPlaylist(playlist: SpotifyPlaylist) {
        spotifyJob?.cancel(); browseJob?.cancel()
        val id = SPOTIFY_PAGE_PREFIX + playlist.id
        showCollection(DetailPage(id, playlist.name, "Spotify", playlist.imageUrl, UiState.Loading, BrowseType.PLAYLIST))
        spotifyJob = scope.launch {
            try {
                val tracks = SpotifyLibrary.tracks(playlist.id) { partial ->
                    detail.update { page -> if (page?.browseId != id) page else page.copy(
                        subtitle = "Spotify · loading ${partial.size} tracks", songs = UiState.Success(partial.map { track ->
                            Song(SPOTIFY_PENDING_PREFIX + track.id, track.title, track.artist, track.imageUrl, albumName = track.album)
                        })) }
                }
                val semaphore = Semaphore(4)
                val resolved = tracks.map { track -> Song(SPOTIFY_PENDING_PREFIX + track.id, track.title, track.artist, track.imageUrl, albumName = track.album) }.toMutableList()
                var matched = 0; var checked = 0
                coroutineScope {
                    tracks.mapIndexed { index, track -> launch {
                        val answer = semaphore.withPermit { SpotifyImporter.matchTrack(track) }
                        ensureActive()
                        resolved[index] = answer ?: resolved[index].copy(videoId = SPOTIFY_MISSING_PREFIX + track.id)
                        checked++; if (answer != null) matched++
                        detail.update { page -> if (page?.browseId != id) page else page.copy(songs = UiState.Success(resolved.toList()),
                            subtitle = "Spotify · $matched matched / ${tracks.size} tracks${if (checked < tracks.size) " · matching $checked/${tracks.size}" else ""}") }
                    } }.joinAll()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { detail.update { page -> if (page?.browseId != id) page else page.copy(songs = UiState.Error(error.message ?: "Spotify playlist is unavailable")) } }
        }
    }
    private fun recordSearch(entry: SearchHistoryEntity) {
        recentSearches.update { (listOf(entry) + it.filterNot { previous -> previous.id == entry.id }).take(100) }
        persist("search-history.json", recentSearches.value)
    }
    fun playSearchSong(song: Song) = playFromSearch(listOf(song), 0)
    fun playFromSearch(songs: List<Song>, index: Int) {
        songs.getOrNull(index)?.let { recordSearch(SearchHistoryEntity(it.videoId, it.title, it.artist, it.thumbnailUrl, EntityType.TRACK)) }
        player.playSongs(songs.map { it.copy(playbackSource = "Search", playbackSourceType = PlaybackSourceType.SEARCH) }, index)
    }
    fun openHistory(entry: SearchHistoryEntity) {
        entry.toSong()?.let { playSearchSong(it); return }
        if (entry.id.startsWith("q:")) { search(entry.title); return }
        openBrowse(BrowseItem(entry.id, entry.title, entry.subtitle, entry.artworkUrl, when (entry.entityType) {
            EntityType.ARTIST -> BrowseType.ARTIST; EntityType.ALBUM -> BrowseType.ALBUM; EntityType.PLAYLIST -> BrowseType.PLAYLIST; else -> BrowseType.OTHER
        }))
    }
    fun openBrowse(item: BrowseItem) {
        spotifyJob?.cancel()
        val localFolder = com.music.bitchord.data.LocalGroups.folderOf(item.browseId)
        if (localFolder != null) {
            val songs = if (localFolder == "local:downloads") MobileFiles.downloads.value else MobileFiles.localSongs.value
            com.music.bitchord.data.LocalGroups.groupOf(item.browseId, songs)?.let { group ->
                showCollection(DetailPage(group.browseId, group.title, group.subtitle, group.thumbnailUrl, UiState.Success(group.songs), group.type))
            }
            return
        }
        if (item.browseId.startsWith("local:download:")) {
            navigate(Destination.DOWNLOADS); return
        }
        if (route.value == Destination.SEARCH) recordSearch(SearchHistoryEntity(item.browseId, item.title, item.subtitle, item.thumbnailUrl,
            when (item.type) { BrowseType.ARTIST -> EntityType.ARTIST; BrowseType.ALBUM -> EntityType.ALBUM; BrowseType.PLAYLIST -> EntityType.PLAYLIST; else -> EntityType.OTHER }))
        if (item.browseId == "device:liked") { showCollection(DetailPage(item.browseId, "Liked songs", "On this computer", null, UiState.Success(liked.value), BrowseType.PLAYLIST)); return }
        playlists.value.firstOrNull { it.id == item.browseId }?.let { list ->
            showCollection(DetailPage(list.id, list.title, "On this computer", list.songs.firstOrNull()?.thumbnailUrl, UiState.Success(list.songs), BrowseType.PLAYLIST)); return
        }
        browseJob?.cancel()
        showCollection(DetailPage(item.browseId, item.title, item.subtitle, item.thumbnailUrl, UiState.Loading, item.type))
        browseJob = listenerLaunch {
            if (item.type == BrowseType.ARTIST) {
                accountRead { YtMusicRepository.artistPage(item.browseId) }.fold({ page ->
                    detail.value = DetailPage(item.browseId, page.name ?: item.title, page.monthlyListenerCount ?: item.subtitle,
                        page.thumbnailUrl ?: item.thumbnailUrl, UiState.Success(page.songs), BrowseType.ARTIST,
                        sections = page.sections, description = page.description, subscription = page.subscription)
                }, { detail.value = detail.value?.copy(songs = UiState.Error(it.message ?: "Artist is unavailable")) })
            } else accountRead { YtMusicRepository.browseSongs(item.browseId) }.fold({ page ->
                detail.value = DetailPage(item.browseId, page.header?.title ?: item.title, page.header?.subtitle ?: item.subtitle,
                    page.header?.thumbnailUrl ?: item.thumbnailUrl, UiState.Success(page.songs), item.type,
                    continuation = page.continuation, library = page.library, description = page.description)
            }, { detail.value = detail.value?.copy(songs = UiState.Error(it.message ?: "Collection is unavailable")) })
        }
    }
    fun moreDetail() {
        val current = detail.value ?: return
        val token = current.continuation ?: return
        if (loadingMoreDetail.value) return
        loadingMoreDetail.value = true
        listenerLaunch { try { accountRead { YtMusicRepository.moreSongs(token) }.onSuccess { page ->
            if (detail.value?.browseId == current.browseId) detail.value = current.copy(songs = UiState.Success((current.songs as? UiState.Success)?.data.orEmpty() + page.songs), continuation = page.continuation)
        } } finally { loadingMoreDetail.value = false } }
    }
    fun topPick(pick: TopPick, source: String) {
        if (pick.kind == TopPickKind.LATEST_RELEASE && pick.browseId != null) {
            openBrowse(BrowseItem(pick.browseId!!, pick.title, pick.subtitle, pick.images.firstOrNull(), BrowseType.ALBUM)); return
        }
        listenerLaunch { accountRead { TopPicks.queue(pick) }.onSuccess { player.playSongs(it.map { song -> song.copy(playbackSource = source, playbackSourceType = PlaybackSourceType.HOME) }) }.onFailure { notice(it.message ?: "Station is unavailable") } }
    }
    fun startMix(song: Song) { listenerLaunch { accountRead { YtMusicRepository.radio(song.videoId) }.onSuccess { player.playSongs(it) }.onFailure { notice(it.message ?: "Mix is unavailable") } } }
    suspend fun collectionSongs(page: DetailPage): List<Song> {
        val initial = (page.songs as? UiState.Success)?.data ?: when {
            page.browseId == "device:liked" -> liked.value
            page.browseId.startsWith("local:") -> playlists.value.firstOrNull { it.id == page.browseId }?.songs.orEmpty()
            page.type == BrowseType.ARTIST -> accountRead { YtMusicRepository.artistPage(page.browseId) }.getOrThrow().songs
            else -> accountRead { YtMusicRepository.allSongs(page.browseId) }.getOrThrow()
        }
        val all = initial.toMutableList()
        var continuation = page.continuation
        val visited = mutableSetOf<String>()
        while (continuation != null && visited.add(continuation)) {
            val token = continuation
            val next = accountRead { YtMusicRepository.moreSongs(token) }.getOrThrow()
            all += next.songs; continuation = next.continuation
        }
        if (detail.value?.browseId == page.browseId) detail.value = detail.value?.copy(songs = UiState.Success(all), continuation = null)
        return all.filterNot(Song::isUnresolvedSpotify)
    }
    fun playCollection(page: DetailPage, shuffle: Boolean = false) { listenerLaunch { runCatching {
        val songs = collectionSongs(page).map { it.copy(playbackSource = page.title, playbackSourceType = PlaybackSourceType.BROWSE, playbackSourceId = page.browseId) }
        player.playSongs(if (shuffle) songs.shuffled() else songs)
    }.onFailure { notice(it.message ?: "Could not play this collection") } } }
    fun toggleSaved(page: DetailPage) { val libraryState = page.library ?: return; listenerLaunch {
        accountWrite { YtMusicRepository.setSaved(libraryState.playlistId, !libraryState.saved) }
            .onSuccess { if (detail.value?.browseId == page.browseId) detail.value = detail.value?.copy(library = libraryState.copy(saved = !libraryState.saved)); refreshLibrary() }
            .onFailure { notice(it.message ?: "Could not update library") }
    } }
    fun toggleLike(song: Song) {
        val revision = listenerRevision.value
        if (!isListener(revision)) return
        val wasLiked = isLiked(song.videoId)
        com.music.bitchord.data.LikeState.set(song.videoId, if (wasLiked) LikeStatus.INDIFFERENT else LikeStatus.LIKE)
        liked.update { if (wasLiked) it.filterNot { row -> row.videoId == song.videoId } else listOf(song) + it }
        persistSongs(listenerFile("liked"), liked.value)
        if (signedIn.value && song.localUri == null && !song.videoId.contains(':')) listenerLaunch { accountWrite(revision) { YtMusicRepository.rate(song.videoId, if (wasLiked) LikeStatus.INDIFFERENT else LikeStatus.LIKE) }
            .onFailure { notice("Saved on this computer; account sync failed: ${it.message}") } }
    }
    fun isLiked(id: String): Boolean = com.music.bitchord.data.LikeState.overrides.value[id]?.let { it == LikeStatus.LIKE } ?: liked.value.any { it.videoId == id }
    suspend fun savePlaylist(title: String, songs: List<Song>, toAccount: Boolean = false) {
        require(title.isNotBlank()) { "Enter a playlist name" }
        if (toAccount) {
            val online = songs.filter { it.localUri == null && !it.videoId.contains(':') }.map(Song::videoId)
            require(online.size == songs.size) { "Local and addon tracks belong in a playlist on this computer" }
            accountWrite { YtMusicRepository.createPlaylist(title.trim(), PlaylistPrivacy.PRIVATE, online) }.getOrThrow(); refreshLibrary()
        } else {
            playlists.update { it + LocalPlaylist(title = title.trim(), songs = songs) }
            persist("playlists.json", playlists.value)
        }
    }
    fun addToPlaylist(id: String, song: Song) { playlists.update { lists -> lists.map { if (it.id == id) it.copy(songs = it.songs + song) else it } }; persist("playlists.json", playlists.value) }
    fun replaceLocalPlaylistSongs(id: String, expected: List<Song>, updated: List<Song>) {
        playlists.update { lists ->
            val current = lists.firstOrNull { it.id == id } ?: error("This local playlist was removed. Reopen the library.")
            check(current.songs == expected) { "This playlist changed while it was being edited. Reload it before saving." }
            lists.map { if (it.id == id) it.copy(songs = updated.toList()) else it }
        }
        persist("playlists.json", playlists.value)
        detail.update { page -> if (page?.browseId == id) page.copy(songs = UiState.Success(updated.toList())) else page }
    }
    fun renamePlaylist(id: String, title: String) { if (title.isBlank()) return; playlists.update { lists -> lists.map { if (it.id == id) it.copy(title = title.trim()) else it } }; persist("playlists.json", playlists.value) }
    fun deletePlaylist(id: String) { playlists.update { it.filterNot { list -> list.id == id } }; persist("playlists.json", playlists.value); if (detail.value?.browseId == id) back() }
    fun removeHistory(id: String) { recentSearches.update { it.filterNot { item -> item.id == id } }; persist("search-history.json", recentSearches.value) }
    fun clearSearchHistory() { recentSearches.value = emptyList(); persist("search-history.json", recentSearches.value) }
    override fun close() {
        synchronized(persistenceLock) { if (closed) return }
        integrations.close()
        MobileFiles.close()
        MobileRemote.close()
        DesktopBrowserAuth.close()
        browseJob?.cancel(); searchJob?.cancel(); homeJob?.cancel(); spotifyJob?.cancel(); libraryJob?.cancel(); likedSyncJob?.cancel(); listenerJob.cancel(); scope.cancel()
        val state = player.state.value
        synchronized(persistenceLock) {
            closed = true
            DesktopStore.writeText("queue.json", json.encodeToString(SavedQueue(state.queue, state.queueIndex, state.positionMs, state.volume, state.speed, state.shuffle, state.repeat)))
            DesktopStore.writeText(listenerFile("liked"), json.encodeToString(SongListSerializer, liked.value))
            DesktopStore.writeText(listenerFile("history"), json.encodeToString(SongListSerializer, history.value))
            DesktopStore.writeText("playlists.json", json.encodeToString(playlists.value))
            DesktopStore.writeText("search-history.json", json.encodeToString(recentSearches.value))
        }
        player.close()
    }
}
