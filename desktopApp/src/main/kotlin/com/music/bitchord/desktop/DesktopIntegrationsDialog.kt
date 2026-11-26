package com.music.bitchord.desktop

import com.music.bitchord.ui.screens.desktopTopScrollFade

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.icons.BitChordIcons
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything the account integrations can be told, on one panel.
 *
 * Android splits this across an Account screen and a Discord screen; a desktop
 * Settings sheet has no navigation stack to push onto, so it goes behind one
 * row the way the lyrics sources do. The content, the order and the wording are
 * Android's.
 */
@Composable
internal fun DesktopIntegrationsDialog(
    song: Song?,
    spotifyConnected: Boolean,
    onOpenYouTube: () -> Unit,
    onOpenSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
    onOpenLastfm: () -> Unit,
    onOpenListenBrainz: () -> Unit,
    onOpenDiscordToken: () -> Unit,
    onDismiss: () -> Unit,
) {
    var choosing by remember { mutableStateOf<DiscordChoice?>(null) }
    var editing by remember { mutableStateOf<DiscordField?>(null) }
    val integrationsScrollState = rememberScrollState()

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 460) {
        Column(
            desktopPanelBody(cardMax = 620.dp)
                .desktopTopScrollFade(integrationsScrollState)
                .verticalScroll(integrationsScrollState),
        ) {
            if (LocalDesktopPanelIsPage.current) {
                DesktopSettingsPageTitle(DesktopSettingsPage.INTEGRATIONS.title)
                DesktopSettingsGroup {
                    DesktopSettingsRow("YouTube Music", "Manage accounts and sign in", onClick = onOpenYouTube)
                }
            } else {
                Text(
                    DesktopStrings["account_integrations", "Account & integrations"],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = DesktopForeground,
                    modifier = Modifier.padding(start = panelInset(20.dp), end = panelInset(20.dp), top = 18.dp, bottom = 12.dp),
                )
            }
            SpotifySection(spotifyConnected, onOpenSpotify, onDisconnectSpotify)
            DiscordSection(song, onOpenDiscordToken, onChoose = { choosing = it }, onEdit = { editing = it })
            ScrobblingSection(onOpenLastfm, onOpenListenBrainz)
        }
        DesktopCardRule()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = panelInset(16.dp), vertical = 10.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text(DesktopStrings["done", "Done"], color = DesktopAccent) }
        }
    }

    choosing?.let { choice ->
        DesktopChoiceDialog(
            title = choice.title,
            message = choice.message,
            options = choice.options,
            selected = choice.selected(),
            onSelect = { choice.onSelect(it); choosing = null },
            onDismiss = { choosing = null },
        )
    }
    editing?.let { field ->
        DesktopTextValueDialog(
            title = field.title,
            message = field.message,
            initial = field.initial(),
            placeholder = field.placeholder,
            onSave = { field.onSave(it); editing = null },
            onDismiss = { editing = null },
        )
    }
}

/** The account controls on Android's Account & Integrations page, adapted for desktop. */
@Composable
private fun SpotifySection(
    connected: Boolean,
    onOpenSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
) {
    PanelGroup(
        header = "Spotify",
        footer = "Spotify playlists play through YouTube Music in BitChord. " +
            "Changing accounts here does not change your YouTube Music profile.",
    ) {
        PanelActionRow(
            title = "Spotify",
            subtitle = if (connected) "Connected" else "Not connected",
            destructive = false,
            onClick = onOpenSpotify,
        )
        IntegrationRule()
        PanelActionRow(
            title = if (connected) "Switch Spotify account" else "Connect Spotify",
            subtitle = if (connected) {
                "Choose another Spotify account or update the saved session"
            } else {
                "Sign in to load your playlists and Liked Songs"
            },
            destructive = false,
            onClick = onOpenSpotify,
        )
        if (connected) {
            IntegrationRule()
            PanelActionRow(
                title = "Disconnect",
                subtitle = "Remove the saved Spotify session from this device",
                destructive = true,
                onClick = onDisconnectSpotify,
            )
        }
    }
}

