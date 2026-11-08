package com.music.bitchord.reworked

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.canvas.*
import com.music.bitchord.data.lyrics.*
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.*
import com.music.bitchord.ui.AppUiHost
import com.music.bitchord.ui.player.*
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*

/** Desktop persistence for exactly the settings read by the mobile shared player. */
internal object MobilePlayerSettings : PlayerSettingsSource {
    private val prefs get() = DesktopStore.preferences
    private fun flag(name: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(name, default))
    private inline fun <reified E : Enum<E>> choice(name: String, default: E) =
        MutableStateFlow(runCatching { enumValueOf<E>(prefs.get(name, default.name)) }.getOrDefault(default))
    override val animatedCanvas = flag("animated_canvas", true)
    override val audioQualityCellular = choice("quality_cellular", AudioQuality.MEDIUM)
    override val audioQualityWifi = choice("quality_wifi", AudioQuality.HIGH)
    override val canvasOverCellular = flag("canvas_cellular", true)
    override val fullBleedArtwork = flag("full_bleed", false)
    override val hideSongStatus = flag("hide_song_status", false)
    override val showQueueHistory = flag("queue_history", true)
    override val hideVolumeBar = flag("hide_volume", false)
    override val hideStatusBar = MutableStateFlow(false)
    override val lastPlayerScreen = choice("player_screen", LastPlayerScreen.MAIN)
    override val playerTheme = choice("player_theme", PlayerTheme.DEFAULT)
    override val lyricsBlur = flag("lyrics_blur", true)
    override val lyricsOffsetMs = MutableStateFlow(prefs.getInt("lyrics_offset", 0).coerceIn(-5000, 5000))
    override val lyricsSourceOrder = MutableStateFlow(LyricsSource.ordered(prefs.get("lyrics_sources", "").split(',').mapNotNull { name -> LyricsSource.entries.find { it.name == name } }))
    override val meteredConnection = MutableStateFlow<Boolean?>(false)
    override val streamCeilingOverride = MutableStateFlow<AudioQuality?>(null)
    override val preferUsbDac = MutableStateFlow(false)
    override val prioritizeSpotifyCanvas = flag("spotify_canvas_first", false)
    override val reduceAnimation = flag("reduce_animation", false)
    override val reduceDynamicBlur = flag("reduce_dynamic_blur", false)
    override val disableBlendEffects = flag("disable_blend", false)
    override val showNerdStats = flag("nerd_stats", false)
    override val showListenTogetherButton = flag("show_listen_together", true)
    override val smartAnalysis = MutableStateFlow(SmartAnalysis())
    override val automixDetail = MutableStateFlow<String?>(null)
    override val smartFadeEnabled = MutableStateFlow(false)
    override val smartMixInProgress = MutableStateFlow(false)
    override val smartMixBlend = MutableStateFlow<MixBlend?>(null)
    override val smartMixView = MutableStateFlow<MixTransitionView?>(null)
    override val smartMixProgress = MutableStateFlow(0f)
    override val smartMixPreview = MutableStateFlow<MixTransitionView?>(null)
    override val smartTransitionWindow = MutableStateFlow<TransitionWindow?>(null)
    override val spotifyCanvasAutoHide = flag("canvas_autohide", true)
    override val syncedLyrics = flag("synced_lyrics", true)
    override val translationLanguage = MutableStateFlow(prefs.get("translation_language", "en"))
    override val versionAlignmentInProgress = MutableStateFlow(false)
    val prioritizeSyllableSync = flag("syllable_sync_priority", false)
    val hideRecents = flag("hide_recents", false)
    val hideHomeVideos = flag("hide_home_videos", false)
    val hideTopPicks = flag("hide_top_picks", false)
    val hideHomeMoods = flag("hide_home_moods", false)
    val squareVideoThumbnails = flag("square_video_thumbnails", false)
    val dontRepeatSuggestions = flag("dont_repeat_suggestions", false)
    val smartVersionAlignment = flag("smart_version_alignment", true)
    val mixViewOpen = MutableStateFlow(false)
    override fun setLastPlayerScreen(value: LastPlayerScreen) { lastPlayerScreen.value = value; prefs.put("player_screen", value.name) }
    override fun setLyricsOffsetMs(value: Int) { lyricsOffsetMs.value = value.coerceIn(-5000,5000); prefs.putInt("lyrics_offset", lyricsOffsetMs.value) }
    override fun setMixViewOpen(open: Boolean) { mixViewOpen.value = open }
    fun setPlayerTheme(value: PlayerTheme) { playerTheme.value = value; prefs.put("player_theme", value.name) }
    fun setQuality(value: AudioQuality) { audioQualityWifi.value = value; audioQualityCellular.value = value; prefs.put("quality_wifi", value.name); prefs.put("quality_cellular", value.name) }
    fun setTranslation(value: String) { translationLanguage.value = value; prefs.put("translation_language", value) }
    fun setLyricsOrder(value: List<LyricsSource>) { lyricsSourceOrder.value = LyricsSource.ordered(value); prefs.put("lyrics_sources", value.joinToString(",") { it.name }) }
    fun flag(name: String, flow: MutableStateFlow<Boolean>, value: Boolean) { prefs.putBoolean(name, value); flow.value = value }
}

