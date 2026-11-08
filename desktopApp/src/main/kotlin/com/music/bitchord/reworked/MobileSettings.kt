package com.music.bitchord.reworked

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.lyrics.PaxSenix
import com.music.bitchord.data.settings.*
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
internal fun MobileSettingsPage(session: MobileSession, onExit: () -> Unit, onParty: () -> Unit, onScrobbling: () -> Unit, onReplay: () -> Unit) {
    val signedIn by session.signedIn.collectAsState()
    val playback by session.player.state.collectAsState()
    val settings = MobilePlayerSettings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        SettingsSection("Accounts") {
            MobileYouTubeAccountSettings(onAddAccount = session::signIn, onChooseInYouTube = { id ->
                DesktopBrowserAuth.youtubeSession(YouTubeSessionMode.SWITCH_CHANNEL, id, onError = session::notice)
            })
            if (signedIn) TextButton(onClick = session::signOut) { Text("Remove current saved account") }
            OutlinedButton(onClick = { DesktopBrowserAuth.spotify { session.notice("Spotify connected") } }) { Text("Connect Spotify") }
        }
        SettingsSection("Appearance") {
            ReworkedAppearanceSettings()
            DesktopExtraAppearanceSettings(session::notice)
            ReworkedToggle("Keep playing when the main window closes", DesktopWindowOptions.keepPlaying, DesktopWindowOptions::setKeepPlaying)
            val theme by settings.playerTheme.collectAsState()
            Text("Player style", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PlayerTheme.entries.forEach { entry ->
                FilterChip(theme == entry, onClick = { settings.setPlayerTheme(entry) }, label = { Text(entry.name.lowercase().replaceFirstChar(Char::titlecase)) })
            } }
            SettingsFlag("Animated artwork", "animated_canvas", settings.animatedCanvas)
            SettingsFlag("Prefer Spotify Canvas", "spotify_canvas_first", settings.prioritizeSpotifyCanvas)
            SettingsFlag("Auto-hide Spotify Canvas", "canvas_autohide", settings.spotifyCanvasAutoHide)
            SettingsFlag("Full-bleed artwork", "full_bleed", settings.fullBleedArtwork)
            SettingsFlag("Hide volume bar", "hide_volume", settings.hideVolumeBar)
            SettingsFlag("Hide song status", "hide_song_status", settings.hideSongStatus)
            SettingsFlag("Show played queue history", "queue_history", settings.showQueueHistory)
            SettingsFlag("Reduce animations", "reduce_animation", settings.reduceAnimation)
            SettingsFlag("Reduce dynamic blur", "reduce_dynamic_blur", settings.reduceDynamicBlur)
            SettingsFlag("Disable blend effects", "disable_blend", settings.disableBlendEffects)
            SettingsFlag("Square video thumbnails", "square_video_thumbnails", settings.squareVideoThumbnails)
            MobileArtistSettings(session::notice)
        }
        SettingsSection("Home") {
            SettingsFlag("Hide recently played", "hide_recents", settings.hideRecents)
            SettingsFlag("Hide videos in recommendations", "hide_home_videos", settings.hideHomeVideos)
            SettingsFlag("Hide Top Picks", "hide_top_picks", settings.hideTopPicks)
            SettingsFlag("Hide mood tiles", "hide_home_moods", settings.hideHomeMoods)
        }
        SettingsSection("Playback") {
            val musicOnly by MobilePlaybackPolicy.preferMusicOnly.collectAsState()
            ReworkedToggle("Prefer music-only versions", musicOnly, MobilePlaybackPolicy::setPreferMusicOnly)
            SettingsFlag("Align audio and video versions by their sound", "smart_version_alignment", settings.smartVersionAlignment)
            val autoplay by session.autoplay.collectAsState()
            ReworkedToggle("Autoplay recommendations", autoplay) { session.autoplay.value = it; DesktopStore.preferences.putBoolean("autoplay", it) }
            SettingsFlag("Avoid repeating autoplay suggestions in this session", "dont_repeat_suggestions", settings.dontRepeatSuggestions)
            Text("Volume · ${(playback.volume * 100).toInt()}%")
            Slider(playback.volume, session.player::setVolume, valueRange = 0f..1f)
            Text("Playback speed · ${"%.2f".format(playback.speed)}×")
            Slider(playback.speed, session.player::setSpeed, valueRange = 0.5f..2f, steps = 14)
            Text("Crossfade · ${playback.crossfadeSeconds} seconds")
            Slider(playback.crossfadeSeconds.toFloat(), { session.player.setCrossfadeSeconds(it.toInt()); DesktopStore.preferences.putInt("crossfade_seconds", it.toInt()) }, valueRange = 0f..12f, steps = 11)
            ReworkedToggle("AutoMix", playback.automix) { DesktopStore.preferences.putBoolean("automix", it); session.player.setAutomixEnabled(it) }
            ReworkedToggle("Advanced AutoMix", playback.advancedAutomix) { DesktopStore.preferences.putBoolean("advanced_automix", it); session.player.setAdvancedAutomixEnabled(it) }
            ReworkedToggle("Skip silence", playback.skipSilence) { DesktopStore.preferences.putBoolean("skip_silence", it); session.player.setSkipSilence(it) }
            if (playback.skipSilence) Text("Silence skipping pauses crossfade and AutoMix transitions. Queue playback stays continuous.", style = MaterialTheme.typography.bodySmall)
            playback.transition?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val quality by settings.audioQualityWifi.collectAsState()
            Text("Preferred audio quality", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { AudioQuality.entries.forEach { entry ->
                FilterChip(quality == entry, onClick = { settings.setQuality(entry) }, label = { Text(entry.name.lowercase().replaceFirstChar(Char::titlecase)) })
            } }
            Text("The source's available formats determine the delivered quality.", style = MaterialTheme.typography.bodySmall)
            Text("Audio device", style = MaterialTheme.typography.titleSmall)
            var devicesOpen by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { devicesOpen = true }) { Text(session.player.outputDevices().firstOrNull { it.id == playback.outputDeviceId }?.name ?: "System default") }
                DropdownMenu(devicesOpen, onDismissRequest = { devicesOpen = false }) {
                    DropdownMenuItem(text = { Text("System default") }, onClick = { session.player.selectOutput(null); devicesOpen = false })
                    session.player.outputDevices().forEach { device -> DropdownMenuItem(text = { Text(device.name) }, onClick = { session.player.selectOutput(device.id); devicesOpen = false }) }
                }
            }
        }
        MobileAudioSettings(session)
        SettingsSection("Listening together and history") {
            OutlinedButton(onClick = onParty) { Text("Listen Together") }
            OutlinedButton(onClick = onScrobbling) { Text("Last.fm and ListenBrainz") }
            OutlinedButton(onClick = onReplay) { Text("Replay listening statistics") }
        }
        SettingsSection("Lyrics") {
            SettingsFlag("Synced lyrics", "synced_lyrics", settings.syncedLyrics)
            SettingsFlag("Prefer syllable-synced lyrics", "syllable_sync_priority", settings.prioritizeSyllableSync)
            SettingsFlag("Blur inactive lines", "lyrics_blur", settings.lyricsBlur)
            val offset by settings.lyricsOffsetMs.collectAsState()
            Text("Timing offset · ${offset} ms")
            Slider(offset.toFloat(), { settings.setLyricsOffsetMs(it.toInt()) }, valueRange = -5000f..5000f, steps = 99)
            TextButton(onClick = { settings.setLyricsOffsetMs(0) }) { Text("Reset timing") }
            val language by settings.translationLanguage.collectAsState()
            var target by remember(language) { mutableStateOf(language) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(target, { target = it }, label = { Text("Translation language (en, id, ja…)") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (target.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z]{2,8})?"))) settings.setTranslation(target.trim()) else session.notice("Enter a language tag such as en or id") }) { Text("Save") }
            }
            val order by settings.lyricsSourceOrder.collectAsState()
            Text("Provider priority", style = MaterialTheme.typography.titleSmall)
            order.forEachIndexed { index, source ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(source.label, Modifier.weight(1f))
                    IconButton(enabled = index > 0, onClick = { val next = order.toMutableList(); next[index] = next[index-1].also { next[index-1] = source }; settings.setLyricsOrder(next) }) { Icon(Icons.Rounded.KeyboardArrowUp, "Move ${source.label} up") }
                    IconButton(enabled = index < order.lastIndex, onClick = { val next = order.toMutableList(); next[index] = next[index+1].also { next[index+1] = source }; settings.setLyricsOrder(next) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Move ${source.label} down") }
                }
            }
            var apiKey by remember { mutableStateOf(DesktopStore.readText("paxsenix-key.txt").orEmpty()) }
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("PaxSenix API key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            TextButton(onClick = { DesktopStore.writeText("paxsenix-key.txt", apiKey.trim()); PaxSenix.setApiKey(apiKey); session.notice("Lyrics key saved") }) { Text("Save key") }
        }
        MobileDiscordSettings(session)
        SettingsSection("Music sources") { ReworkedSourceSettings() }
        SettingsSection("Remote libraries") { MobileRemoteSettings() }
        SettingsSection("Local music") {
            val scanning by MobileFiles.scanning.collectAsState()
            val songs by MobileFiles.localSongs.collectAsState()
            Text("${songs.size} tracks indexed")
            MobileFileSettings()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = MobileFiles::chooseFolder, enabled = !scanning) { Text("Add music folder") }
                TextButton(onClick = MobileFiles::scanAgain, enabled = !scanning) { Text(if (scanning) "Scanning…" else "Rescan folders") }
            }
        }
        SettingsSection("Picture in picture") { ReworkedPipSettings(canCloseToTray = true) }
        SettingsSection("Updates") { ReworkedUpdateSettings(onExitForUpdate = onExit) }
        SettingsSection("About") {
            Text("BitChord Desktop Reworked", style = MaterialTheme.typography.titleLarge)
            Text("${System.getProperty("bitchord.version", "1.9.1-reworked.1")} · Based on BitChord mobile v1.9.1")
            Text("Built from the original mobile UI, music clients, lyrics and artwork providers, with new Windows and Linux adapters.")
            TextButton(onClick = { openExternal("https://github.com/Bntagaaa/BitChord-Desktop-Revived/tree/bitchord-desktop-reworked") }) { Text("Source code and licenses") }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); content() }
    }
}

@Composable
private fun SettingsFlag(label: String, key: String, flow: MutableStateFlow<Boolean>) {
    val value by flow.collectAsState()
    ReworkedToggle(label, value) { MobilePlayerSettings.flag(key, flow, it) }
}