@Composable
private fun DiscordSection(
    song: Song?,
    onOpenDiscordToken: () -> Unit,
    onChoose: (DiscordChoice) -> Unit,
    onEdit: (DiscordField) -> Unit,
) {
    val local by DesktopDiscordRpc.localClient.collectAsState()
    val token by DesktopDiscordRpc.token.collectAsState()
    val username by DesktopDiscordRpc.username.collectAsState()
    val rpcEnabled by DesktopDiscordRpc.enabled.collectAsState()
    val useDetails by DesktopDiscordSettings.useDetails.collectAsState()
    val advanced by DesktopDiscordSettings.advancedMode.collectAsState()
    val status by DesktopDiscordSettings.status.collectAsState()
    val activityType by DesktopDiscordSettings.activityType.collectAsState()
    val activityName by DesktopDiscordSettings.activityName.collectAsState()
    val button1Text by DesktopDiscordSettings.button1Text.collectAsState()
    val button1Visible by DesktopDiscordSettings.button1Visible.collectAsState()
    val button2Text by DesktopDiscordSettings.button2Text.collectAsState()
    val button2Visible by DesktopDiscordSettings.button2Visible.collectAsState()
    LaunchedEffect(Unit) { DesktopDiscordRpc.refreshLocalClient() }

    val connected = local || token.isNotBlank()

    PanelGroup(
        header = "Rich presence",
        footer = "The card updates when the track, position or playback state changes. " +
            "It clears when playback stops.",
    ) {
        PanelSwitchRow(
            title = DesktopStrings["show_what_playing", "Show what I am playing"],
            subtitle = when {
                !connected -> "Connect an account first"
                local -> "Sharing through the Discord app on this computer"
                username.isNotBlank() -> "Sharing as @$username"
                else -> null
            },
            checked = rpcEnabled && connected,
            enabled = connected,
            onCheckedChange = DesktopDiscordRpc::setEnabled,
        )
        IntegrationRule()
        PanelSwitchRow(
            title = DesktopStrings["lead_with_song", "Lead with the song"],
            subtitle = DesktopStrings["lead_with_song_subtitle", "Puts the title on the bold line instead of the artist"],
            checked = useDetails,
            enabled = connected && rpcEnabled,
            onCheckedChange = DesktopDiscordSettings::setUseDetails,
        )
        IntegrationRule()
        PanelSwitchRow(
            title = DesktopStrings["customize_card", "Customize the card"],
            subtitle = DesktopStrings["customize_card_subtitle", "Status, wording and both buttons"],
            checked = advanced,
            enabled = connected && rpcEnabled,
            onCheckedChange = DesktopDiscordSettings::setAdvancedMode,
        )
    }

    if (connected && rpcEnabled && advanced) {
        PanelGroup(header = "Presence") {
            PanelValueRow("Status", DesktopDiscordSettings.statusLabel(status)) {
                onChoose(
                    DiscordChoice(
                        title = DesktopStrings["status", "Status"],
                        message = DesktopStrings["status_dialog_message", "What your account shows while presence is active."],
                        options = DesktopDiscordSettings.statuses,
                        selected = { status },
                        onSelect = DesktopDiscordSettings::setStatus,
                    ),
                )
            }
            IntegrationRule()
            PanelValueRow("Activity", DesktopDiscordSettings.activityLabel(activityType)) {
                onChoose(
                    DiscordChoice(
                        title = DesktopStrings["activity", "Activity"],
                        message = DesktopStrings["activity_dialog_message", "The verb shown above the card."],
                        options = DesktopDiscordSettings.activities,
                        selected = { activityType },
                        onSelect = DesktopDiscordSettings::setActivityType,
                    ),
                )
            }
            IntegrationRule()
            PanelValueRow("Name", activityName.ifEmpty { DesktopDiscordSettings.APP_NAME }) {
                onEdit(
                    DiscordField(
                        title = DesktopStrings["name", "Name"],
                        message = DesktopStrings.format(
                            "d_what_follows_the_verb_on_the_profile",
                            DesktopDiscordSettings.APP_NAME,
                            fallback = "What follows the verb on the profile. " +
                                "Leave it empty to use %1\$s.",
                        ),
                        placeholder = DesktopDiscordSettings.APP_NAME,
                        initial = { activityName },
                        onSave = DesktopDiscordSettings::setActivityName,
                    ),
                )
            }
        }

        PanelGroup(
            header = "Buttons",
            footer = "{song_name}, {artist_name} and {album_name} are replaced with the track. " +
                "The first button opens the song on YouTube Music and the second opens this project.",
        ) {
            PanelSwitchRow(
                title = DesktopStrings["first_button", "First button"],
                subtitle = button1Text.ifEmpty { DesktopDiscordSettings.DEFAULT_BUTTON_1 },
                checked = button1Visible,
                onCheckedChange = DesktopDiscordSettings::setButton1Visible,
                onClick = {
                    onEdit(
                        DiscordField(
                            title = DesktopStrings["first_button", "First button"],
                            message = DesktopStrings["discord_button_variables", "{song_name}, {artist_name} and {album_name} are replaced with the current track."],
                            placeholder = DesktopDiscordSettings.DEFAULT_BUTTON_1,
                            initial = { button1Text },
                            onSave = DesktopDiscordSettings::setButton1Text,
                        ),
                    )
                },
            )
            IntegrationRule()
            PanelSwitchRow(
                title = DesktopStrings["second_button", "Second button"],
                subtitle = button2Text.ifEmpty { DesktopDiscordSettings.DEFAULT_BUTTON_2 },
                checked = button2Visible,
                onCheckedChange = DesktopDiscordSettings::setButton2Visible,
                onClick = {
                    onEdit(
                        DiscordField(
                            title = DesktopStrings["second_button", "Second button"],
                            message = DesktopStrings["discord_button_variables", "{song_name}, {artist_name} and {album_name} are replaced with the current track."],
                            placeholder = DesktopDiscordSettings.DEFAULT_BUTTON_2,
                            initial = { button2Text },
                            onSave = DesktopDiscordSettings::setButton2Text,
                        ),
                    )
                },
            )
        }
    }

    PanelGroup(
        header = "Preview",
        boxed = true,
        footer = if (song == null) "Play something to fill in the preview." else null,
    ) {
        RichPresencePreview(
            song = song,
            heading = activityName.ifEmpty { DesktopDiscordSettings.APP_NAME },
            verb = DesktopDiscordSettings.activityVerb(activityType),
            useDetails = useDetails,
            button1Text = button1Text,
            button1Visible = button1Visible,
            button2Text = button2Text,
            button2Visible = button2Visible,
        )
    }

    // The account route stays the alternative, and only while the local client
    // is not answering; see [DesktopDiscordRpc].
    if (!local) {
        PanelGroup(
            footer = "Signing in with a token grants full account access, and account " +
                "automation is against Discord's terms. Use it only if you accept that risk.",
        ) {
            PanelActionRow(
                title = if (token.isBlank()) "Enter a token instead" else "Disconnect",
                subtitle = if (token.isBlank()) {
                    "Use this if Discord is not running on this computer"
                } else {
                    "Signed in as @$username"
                },
                destructive = token.isNotBlank(),
                onClick = { if (token.isBlank()) onOpenDiscordToken() else DesktopDiscordRpc.signOut() },
            )
        }
    }
}