/** New Windows/Linux boundary below the unchanged mobile NowPlayingScreen. */
internal class MobilePlayerHost(private val player: DesktopPlayback) : PlayerHost, AppUiHost {
    override val settings = MobilePlayerSettings
    val messages = MutableStateFlow<String?>(null)
    override fun cachedCanvas(song: Song): CanvasArtwork? = CanvasRepository.cached(song)
    override suspend fun canvasFor(song: Song): CanvasArtwork? =
        if (!OfflineSmoke.active && settings.animatedCanvas.value) CanvasRepository.canvasFor(song) else null
    @Composable override fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) = MobileCanvasVideo(spec, modifier)
    override val musicVideoSupported = !OfflineSmoke.active
    override val musicVideoAspect = MutableStateFlow(16f / 9f)
    override val musicVideoQuality = MutableStateFlow(MusicVideoQualityUi())
    val showVideo = mutableStateOf(true)
    val manualVideoId = mutableStateOf<String?>(null)
    override fun showsMusicVideoPicture(videoId: String) = showVideo.value && (manualVideoId.value == videoId || !MobilePlaybackPolicy.preferMusicOnly.value || com.music.bitchord.data.listentogether.ListenTogether.state.value.inParty)
    override fun setMusicVideoQuality(height: Int?) { musicVideoQuality.update { it.copy(held = height) } }
    @Composable override fun MusicVideo(videoId: String, fit: Boolean, onRenderedChanged: (Boolean) -> Unit, modifier: Modifier) =
        MobileMusicVideo(videoId, player, musicVideoQuality, musicVideoAspect, fit, onRenderedChanged, modifier)
    @Composable override fun rememberRemoteArtworkUrl(song: Song?) = song?.thumbnailUrl
    private val volumeLevel = MutableStateFlow(1f)
    override val volume = object : SystemVolume {
        override val level: StateFlow<Float> = volumeLevel
        override fun set(level: Float) { player.setVolume(level); volumeLevel.value = level.coerceIn(0f,1f) }
        override fun refresh() { volumeLevel.value = player.state.value.volume }
    }
    @Composable override fun rememberAudioOutputs(): List<AudioOutputDevice> {
        val state by player.state.collectAsState()
        return listOf(AudioOutputDevice(-1, "System default", AudioOutputKind.OTHER, state.outputDeviceId == null)) +
            player.outputDevices().map { AudioOutputDevice(it.id, it.name, AudioOutputKind.OTHER, state.outputDeviceId == it.id) }
    }
    @Composable override fun rememberOutputPicker(onOpen: () -> Unit) = onOpen
    override fun selectAudioOutput(id: Int?) { player.selectOutput(id?.takeIf { it >= 0 }) }
    override val outputFormat = MutableStateFlow(OutputFormatUi())
    override val party = MutableStateFlow(PartyUi())
    override suspend fun translateLyrics(trackId: String, lines: List<LyricLine>, targetLanguageTag: String) =
        when (val result = LyricsTranslation.translate(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.Result.Translated -> LyricsTranslationResult.Translated(result.lines)
            is LyricsTranslation.Result.SameLanguage -> LyricsTranslationResult.SameLanguage(result.language)
            LyricsTranslation.Result.Unavailable -> LyricsTranslationResult.Unavailable
        }
    override suspend fun romanizeLyrics(trackId: String, lines: List<LyricLine>, targetLanguageTag: String) =
        when (val result = LyricsTranslation.romanize(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.RomanizationResult.Romanized -> LyricsRomanizationResult.Romanized(result.lines)
            LyricsTranslation.RomanizationResult.AlreadyRomanized -> LyricsRomanizationResult.AlreadyRomanized
            LyricsTranslation.RomanizationResult.Unavailable -> LyricsRomanizationResult.Unavailable
        }
    override fun showMessage(message: String) { messages.value = message }
    @Composable override fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit) {
        val playback by player.state.collectAsState()
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Audio output") },
            text = { Text("Source → FFmpeg decode → selected audio device\nVolume: ${(playback.volume * 100).toInt()}%\n${playback.format.orEmpty()}\nExclusive / bit-perfect output is not active.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    }
    override val swipeToPlayNext = MutableStateFlow(false)
    override val pinnedPlaylists = MutableStateFlow(DesktopStore.preferences.get("pinned_playlists", "").split('\n').filter(String::isNotBlank))
    override val librarySort = MutableStateFlow(runCatching { LibrarySort.valueOf(DesktopStore.preferences.get("library_sort", "DEFAULT")) }.getOrDefault(LibrarySort.DEFAULT))
    fun setLibrarySort(value: LibrarySort) { librarySort.value = value; DesktopStore.preferences.put("library_sort", value.name) }
    val downloaded = MutableStateFlow<Set<String>>(emptySet())
    @Composable override fun downloadedIds(): Set<String> { val ids by downloaded.collectAsState(); return ids }
    override suspend fun releaseMotion(album: String, artist: String): CanvasArtwork? =
        if (!OfflineSmoke.active && settings.animatedCanvas.value) CanvasRepository.canvasForAlbum(album, artist) else null
    override suspend fun artistMotion(browseId: String, artistName: String): CanvasArtwork? {
        if (OfflineSmoke.active || !settings.animatedCanvas.value) return null
        val revision = MobileYouTubeAccounts.state.value.revision
        val art = MobileYouTubeAccounts.withListener(revision) {
            val answer = AppleArtistArtRepository.artFor(browseId, artistName) {
                YtMusicRepository.artistPage(browseId).getOrNull()?.let { page -> page.songs.map { it.title } + page.sections.flatMap { it.items }.map { it.title } }.orEmpty()
            }
            // The original provider treats catalogue errors as misses; cancellation must
            // still leave this listener before a profile switch may publish its result.
            currentCoroutineContext().ensureActive()
            answer
        }
        if (revision != MobileYouTubeAccounts.state.value.revision) throw CancellationException("The YouTube profile changed during the artist lookup")
        return art?.videoUrl?.let { CanvasArtwork(it, artist = artistName) }
    }
    fun togglePin(id: String) {
        pinnedPlaylists.update { if (id in it) it - id else listOf(id) + it }
        DesktopStore.preferences.put("pinned_playlists", pinnedPlaylists.value.joinToString("\n"))
    }
}
