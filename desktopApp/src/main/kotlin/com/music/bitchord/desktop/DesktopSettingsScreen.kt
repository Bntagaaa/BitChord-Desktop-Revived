package com.music.bitchord.desktop

import com.music.bitchord.desktop.icons.DesktopIcons

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.bitchord_logo_original
import bitchord.desktopapp.generated.resources.logo
import org.jetbrains.compose.resources.painterResource
import com.music.bitchord.desktop.DesktopSettingsPage.*
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.settings.PlayerTheme
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.screens.desktopTopScrollFade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

@Composable
internal fun DesktopSettingsScreen(
    page: DesktopSettingsPage,
    bottomScrollInset: Dp,
    onOpenPage: (DesktopSettingsPage) -> Unit,
    onBack: () -> Unit,
    scroll: DesktopSettingsScroll,
    updater: DesktopUpdateService?,
    query: String,
    onQueryChange: (String) -> Unit,
    autoplay: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    automix: Boolean,
    onAutomixChange: (Boolean) -> Unit,
    automixArtwork: Boolean,
    onAutomixArtworkChange: (Boolean) -> Unit,
    automixMiniArtwork: Boolean,
    onAutomixMiniArtworkChange: (Boolean) -> Unit,
    automixDuration: DesktopAutomixDuration,
    onAutomixDurationChange: (DesktopAutomixDuration) -> Unit,
    automixPerformance: AutomixPerformanceMode,
    onAutomixPerformanceChange: (AutomixPerformanceMode) -> Unit,
    shuffle: Boolean,
    onShuffleChange: (Boolean) -> Unit,
    repeatMode: DesktopRepeatMode,
    onRepeatModeChange: (DesktopRepeatMode) -> Unit,
    playbackSpeed: Float,
    onPlaybackSpeedChange: (Float) -> Unit,
    crossfadeSeconds: Int,
    onCrossfadeSecondsChange: (Int) -> Unit,
    animatedCanvas: Boolean,
    onAnimatedCanvasChange: (Boolean) -> Unit,
    spotifyCanvasReady: Boolean,
    dontRepeatSuggestions: Boolean,
    onDontRepeatSuggestionsChange: (Boolean) -> Unit,
    filterNonMusicAudio: Boolean,
    onFilterNonMusicAudioChange: (Boolean) -> Unit,
    onChooseLocalMusicFolder: (onChosen: () -> Unit) -> Unit,
    onLocalMusicFolderChanged: () -> Unit,
    syncedLyrics: Boolean,
    onSyncedLyricsChange: (Boolean) -> Unit,
    lyricsBlur: Boolean,
    onLyricsBlurChange: (Boolean) -> Unit,
    /** The sources that will actually be asked, in the order they are asked. */
    enabledLyricsSources: List<String>,
    showNerdStats: Boolean,
    onShowNerdStatsChange: (Boolean) -> Unit,
    showBestPart: Boolean,
    onShowBestPartChange: (Boolean) -> Unit,
    fullBleedArtwork: Boolean,
    onFullBleedArtworkChange: (Boolean) -> Unit,
    trayIconEnabled: Boolean,
    closeToTray: Boolean,
    onCloseToTrayChange: (Boolean) -> Unit,
    onTrayIconChange: (Boolean) -> Unit,
    spatialAudio: Boolean,
    onSpatialAudioChange: (Boolean) -> Unit,
    dolbyAtmos: Boolean,
    onDolbyAtmosChange: (Boolean) -> Unit,
    skipSilence: Boolean,
    onSkipSilenceChange: (Boolean) -> Unit,
    outputPrecision: String,
    onOutputPrecisionChange: (String) -> Unit,
    outputSummary: String,
    downloadQuality: String,
    onDownloadQualityChange: (String) -> Unit,
    audioQuality: DesktopAudioQuality,
    onAudioQualityChange: (DesktopAudioQuality) -> Unit,
    sleepAfterTrack: Boolean,
    sleepRemainingMs: Long?,
    onSleepTimerCycle: () -> Unit,
    sourceConfigs: List<DesktopSourceConfig>,
    sourceStatus: Map<String, String>,
    onSourceEnabledChange: (DesktopSourceConfig, Boolean) -> Unit,
    onSaveSource: (DesktopSourceConfig) -> Unit,
    onRemoveSource: (DesktopSourceConfig) -> Unit,
    onMoveSource: (DesktopSourceConfig, Int) -> Unit,
    onTestSource: (DesktopSourceConfig) -> Unit,
) {
    var editingSource by remember { mutableStateOf<DesktopSourceConfig?>(null) }
    val sourceProbeKey = sourceConfigs
        .filter { it.kind.needsServer && it.isComplete }
        .joinToString { "${it.id}@${it.baseUrl}" }
    val searching = page == MAIN && query.isNotBlank()
    if (page == SOURCES) LaunchedEffect(sourceProbeKey) {
        sourceConfigs
            .filter { it.kind.needsServer && it.isComplete }
            .forEach(onTestSource)
    }
    val settingsQuery = remember(query, searching) { DesktopSettingsQuery(if (searching) query else "") }
    val outputOptions = remember { linkedMapOf("PCM_16" to "16-bit PCM", "FLOAT_32" to "32-bit float") }
    val downloadOptions = remember { linkedMapOf("STANDARD" to "Standard", "HIGH" to "High", "LOSSLESS" to "Lossless") }
    val audioSearch = DesktopAudioQuality.entries.joinToString { "${it.label} ${it.detail}" } + " streaming bitrate source ceiling"
    val automixSearch = AutomixPerformanceMode.entries.joinToString { "${it.label()} ${it.detail()}" }
    val fontSearch = DesktopFontMode.entries.joinToString { "${fontLabel(it)} ${fontDetail(it)}" } + " typeface typography"
    val languageSearch = remember { DesktopStrings.available.joinToString { it.label } + " locale" }
    val listState = when {
        searching -> scroll.results
        page == MAIN -> scroll.overview
        else -> scroll.forPage(page)
    }
    when (page) {
        AUDIO_QUALITY -> DesktopSettingsChoicePage(
            page.title, DesktopAudioQuality.entries, audioQuality, listState, { it.label }, { it.detail },
            footer = "Audio quality determines which streaming sources can play.",
        ) { onAudioQualityChange(it); onBack() }
        DOWNLOAD_QUALITY -> DesktopSettingsChoicePage(
            page.title, downloadOptions.keys.toList(), downloadQuality, listState, { downloadOptions.getValue(it) },
        ) { onDownloadQualityChange(it); onBack() }
        OUTPUT_PRECISION -> DesktopSettingsChoicePage(
            page.title, outputOptions.keys.toList(), outputPrecision, listState, { outputOptions.getValue(it) },
            footer = "Playing at $outputSummary",
        ) { onOutputPrecisionChange(it); onBack() }
        AUTOMIX_PERFORMANCE -> DesktopSettingsChoicePage(
            page.title, AutomixPerformanceMode.entries, automixPerformance, listState,
            { it.label() }, { it.detail() },
            footer = DesktopStrings["automix_performance_subtitle", "Sets how much CPU background analysis may use"],
        ) { onAutomixPerformanceChange(it); onBack() }
        FONT -> DesktopSettingsChoicePage(
            page.title, DesktopFontMode.entries, DesktopFontSettings.mode, listState, ::fontLabel, ::fontDetail,
        ) { DesktopFontSettings.selectMode(it); onBack() }
        APP_LANGUAGE -> {
            val chosen by DesktopStrings.language.collectAsState()
            DesktopSettingsChoicePage(
                page.title, DesktopStrings.available, DesktopStrings.available.firstOrNull { it.tag == chosen }
                    ?: DesktopStrings.available.first(), listState, { it.label },
            ) { DesktopStrings.setLanguage(it.tag); onBack() }
        }
        else -> Unit
    }
    if (page in listOf(AUDIO_QUALITY, DOWNLOAD_QUALITY, OUTPUT_PRECISION, AUTOMIX_PERFORMANCE, FONT, APP_LANGUAGE)) return

    val directSource by DesktopDirectSource.enabled.collectAsState()
    val dolbyAtmosEnabled = DesktopCodecs.supportsDolbyAtmos
    val dolbyAtmosSubtitle = if (dolbyAtmosEnabled) {
        DesktopStrings["dolby_atmos_subtitle", "Play the more immersive, surround version of a song when there is one."]
    } else {
        DesktopStrings["dolby_atmos_unavailable", "This device can't play Atmos, so these songs play in their usual version"]
    }

    val sections = DesktopSettingsSections(page, searching, settingsQuery)
    if (searching) {
        sections.section(INTEGRATIONS) {
            navigationRow("YouTube Music", "Manage YouTube accounts, sign in or switch accounts") { onOpenPage(INTEGRATIONS) }
            navigationRow("Spotify", "Connect, switch or disconnect your Spotify account") { onOpenPage(INTEGRATIONS) }
            navigationRow("Discord Rich Presence", "Show what I am playing, lead with the song, customize the card, status, activity, name, buttons and token") {
                onOpenPage(INTEGRATIONS)
            }
            navigationRow("Last.fm", "Connect, disconnect, scrobble tracks, primary artist, now playing, and scrobble timing") { onOpenPage(INTEGRATIONS) }
            navigationRow("ListenBrainz", "Connect with a token, disconnect and scrobble the primary artist") { onOpenPage(INTEGRATIONS) }
        }
        sections.section(LISTEN_TOGETHER) {
            navigationRow(LISTEN_TOGETHER.title, "Create or join a room by code, nickname, party size, custom server, queue and host controls") { onOpenPage(LISTEN_TOGETHER) }
        }
        sections.section(AUDIO_QUALITY) {
            toggle(
                "Direct source stream",
                "Start on a higher priority source; YouTube remains the fallback.",
                directSource,
                DesktopDirectSource::setEnabled,
            )
            choice(AUDIO_QUALITY.title, audioQuality.label, audioSearch, icon = DesktopIcons.Settings.Wifi) { onOpenPage(AUDIO_QUALITY) }
            toggle(
                DesktopStrings["dolby_atmos", "Dolby Atmos"],
                dolbyAtmosSubtitle,
                dolbyAtmos && dolbyAtmosEnabled,
                onDolbyAtmosChange,
                icon = DesktopIcons.Audio.SurroundSound,
                enabled = dolbyAtmosEnabled,
            )
        }
        sections.section(LICENSES) {
            navigationRow(LICENSES.title, "About BitChord and its open-source dependencies") { onOpenPage(LICENSES) }
        }
    }

    sections.section(SOURCES) {
        val sourcesHeading = DesktopStrings["sources_order_header", "Sources · tried in this order"]
        val orderedSources = sourceConfigs.inSourceOrder()
        val userAddedIds = orderedSources.filter(DesktopSourceConfig::isUserAdded).map(DesktopSourceConfig::id)
        orderedSources.forEachIndexed { index, config ->
            row(
                config.displayName, sourcesHeading, config.kind.detail, sourceStatus[config.id],
                "streaming download reorder edit test", icon = true, id = config.id,
            ) {
                DesktopSourceSettingsRow(
                    position = index + 1,
                    config = config,
                    status = sourceStatus[config.id],
                    skippedByQuality = config.enabled && !audioQuality.permits(config.kind),
                    onEdit = if (config.kind.needsServer) {
                        { editingSource = config }
                    } else {
                        null
                    },
                    onToggle = if (config.kind == DesktopSourceKind.YOUTUBE) {
                        null
                    } else {
                        { onSourceEnabledChange(config, it) }
                    },
                    onMoveUp = if (userAddedIds.indexOf(config.id) > 0) {
                        { onMoveSource(config, -1) }
                    } else null,
                    onMoveDown = if (userAddedIds.indexOf(config.id) in 0 until userAddedIds.lastIndex) {
                        { onMoveSource(config, 1) }
                    } else null,
                )
            }
        }
        // One entry point, and it creates an addon.
        iconRow(
            BitChordIcons.Plus,
            "Add a source",
            DesktopSourceKind.ADDON.detail,
        ) {
            editingSource = DesktopSourceConfig(
                id = UUID.randomUUID().toString(),
                kind = DesktopSourceKind.ADDON,
            )
        }
    }

    sections.section(DOWNLOADS) {
        choice(
            DOWNLOAD_QUALITY.title,
            downloadOptions[downloadQuality] ?: downloadQuality,
            "offline Standard High Lossless",
            icon = BitChordIcons.Download,
        ) {
            onOpenPage(DOWNLOAD_QUALITY)
        }
    }

    sections.section(PLAYBACK) {
        val output by DesktopAudioDevices.selected.collectAsState()
        val eqOn by DesktopEqualizerSettings.enabled.collectAsState()
        val videoBeta by DesktopVideoBeta.enabled.collectAsState()
        row(OUTPUT_PRECISION.title, "Playing at $outputSummary", "PCM float bit-perfect lossless $outputSummary", icon = true) {
            DesktopSettingsControl(OUTPUT_PRECISION.title, "Playing at $outputSummary", icon = DesktopIcons.Audio.GraphicEq) {
                DesktopSettingsSegmentedControl(
                    outputOptions.keys.toList(),
                    outputPrecision,
                    { outputOptions.getValue(it) },
                    onOutputPrecisionChange,
                )
            }
        }
        choice(
            AUDIO_OUTPUT.title,
            DesktopAudioDevices.label(output),
            "output device system default speakers headphones sound card advanced",
            icon = DesktopIcons.Audio.Speaker,
        ) {
            onOpenPage(AUDIO_OUTPUT)
        }
        toggle(
            DesktopStrings["automix", "Automix [BETA]"],
            if (automix) {
                "Calculates timing and blends transitions from the tracks"
            } else {
                "Automatically calculates transition timing without a slider"
            },
            automix,
            onAutomixChange,
            icon = DesktopIcons.Status.Sparkles,
        )
        if (automix) {
            val title = DesktopStrings["automix_duration", "Automix duration"]
            val description = "How long the two songs play together"
            row(title, description, "Adaptive 8 Bars 16 Bars longer overlaps") {
                DesktopSettingsControl(title, description) {
                    DesktopSettingsSegmentedControl(DesktopAutomixDuration.entries, automixDuration, { it.optionLabel }, onAutomixDurationChange)
                    Text(automixDuration.description, color = DesktopSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            val title = DesktopStrings["crossfade", "Crossfade"]
            row(title, "Fade transitions between songs") {
                DesktopSettingsControl(title, if (crossfadeSeconds == 0) "Off" else "${crossfadeSeconds}s") {
                    DesktopBareSlider(crossfadeSeconds.toFloat(), { onCrossfadeSecondsChange(it.toInt()) }, valueRange = 0f..12f, steps = 11)
                }
            }
        }
        toggle(
            "AutoMix artwork transition",
            "Dissolve artwork and blend the background during AutoMix",
            automixArtwork,
            onAutomixArtworkChange,
        )
        toggle(
            "AutoMix mini-player artwork transition",
            "Dissolve artwork in compact and floating players",
            automixMiniArtwork,
            onAutomixMiniArtworkChange,
        )
        row("AutoMix models", "Download or delete analysis models", icon = true) {
            Column(Modifier.fillMaxWidth()) {
                DesktopSettingsRow("AutoMix models", "Download or delete analysis models", icon = DesktopIcons.Actions.CheckCircle)
                Column(Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp)) {
                    DesktopAutomixModelControls(onDelete = { onAutomixChange(false) })
                }
            }
        }
        choice(
            AUTOMIX_PERFORMANCE.title,
            automixPerformance.label(),
            automixSearch,
            icon = DesktopIcons.Settings.Sliders,
            subtitle = DesktopStrings["automix_performance_subtitle", "Sets how much CPU background analysis may use"],
        ) { onOpenPage(AUTOMIX_PERFORMANCE) }
        toggle(
            DesktopStrings["skip_silence", "Skip silence"],
            DesktopStrings["d_shorten_long_gaps_rather_than_playing_them_out", "Shorten long gaps rather than playing them out"],
            skipSilence,
            onSkipSilenceChange,
            icon = DesktopIcons.Audio.VolumeOff,
        )
        iconRow(
            DesktopIcons.Player.SleepTimer,
            "Sleep timer",
            when {
                sleepRemainingMs != null -> "${formatSleepTimer(sleepRemainingMs)} until playback pauses"
                sleepAfterTrack -> "Pausing when this song ends"
                else -> "Pause playback after a while"
            },
        ) { onSleepTimerCycle() }
        toggle(
            DesktopStrings["spatial_audio", "Spatial audio"],
            DesktopStrings["spatial_audio_subtitle", "Widens stereo tracks for a more immersive feel"],
            spatialAudio,
            onSpatialAudioChange,
            icon = DesktopIcons.Audio.Speaker,
        )
        val equalizerDetail = DesktopStrings["equalizer_subtitle", "Tone, seven bands and balance"]
        row(EQUALIZER.title, equalizerDetail, "bass treble frequency presets manual dynamic width reset gain EQ", icon = true) {
            DesktopSettingsRow(
                EQUALIZER.title,
                equalizerDetail,
                icon = DesktopIcons.Settings.Sliders,
                value = if (eqOn) "On" else "Off",
                onClick = { onOpenPage(EQUALIZER) },
            )
        }
        toggle(
            "Music video playback (Beta)",
            "Experimental video player and audio/video switching. Off uses the classic audio player; video thumbnails stay widescreen.",
            videoBeta,
            DesktopVideoBeta::setEnabled,
            icon = DesktopIcons.Player.MusicVideo,
        )
        toggle(
            DesktopStrings["autoplay", "Autoplay"],
            DesktopStrings["d_keep_the_music_going_with_similar_songs", "Keep the music going with similar songs"],
            autoplay,
            onAutoplayChange,
        )
        toggle(
            DesktopStrings["shuffle", "Shuffle"],
            DesktopStrings["d_mix_the_order_of_the_current_queue", "Mix the order of the current queue"],
            shuffle,
            onShuffleChange,
            icon = BitChordIcons.Shuffle,
        )
        iconRow(
            BitChordIcons.Repeat,
            DesktopStrings["d_repeat", "Repeat"],
            "${repeatMode.label()} · Tap to change",
        ) { onRepeatModeChange(repeatMode.next()) }
        iconRow(
            DesktopIcons.Settings.Tune,
            DesktopStrings["d_playback_speed", "Playback speed"],
            "${"%.2f".format(playbackSpeed)}×",
        ) {
            val next = when {
                playbackSpeed < 0.76f -> 1.0f
                playbackSpeed < 1.01f -> 1.25f
                playbackSpeed < 1.26f -> 1.5f
                playbackSpeed < 1.51f -> 2.0f
                else -> 0.5f
            }
            onPlaybackSpeedChange(next)
        }
    }

    sections.section(CONTENT_PREFERENCES) {
        contentPreferenceRows()
    }

    sections.section(APPEARANCE) {
        val themeTitle = "Theme"
        val themeSubtitle = "Follow your device appearance by default, or choose a theme."
        row(themeTitle, themeSubtitle, "Light Dark System", icon = true) {
            DesktopSettingsControl(themeTitle, themeSubtitle, icon = DesktopIcons.Settings.Brightness) {
                DesktopSettingsSegmentedControl(DesktopThemeMode.entries, DesktopTheme.mode, { it.label }, DesktopTheme::selectMode)
            }
        }
        // Show only after the user explicitly chooses Dark, not System or Light.
        if (DesktopTheme.supportsPitchBlack(DesktopTheme.mode)) {
            toggle(
                "Pitch black background",
                "Make both the content area and sidebar true black.",
                DesktopTheme.pitchBlack,
                DesktopTheme::updatePitchBlack,
            )
        }
        choice(
            FONT.title,
            fontLabel(DesktopFontSettings.mode),
            fontSearch,
            icon = DesktopIcons.Settings.Font,
            subtitle = fontDetail(DesktopFontSettings.mode),
        ) { onOpenPage(FONT) }
        toggle(
            DesktopStrings["full_screen_cover_art", "Full-screen cover art"],
            DesktopStrings["full_screen_cover_art_subtitle", "Runs the cover to the edges of the player instead of a square sleeve"],
            fullBleedArtwork,
            onFullBleedArtworkChange,
            icon = DesktopIcons.Window.Fullscreen,
        )
        toggle(
            DesktopStrings["show_best_part", "Show Best Part"],
            DesktopStrings["show_best_part_subtitle", "Show a shortcut to the most replayed part or chorus on the player artwork"],
            showBestPart,
            onShowBestPartChange,
            icon = BitChordIcons.MusicNote,
        )
        // Like the mobile segmented setting; preserve all three backdrop implementations.
        val playerTheme by DesktopPlayerThemeSettings.theme.collectAsState()
        row("Player theme", "Default Legacy Simple background mesh gradient blur", icon = true) {
            val description = when (playerTheme) {
                PlayerTheme.DEFAULT -> "The cover's own colours carry on below it."
                PlayerTheme.LEGACY -> "Animated colours from the cover drift behind the player."
                PlayerTheme.SIMPLE -> "A soft blur of the cover fills the player."
            }
            DesktopSettingsControl("Player theme", description, icon = DesktopIcons.Settings.Brightness) {
                DesktopSettingsSegmentedControl(
                    PlayerTheme.entries, playerTheme, { it.name.lowercase().replaceFirstChar(Char::uppercaseChar) },
                    DesktopPlayerThemeSettings::setTheme,
                )
            }
        }
        toggle(
            DesktopStrings["d_animated_canvas", "Animated canvas"],
            DesktopStrings["d_show_motion_artwork_when_it_is_available", "Show motion artwork when it is available"],
            animatedCanvas,
            onAnimatedCanvasChange,
            icon = DesktopIcons.Settings.Animation,
        )
        if (animatedCanvas || searching) {
            navigationRow(
                title = DesktopStrings["spotify_canvas_setup", "Spotify Canvas setup"],
                subtitle = if (spotifyCanvasReady) {
                    "Connected"
                } else {
                    "To use Spotify Canvas, provide your Spotify sp_dc cookie."
                },
                onClick = { onOpenPage(SPOTIFY_CANVAS) },
            )
        }
        toggle(
            DesktopStrings["synced_lyrics", "Synced lyrics"],
            DesktopStrings["synced_lyrics_subtitle", "Lights up the words on the player as they're sung"],
            syncedLyrics,
            onSyncedLyricsChange,
            icon = BitChordIcons.Lyrics,
        )
        // Nothing to choose between while the feature is off, and the sources are
        // third-party services reached on the listener's connection.
        if (syncedLyrics || searching) {
            val animateLyricsWhileUnfocused by DesktopAppearanceSettings.animateLyricsWhileUnfocused.collectAsState()
            if (syncedLyrics) toggle(
                "Continue lyrics animation when unfocused",
                "Keep synced lyrics moving on a visible second monitor. May use more CPU/GPU; stops when minimized or hidden.",
                animateLyricsWhileUnfocused,
                DesktopAppearanceSettings::setAnimateLyricsWhileUnfocused,
            )
            if (syncedLyrics) toggle(
                DesktopStrings["d_blur_unfocused_lyrics", "Blur unfocused lyrics"],
                DesktopStrings["d_keeps_the_spotlight_on_the_current_line", "Keeps the spotlight on the current line"],
                lyricsBlur,
                onLyricsBlurChange,
                icon = DesktopIcons.Settings.BlurOn,
            )
            val sourceSummary = enabledLyricsSources.joinToString(", ").ifEmpty { "None. Lyrics will not be fetched." }
            val providers = DesktopLyricsClient.sources.joinToString { "${it.name} ${it.detail}" }
            row(LYRICS_SOURCES.title, sourceSummary, providers, "provider priority word-by-word syllable lyrics", icon = true) {
                DesktopSettingsRow(
                    LYRICS_SOURCES.title,
                    sourceSummary,
                    icon = DesktopIcons.Settings.Language,
                    onClick = { onOpenPage(LYRICS_SOURCES) },
                )
            }
            val translationLanguage by DesktopTranslationSetting.language.collectAsState()
            row(TRANSLATION_LANGUAGE.title, DesktopTranslationSetting.describe(translationLanguage), "translate lyrics follow app language", icon = true) {
                DesktopSettingsRow(
                    TRANSLATION_LANGUAGE.title,
                    icon = DesktopIcons.Settings.Translate,
                    value = DesktopTranslationSetting.describe(translationLanguage),
                    onClick = { onOpenPage(TRANSLATION_LANGUAGE) },
                )
            }
        }
        val uiScalePercent by DesktopUiScaleSettings.percent.collectAsState()
        val uiScaleTitle = DesktopStrings["d_ui_scale", "UI scale"]
        val uiScaleSubtitle = DesktopStrings[
            "d_ui_scale_subtitle",
            "Zoom text, controls, and panels throughout BitChord.",
        ]
        row(uiScaleTitle, uiScaleSubtitle, "zoom size dpi density text") {
            var uiScaleMenuOpen by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(uiScaleTitle, fontWeight = FontWeight.Medium)
                    Text(uiScaleSubtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(12.dp))
                Box {
                    OutlinedButton(onClick = { uiScaleMenuOpen = true }) {
                        Text("$uiScalePercent%")
                        Spacer(Modifier.width(4.dp))
                        Icon(DesktopIcons.Window.ExpandMore, contentDescription = null)
                    }
                    DesktopDropdownMenu(
                        expanded = uiScaleMenuOpen,
                        onDismissRequest = { uiScaleMenuOpen = false },
                    ) {
                        DesktopUiScaleSettings.options.forEach { percent ->
                            DesktopDropdownMenuItem(
                                text = { Text("$percent%") },
                                trailingIcon = if (percent == uiScalePercent) {
                                    { Icon(BitChordIcons.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                                onClick = {
                                    uiScaleMenuOpen = false
                                    DesktopUiScaleSettings.setPercent(percent)
                                },
                            )
                        }
                    }
                }
            }
        }
        val oldTopPlayerEnabled by DesktopAppearanceSettings.enableOldTopMusicPlayerStyle.collectAsState()
        val useOldExploreCards by DesktopAppearanceSettings.useOldExploreCards.collectAsState()
        toggle(
            "Use old Explore cards",
            "Bring back the v1.7 gradient cards with angled cover art in Moods & moments and Genres",
            useOldExploreCards,
            DesktopAppearanceSettings::setUseOldExploreCards,
        )
        toggle(
            "Enable old top music player style",
            "Use the original desktop top player instead of the new floating bottom player",
            oldTopPlayerEnabled,
            DesktopAppearanceSettings::setEnableOldTopMusicPlayerStyle,
        )
    }

    sections.section(PERFORMANCE) {
        val reduceBlur by DesktopAppearanceSettings.reduceDynamicBlur.collectAsState()
        val reduceAnimation by DesktopAppearanceSettings.reduceAnimation.collectAsState()
        toggle(
            DesktopStrings["reduce_animation", "Reduce animation"],
            "Limit interface motion and transitions.",
            reduceAnimation,
            DesktopAppearanceSettings::setReduceAnimation,
            icon = DesktopIcons.Settings.Animation,
        )
        toggle(
            "Reduce Blur",
            "Turn off blur effects across the interface for a lighter, more responsive experience.",
            reduceBlur,
            DesktopAppearanceSettings::setReduceDynamicBlur,
            icon = DesktopIcons.Settings.BlurOn,
        )
    }

    sections.section(WINDOW_SYSTEM) {
        if (DesktopSidebarMica.isSupported) {
            val micaSidebar by DesktopAppearanceSettings.micaSidebar.collectAsState()
            toggle(
                "Use Mica-style sidebar",
                "Wallpaper-tinted Windows 11 sidebar without live blur; works independently of Reduce dynamic blur",
                micaSidebar,
                DesktopAppearanceSettings::setMicaSidebar,
            )
        }
        if (DesktopPlatform.isWindows) {
            val backdropChoice by DesktopWindowBackdrop.selected.collectAsState()
            val backdropActive by DesktopWindowBackdrop.captionActive.collectAsState()
            val materialTitle = "Native title bar material"
            val materialSubtitle = "Changes the Windows title bar only; app content stays opaque"
            row(materialTitle, materialSubtitle, "Mica Acrylic Windows DWM") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(materialTitle, fontWeight = FontWeight.Medium)
                    Text(materialSubtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    DesktopSettingsSegmentedControl(DesktopBackdrop.entries, backdropChoice, { it.label }, DesktopWindowBackdrop::set)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            backdropChoice != DesktopBackdrop.OFF && backdropActive == DesktopBackdrop.OFF ->
                                "Unavailable on this Windows version or DWM declined it; the system title bar remains active"
                            backdropChoice == DesktopBackdrop.MICA ->
                                "Mica requested for the native title bar"
                            backdropChoice == DesktopBackdrop.ACRYLIC ->
                                "Acrylic requested for the native title bar"
                            else -> "System title bar without backdrop material"
                        },
                        color = DesktopSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        toggle(
            DesktopStrings["d_tray_icon", "Tray icon"],
            DesktopStrings[
                "d_show_bitchord_in_the_system_tray_with_playback_controls",
                "Show BitChord in the system tray, with playback controls",
            ],
            trayIconEnabled,
            onTrayIconChange,
        )
        if (trayIconEnabled) {
            toggle(
                DesktopStrings["d_keep_playing_when_closed", "Keep playing when closed"],
                DesktopStrings[
                    "d_closing_the_window_leaves_bitchord_in_the_tray_instead_o",
                    "Closing the window leaves BitChord in the tray instead of quitting",
                ],
                closeToTray,
                onCloseToTrayChange,
            )
        }
        if (trayIconEnabled && closeToTray) {
            val pip by DesktopPipSettings.state.collectAsState()
            toggle("Switch to PiP Mode When Closing App",
                "Closing the main window opens the floating player while music continues",
                pip.onClose, { value -> DesktopPipSettings.update { it.copy(onClose = value) } })
        }
    }

    sections.section(LOCAL_MUSIC) {
        var folderLabel by remember { mutableStateOf(DesktopLocalMusic.folderLabel()) }
        iconRow(
            DesktopIcons.Settings.Folder,
            "Local music folder",
            folderLabel,
        ) {
            onChooseLocalMusicFolder { folderLabel = DesktopLocalMusic.folderLabel() }
        }
        if (DesktopLocalMusic.folder() != null) {
            iconRow(
                DesktopIcons.Status.LibraryMusic,
                DesktopStrings["use_all_audio_folders", "Use all audio folders"],
                DesktopStrings["use_all_audio_folders_subtitle", "Remove the folder limit and scan music across the device"],
            ) {
                DesktopLocalMusic.setFolder(null)
                folderLabel = DesktopLocalMusic.folderLabel()
                onLocalMusicFolderChanged()
            }
        }
        toggle(
            DesktopStrings["filter_non_music_audio", "Filter non-music audio"],
            DesktopStrings[
                "filter_non_music_audio_subtitle",
                "Hides clips under 30 seconds, WAV files, voice notes, recordings and system sounds",
            ],
            filterNonMusicAudio,
            onFilterNonMusicAudioChange,
            icon = BitChordIcons.MusicNote,
        )
    }

    sections.section(STORAGE) {
        var limitMb by remember { mutableStateOf(DesktopMediaCache.limitMb()) }
        var cleared by remember { mutableStateOf<String?>(null) }
        val cacheScope = rememberCoroutineScope()
        row(DesktopStrings["song_cache_limit", "Song cache limit"], "Keep downloaded audio on disk, media cache space", icon = true) {
            DesktopSettingsControl(
                title = DesktopStrings["song_cache_limit", "Song cache limit"],
                subtitle = if (limitMb > DesktopMediaCache.WARNING_MB) {
                    "Keeps up to ${formatCacheSize(limitMb)} of downloaded audio on disk. " +
                        "This can take a noticeable share of your free space."
                } else {
                    "Keeps media on disk. Playback audio ranges use a separate cache capped at 256 MB."
                },
                icon = DesktopIcons.Settings.Storage,
            ) {
                DesktopBareSlider(
                    value = limitMb.toFloat(),
                    onValueChange = { limitMb = it.roundToInt() },
                    onValueChangeFinished = { DesktopMediaCache.setLimitMb(limitMb) },
                    valueRange = DesktopMediaCache.MIN_LIMIT_MB.toFloat()..
                        DesktopMediaCache.MAX_LIMIT_MB.toFloat(),
                    steps = 18,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(formatCacheSize(limitMb), color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        iconRow(
            DesktopIcons.Actions.Delete,
            "Clear song cache",
            cleared ?: "Frees space used by downloaded audio",
        ) {
            cacheScope.launch(Dispatchers.IO) {
                DesktopMediaCache.clear()
                DesktopAudioCache.shared.clear()
                withContext(Dispatchers.Main) { cleared = "Song cache cleared" }
            }
        }
        var imagesCleared by remember { mutableStateOf(false) }
        iconRow(
            DesktopIcons.Actions.Delete,
            "Clear image cache",
            if (imagesCleared) "Image cache cleared" else "Frees space used by album artwork",
        ) {
            DesktopArtworkCache.clear()
            imagesCleared = true
        }
    }

    sections.section(MISCELLANEOUS) {
        toggle(
            DesktopStrings["d_dont_repeat_songs_in_current_session", "Don\u2019t repeat songs in current session"],
            DesktopStrings["d_autoplay_wont_suggest_a_song_already_played_or_suggested", "AutoPlay won\u2019t suggest a song already played or suggested this session"],
            dontRepeatSuggestions,
            onDontRepeatSuggestionsChange,
            icon = DesktopIcons.Settings.History,
        )
        val hideVolumeBar by DesktopAppearanceSettings.hideVolumeBar.collectAsState()
        toggle(
            DesktopStrings["hide_volume_bar", "Hide volume bar"],
            DesktopStrings["hide_volume_bar_subtitle", "Removes the volume slider from the main player"],
            hideVolumeBar,
            DesktopAppearanceSettings::setHideVolumeBar,
            icon = DesktopIcons.Audio.VolumeOff,
        )
        toggle(
            DesktopStrings["show_nerd_stats", "Show stats for nerds"],
            DesktopStrings["show_nerd_stats_subtitle", "Codec, bitrate and sample rate on the player"],
            showNerdStats,
            onShowNerdStatsChange,
            icon = DesktopIcons.Settings.BarChart,
        )
        val iconTitle = DesktopStrings["d_app_icon", "App icon"]
        val iconSubtitle = DesktopStrings[
            "d_app_icon_subtitle",
            "Change the window and tray icon instantly. Your choice is saved independently of the theme.",
        ]
        row(iconTitle, iconSubtitle, "launcher window tray", icon = true) {
            DesktopSettingsControl(iconTitle, iconSubtitle, icon = DesktopIcons.Navigation.GridView) {
                DesktopAppIconPicker()
                Text(
                    DesktopStrings[
                        "d_app_icon_launcher_note",
                        "Installed shortcuts and pinned launcher icons may keep the default Dark icon.",
                    ],
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    sections.section(LANGUAGE) {
        choice(
            DesktopStrings["app_language", "App language"],
            DesktopStrings.languageLabel(),
            languageSearch,
            icon = DesktopIcons.Settings.Language,
        ) {
            onOpenPage(APP_LANGUAGE)
        }
    }

    sections.section(APP_UPDATES) {
        updater?.let { service ->
            val state by service.state.collectAsState()
            val auto by service.autoCheck.collectAsState()
            val checked by service.lastCheck.collectAsState()
            updateSettingsRows(service, state, auto, checked)
        }
    }

    val overview = page == MAIN && !searching
    DesktopSettingsPageFrame {
        DesktopSettingsPageTitle(page.title)
        if (page == MAIN) {
            DesktopSettingsSearch(query, onQueryChange)
        }
        DesktopSettingsList(
            state = listState,
            modifier = Modifier.weight(1f),
            topPadding = if (page == MAIN) 10.dp else 0.dp,
            bottomScrollInset = bottomScrollInset,
        ) {
            if (overview) {
                item("categories") {
                    DesktopSettingsOverview(
                        audioQuality = audioQuality.label,
                        directSource = directSource,
                        onDirectSourceChange = DesktopDirectSource::setEnabled,
                        dolbyAtmos = dolbyAtmos,
                        dolbyAtmosEnabled = dolbyAtmosEnabled,
                        dolbyAtmosSubtitle = dolbyAtmosSubtitle,
                        onDolbyAtmosChange = onDolbyAtmosChange,
                        onOpenPage = onOpenPage,
                    )
                }
            } else {
                if (page == APP_UPDATES && !searching) {
                    item("about_header") { DesktopAboutHeaderCard { onOpenPage(LICENSES) } }
                }
                sections.groups.forEach { (route, rows) ->
                    item(route.name) { rows.Content(route.title.takeIf { searching }) }
                }
                if (searching && sections.groups.isEmpty()) {
                    item("empty") {
                        Text(
                            DesktopStrings["settings_no_results", "No settings found"],
                            color = DesktopSecondary,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }
        }
    }

    editingSource?.let { config ->
        DesktopSourceEditorDialog(
            config = config,
            configuredSources = sourceConfigs,
            isNew = sourceConfigs.none { it.id == config.id },
            status = sourceStatus[config.id],
            onDismiss = { editingSource = null },
            onSave = {
                onSaveSource(it)
                editingSource = null
            },
            onRemove = {
                onRemoveSource(config)
                editingSource = null
            },
            onTest = onTestSource,
        )
    }
}

/**
 * Keep the viewport full height so rows can scroll behind the floating player.
 * Put the shell's clearance inside the list instead, so its final row or About
 * footer can scroll completely above the player without a permanent bottom gap.
 */
@Composable
internal fun DesktopSettingsList(
    state: LazyListState,
    modifier: Modifier,
    topPadding: Dp,
    bottomScrollInset: Dp,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth().desktopTopScrollFade(state),
        contentPadding = PaddingValues(top = topPadding, bottom = 28.dp + bottomScrollInset),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        content = content,
    )
}

/** The About BitChord header card shown at the top of the About BitChord settings page. */
@Composable
private fun DesktopAboutHeaderCard(onLicenses: () -> Unit) {
    val version = remember { System.getProperty("bitchord.version") ?: "1.10" }
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = DesktopForeground, textDecoration = TextDecoration.Underline),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 112.dp)
            .settingsCard(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(136.dp)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        listOf(DesktopForeground.copy(alpha = 0.06f), Color.Transparent),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(if (DesktopTheme.isLight) Res.drawable.logo else Res.drawable.bitchord_logo_original),
                contentDescription = "BitChord",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 56.dp, height = 38.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 24.dp, top = 22.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            DesktopAboutVersionTitle(
                version = version,
                color = DesktopForeground,
                textAlign = TextAlign.Start,
                fontSize = 28.sp,
            )
            Text(
                text = buildAnnotatedString {
                    withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx/BitChord", linkStyles)) {
                        append("GitHub")
                    }
                    append(" ")
                    withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx", linkStyles)) {
                        append("Developer")
                    }
                    append(" ")
                    withLink(LinkAnnotation.Url("https://discord.gg/pDdKfrdHY6", linkStyles)) {
                        append("Discord")
                    }
                    append(" ")
                    withLink(LinkAnnotation.Clickable("licenses", linkStyles) { onLicenses() }) {
                        append("Licenses")
                    }
                    append("  ~YouTube Music Backend")
                },
                style = MaterialTheme.typography.labelSmall,
                color = DesktopSecondary,
                textAlign = TextAlign.Start,
            )
        }
    }
}

/** Everything this build is assembled from, and the terms each part came under. A page of Settings. */
@Composable
internal fun DesktopLicensesPage() {
    val licensesListState = rememberLazyListState()
    DesktopSettingsPageFrame {
        DesktopSettingsPageTitle(LICENSES.title)
        Text(
            DesktopStrings["d_bitchord_is_free_software_and_so_is_everything_it_is_bui", "BitChord is free software, and so is everything it is built on."],
            color = DesktopSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(
            state = licensesListState,
            modifier = Modifier.weight(1f).desktopTopScrollFade(licensesListState),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DesktopLicenses.groups.forEach { group ->
                item(key = group.title) {
                    DesktopSettingsGroup(group.title) {
                        group.entries.forEachIndexed { index, entry ->
                            if (index > 0) DesktopSettingsDivider()
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.name, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        entry.license,
                                        color = DesktopAccent,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                if (entry.note.isNotBlank()) {
                                    Text(
                                        entry.note,
                                        color = DesktopSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(
                                    entry.url,
                                    color = DesktopSecondary.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopSourceSettingsRow(
    position: Int,
    config: DesktopSourceConfig,
    status: String?,
    skippedByQuality: Boolean = false,
    onEdit: (() -> Unit)?,
    onToggle: ((Boolean) -> Unit)?,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val enabledAlpha = if (config.enabled) 1f else 0.45f
    Row(
        Modifier
            .fillMaxWidth()
            .desktopRowClickable(enabled = onEdit != null) { onEdit?.invoke() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$position",
            color = DesktopSecondary,
            modifier = Modifier.width(20.dp).alpha(enabledAlpha),
        )
        Icon(
            when (config.kind) {
                DesktopSourceKind.ADDON -> DesktopIcons.Settings.Extension
                DesktopSourceKind.CUSTOM_MODULE, DesktopSourceKind.MODULE -> DesktopIcons.Settings.Extension
                DesktopSourceKind.JIOSAAVN -> DesktopIcons.Audio.GraphicEq
                DesktopSourceKind.YOUTUBE -> DesktopIcons.Player.PlayCircle
            },
            contentDescription = null,
            tint = DesktopForeground,
            modifier = Modifier.size(22.dp).alpha(enabledAlpha),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(enabledAlpha)) {
            Text(config.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    // The ceiling in force, not the switch, is why this one is being passed over —
                    // worth saying, or the row reads as enabled and silently unused.
                    skippedByQuality -> "Skipped at this audio quality"
                    status != null -> status
                    !config.isComplete -> "Setup required"
                    config.kind.needsServer -> "Checking…"
                    else -> config.kind.detail
                } + if (!config.allowDownloads) {
                    " · " + DesktopStrings["source_downloads_off", "Streaming only"]
                } else {
                    ""
                },
                color = if (skippedByQuality) DesktopAccent else DesktopSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (onMoveUp != null) {
            IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                Icon(DesktopIcons.Window.ExpandLess, "Move source earlier")
            }
        }
        if (onMoveDown != null) {
            IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                Icon(DesktopIcons.Window.ExpandMore, "Move source later")
            }
        }
        if (onToggle == null) {
            Text(DesktopStrings["always_on", "Always on"], color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        } else {
            DesktopSettingsSwitch(checked = config.enabled, onCheckedChange = onToggle, label = config.displayName)
        }
    }
}


private fun AutomixPerformanceMode.label(): String = when (this) {
    AutomixPerformanceMode.EFFICIENT -> "Efficient"
    AutomixPerformanceMode.BALANCED -> "Balanced"
    AutomixPerformanceMode.PERFORMANCE -> "Performance"
}

private fun AutomixPerformanceMode.detail(): String = when (this) {
    AutomixPerformanceMode.EFFICIENT ->
        "1 thread · lowest heat and battery use · analysis may take longer"
    AutomixPerformanceMode.BALANCED ->
        "2 threads · recommended balance of speed, heat and battery"
    AutomixPerformanceMode.PERFORMANCE ->
        "4 threads · fastest analysis · higher heat and battery use"
}

private fun fontLabel(mode: DesktopFontMode): String =
    DesktopStrings[if (mode == DesktopFontMode.DEFAULT) "font_default" else "font_legacy", mode.label]

private fun fontDetail(mode: DesktopFontMode): String = if (mode == DesktopFontMode.DEFAULT) {
    DesktopStrings["font_default_subtitle", "SF Pro Display · Apple Music weights"]
} else {
    DesktopStrings["font_legacy_subtitle", "SF Pro Display · Original heavy weights"]
}

@Composable
private fun DesktopSourceEditorDialog(
    config: DesktopSourceConfig,
    configuredSources: List<DesktopSourceConfig>,
    isNew: Boolean,
    status: String?,
    onDismiss: () -> Unit,
    onSave: (DesktopSourceConfig) -> Unit,
    onRemove: () -> Unit,
    onTest: (DesktopSourceConfig) -> Unit,
) {
    var label by remember(config.id) { mutableStateOf(config.label) }
    var baseUrl by remember(config.id) { mutableStateOf(config.baseUrl) }
    var checking by remember(config.id) { mutableStateOf(false) }
    var message by remember(config.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    val candidate = config.copy(label = label.trim(), baseUrl = baseUrl.trim())

    /** Works out what is actually on the end of the URL before storing it. */
    fun identify(thenSave: Boolean) {
        if (candidate.baseUrl.isBlank() || checking) return
        checking = true
        message = null
        scope.launch {
            val outcome = DesktopSourceFormats.identify(candidate.baseUrl)
            checking = false
            outcome.fold(
                onSuccess = { detected ->
                    when (detected) {
                        is DesktopDetectedFormat.Addon -> {
                            val named = candidate.copy(
                                kind = DesktopSourceKind.ADDON,
                                baseUrl = detected.baseUrl,
                                label = candidate.label.ifBlank { detected.manifest.displayName },
                                allowDownloads = detected.manifest.downloadsAllowed,
                            )
                            val duplicate = configuredSources.duplicateOf(named.baseUrl, exceptId = named.id)
                            if (duplicate != null) {
                                message = "Already added as ${duplicate.displayName}"
                            } else {
                                message = "Addon · ${detected.manifest.displayName}"
                                if (thenSave) onSave(named)
                            }
                        }
                        is DesktopDetectedFormat.ModuleIndex -> {
                            val asIndex = candidate.copy(
                                kind = DesktopSourceKind.CUSTOM_MODULE,
                                baseUrl = detected.url.ifBlank { candidate.baseUrl },
                            )
                            message = "Module index · ${detected.moduleCount} modules"
                            if (thenSave) onSave(asIndex)
                        }
                        is DesktopDetectedFormat.Unsupported -> message = detected.reason
                    }
                },
                onFailure = { message = it.message ?: "Nothing answered at that address" },
            )
        }
    }

    val canAct = candidate.baseUrl.isNotBlank() && !checking

    DesktopDialogFrame(onDismiss = onDismiss) {
        DesktopDialogHeading(if (isNew) "Add a source" else config.displayName)
        Spacer(Modifier.height(16.dp))
        DesktopDialogField(
            value = baseUrl,
            onValueChange = {
                baseUrl = it
                message = null
            },
            placeholder = DesktopStrings["d_addon_url", "Addon URL"],
            imeAction = ImeAction.Next,
            modifier = Modifier.focusRequester(focusRequester),
        )
        Text(
            DesktopStrings[
                "d_an_addon_server",
                "An addon server — BitChord asks it over plain HTTP and runs no code from it. " +
                    "A compatible module index also works and is stored as one.",
            ],
            color = DesktopSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        )
        DesktopDialogField(
            value = label,
            onValueChange = { label = it },
            placeholder = DesktopStrings["d_name_optional", "Name (optional)"],
            onSubmit = { identify(thenSave = true) },
        )
        (message ?: status)?.let {
            Text(
                it,
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isNew) {
                TextButton(onClick = onRemove) {
                    Text(DesktopStrings["remove", "Remove"], color = DesktopDestructive)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text(DesktopStrings["cancel", "Cancel"], color = DesktopSecondary)
            }
            TextButton(
                onClick = {
                    onTest(candidate)
                    identify(thenSave = false)
                },
                enabled = canAct,
            ) {
                Text(
                    if (checking) "Checking…" else "Test",
                    color = if (canAct) DesktopAccent else DesktopSecondary,
                )
            }
            TextButton(
                onClick = { identify(thenSave = true) },
                enabled = canAct,
            ) {
                Text(
                    DesktopStrings["save", "Save"],
                    color = if (canAct) DesktopAccent else DesktopSecondary,
                )
            }
        }
    }
}