@Composable
private fun ScrobblingSection(onOpenLastfm: () -> Unit, onOpenListenBrainz: () -> Unit) {
    val lastfmEnabled by DesktopScrobbleSettings.lastfmEnabled.collectAsState()
    val lastfmUser by DesktopScrobbleSettings.lastfmUsername.collectAsState()
    val lastfmSession by DesktopScrobbleSettings.lastfmSessionKey.collectAsState()
    val lbEnabled by DesktopScrobbleSettings.listenBrainzEnabled.collectAsState()
    val lbToken by DesktopScrobbleSettings.listenBrainzToken.collectAsState()
    val signedIn = lastfmSession.isNotBlank()

    PanelGroup(
        header = "Scrobbling",
        footer = "Scrobble your listens to Last.fm and ListenBrainz.",
    ) {
        PanelSwitchRow(
            title = "ListenBrainz",
            subtitle = if (lbEnabled && lbToken.isNotBlank()) "Connected" else "Enter a token to enable",
            checked = lbEnabled,
            onCheckedChange = { checked ->
                if (checked && lbToken.isBlank()) onOpenListenBrainz()
                else DesktopScrobbleSettings.setListenBrainzEnabled(checked)
            },
            onClick = onOpenListenBrainz,
        )
        if (lbEnabled && lbToken.isNotBlank()) {
            IntegrationRule()
            val primaryOnly by DesktopScrobbleSettings.listenBrainzPrimaryArtistOnly.collectAsState()
            PanelSwitchRow(
                title = DesktopStrings["scrobble_primary_artist_only", "Only scrobble primary artist"],
                subtitle = DesktopStrings["scrobble_primary_artist_only_subtitle", "Use the first artist when a track credits multiple artists"],
                checked = primaryOnly,
                onCheckedChange = DesktopScrobbleSettings::setListenBrainzPrimaryArtistOnly,
            )
        }
        IntegrationRule()
        PanelSwitchRow(
            title = "Last.fm",
            subtitle = when {
                !DesktopScrobbling.canSignInToLastFm -> "This build has no Last.fm API key"
                signedIn -> "Signed in as $lastfmUser"
                else -> "Tap to sign in"
            },
            checked = lastfmEnabled,
            enabled = DesktopScrobbling.canSignInToLastFm,
            onCheckedChange = { checked ->
                if (checked && !signedIn) onOpenLastfm() else DesktopScrobbleSettings.setLastfmEnabled(checked)
            },
            onClick = { if (signedIn) DesktopScrobbleSettings.signOutOfLastfm() else onOpenLastfm() },
        )
        if (lastfmEnabled && signedIn) {
            val scrobble by DesktopScrobbleSettings.lastfmScrobble.collectAsState()
            val nowPlaying by DesktopScrobbleSettings.lastfmNowPlaying.collectAsState()
            val primaryOnly by DesktopScrobbleSettings.lastfmPrimaryArtistOnly.collectAsState()
            IntegrationRule()
            PanelSwitchRow(
                title = DesktopStrings["scrobble_tracks", "Scrobble tracks"],
                subtitle = DesktopStrings["scrobble_tracks_subtitle", "Log plays to your Last.fm timeline"],
                checked = scrobble,
                onCheckedChange = DesktopScrobbleSettings::setLastfmScrobble,
            )
            IntegrationRule()
            PanelSwitchRow(
                title = DesktopStrings["scrobble_primary_artist_only", "Only scrobble primary artist"],
                subtitle = DesktopStrings["scrobble_primary_artist_only_subtitle", "Use the first artist when a track credits multiple artists"],
                checked = primaryOnly,
                onCheckedChange = DesktopScrobbleSettings::setLastfmPrimaryArtistOnly,
            )
            IntegrationRule()
            PanelSwitchRow(
                title = DesktopStrings["now_playing", "Now playing"],
                subtitle = DesktopStrings["lastfm_now_playing_subtitle", "Update Last.fm with what you are listening to"],
                checked = nowPlaying,
                onCheckedChange = DesktopScrobbleSettings::setLastfmNowPlaying,
            )
        }
    }

    if (lastfmEnabled && signedIn) {
        val minDuration by DesktopScrobbleSettings.minDuration.collectAsState()
        val delayPercent by DesktopScrobbleSettings.delayPercent.collectAsState()
        val delaySeconds by DesktopScrobbleSettings.delaySeconds.collectAsState()
        PanelGroup(header = "Scrobble timing") {
            PanelSliderRow(
                "Minimum song duration",
                "Shorter songs will not scrobble",
                "${minDuration}s",
                minDuration.toFloat(),
                15f..120f,
                20,
            ) { DesktopScrobbleSettings.setMinDuration(it.roundToInt()) }
            IntegrationRule()
            PanelSliderRow(
                "Scrobble delay",
                "How far into a song to scrobble it",
                "${(delayPercent * 100).roundToInt()}%",
                delayPercent,
                0.1f..1.0f,
                8,
            ) { DesktopScrobbleSettings.setDelayPercent(it) }
            IntegrationRule()
            PanelSliderRow(
                "Maximum delay",
                "Maximum scrobble delay in seconds",
                "${delaySeconds}s",
                delaySeconds.toFloat(),
                30f..300f,
                26,
            ) { DesktopScrobbleSettings.setDelaySeconds(it.roundToInt()) }
        }
    }
}

/** What Discord will draw, so the switches above can be judged without leaving. */
@Composable
private fun RichPresencePreview(
    song: Song?,
    heading: String,
    verb: String,
    useDetails: Boolean,
    button1Text: String,
    button1Visible: Boolean,
    button2Text: String,
    button2Visible: Boolean,
) {
    val title = song?.title ?: "Song title"
    val artist = song?.artist ?: "Artist"
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            "$verb $heading".uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.W700,
            color = DesktopForeground,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            val shape = RoundedCornerShape(6.dp)
            if (song?.thumbnailUrl != null) {
                DesktopArtwork(song.thumbnailUrl, Modifier.size(84.dp).clip(shape), px = 240)
            } else {
                Box(Modifier.size(84.dp).clip(shape).background(DesktopDivider))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // Discord bolds whichever line the display type names, which is
                // the whole point of "Lead with the song".
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    fontWeight = if (useDetails) FontWeight.W700 else FontWeight.W400,
                    color = DesktopForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    artist,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    fontWeight = if (useDetails) FontWeight.W400 else FontWeight.W700,
                    color = DesktopForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                song?.albumName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = DesktopSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (button1Visible || button2Visible) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (button1Visible) {
                    PreviewButton(
                        DesktopDiscordSettings.resolveVariables(
                            button1Text.ifEmpty { DesktopDiscordSettings.DEFAULT_BUTTON_1 },
                            title, artist, song?.albumName,
                        ),
                    )
                }
                if (button2Visible) {
                    PreviewButton(
                        DesktopDiscordSettings.resolveVariables(
                            button2Text.ifEmpty { DesktopDiscordSettings.DEFAULT_BUTTON_2 },
                            title, artist, song?.albumName,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewButton(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(DesktopForeground.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = DesktopForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private class DiscordChoice(
    val title: String,
    val message: String,
    val options: List<DesktopDiscordSettings.Choice>,
    val selected: () -> String,
    val onSelect: (String) -> Unit,
)

private class DiscordField(
    val title: String,
    val message: String,
    val placeholder: String,
    val initial: () -> String,
    val onSave: (String) -> Unit,
)

@Composable
private fun PanelGroup(
    header: String? = null,
    footer: String? = null,
    /** Keeps its box on a page too — the preview, which is a picture of a card. */
    boxed: Boolean = false,
    content: @Composable () -> Unit,
) {
    val page = LocalDesktopPanelIsPage.current
    Column(Modifier.fillMaxWidth().padding(horizontal = panelInset(16.dp))) {
        header?.let {
            Text(
                it.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
                color = DesktopSecondary,
                modifier = Modifier.padding(start = panelInset(6.dp), top = 14.dp, bottom = 6.dp),
            )
        }
        Column(
            if (page && !boxed) Modifier.fillMaxWidth().settingsCard()
            else Modifier.fillMaxWidth().desktopCardInset(RoundedCornerShape(12.dp)),
        ) { content() }
        footer?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
                modifier = Modifier.padding(start = panelInset(6.dp), end = panelInset(6.dp), top = 6.dp),
            )
        }
    }
}

@Composable
private fun PanelSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    if (LocalDesktopPanelIsPage.current) {
        if (onClick == null) {
            DesktopSettingsRow(
                title, subtitle, enabled = enabled, checked = checked,
                onClick = { onCheckedChange(!checked) },
            )
        } else {
            DesktopSettingsRow(title, subtitle, enabled = enabled, onClick = onClick) {
                DesktopSettingsSwitch(checked, onCheckedChange, enabled, label = title)
            }
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .desktopRowClickable(enabled = enabled) { onClick?.invoke() ?: onCheckedChange(!checked) }
            .padding(horizontal = if (LocalDesktopPanelIsPage.current) 16.dp else 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) DesktopForeground else DesktopSecondary)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, colors = desktopSwitchColors())
    }
}

@Composable
private fun PanelValueRow(title: String, value: String, onClick: () -> Unit) {
    if (LocalDesktopPanelIsPage.current) {
        DesktopSettingsRow(title, value = value, onClick = onClick)
        return
    }
    Row(
        Modifier.fillMaxWidth().desktopRowClickable(onClick = onClick).padding(horizontal = panelInset(14.dp), vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = DesktopForeground, modifier = Modifier.weight(1f))
        Text(value, color = DesktopSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PanelActionRow(title: String, subtitle: String, destructive: Boolean, onClick: () -> Unit) {
    if (LocalDesktopPanelIsPage.current && !destructive) {
        DesktopSettingsRow(title, subtitle, onClick = onClick)
        return
    }
    Row(
        Modifier.fillMaxWidth().desktopRowClickable(onClick = onClick).padding(horizontal = if (LocalDesktopPanelIsPage.current) 16.dp else 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (destructive) DesktopDestructive else DesktopForeground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
        }
    }
}

@Composable
private fun PanelSliderRow(
    title: String,
    subtitle: String,
    readout: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = if (LocalDesktopPanelIsPage.current) 16.dp else 14.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = DesktopForeground)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
            }
            Text(readout, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        }
        DesktopBareSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth().height(26.dp),
        )
    }
}

/** One-of-several, the shape Android's `ChoiceAlert` has. */
@Composable
internal fun DesktopChoiceDialog(
    title: String,
    message: String,
    options: List<DesktopDiscordSettings.Choice>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 380, popup = true) {
        Column(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = DesktopForeground)
            Text(
                message,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
            )
        }
        options.forEach { option ->
            DesktopCardRule()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option.id) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(option.label, color = DesktopForeground)
                    Text(option.detail, style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
                }
                if (option.id == selected) {
                    Icon(BitChordIcons.Check, DesktopStrings["selected", "Selected"], tint = DesktopAccent, modifier = Modifier.size(18.dp))
                }
            }
        }
        DesktopCardRule()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(DesktopStrings["cancel", "Cancel"], color = DesktopSecondary) }
        }
    }
}

@Composable
private fun IntegrationRule() {
    if (LocalDesktopPanelIsPage.current) DesktopSettingsDivider() else DesktopCardRule()
}
