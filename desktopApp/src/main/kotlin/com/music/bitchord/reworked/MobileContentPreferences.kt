package com.music.bitchord.reworked

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop presentation of the original mobile Content Preferences screen.
 * Reuse the reworked mobile-backed settings and playback policy so the switches
 * update the feed/player immediately and survive a Windows/Linux restart.
 */
@Composable
internal fun MobileContentPreferencesPage(onBack: () -> Unit) {
    val settings = MobilePlayerSettings

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 860.dp).fillMaxWidth()) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to Settings")
            }
            Spacer(Modifier.height(12.dp))
            Text("Content Preferences", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(22.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column {
                    ContentPreferenceRow(
                        icon = Icons.Rounded.Videocam,
                        title = "Prefer music-only version",
                        description = "Music videos play as their audio version, and the next track in the queue is converted ahead of time so Automix and crossfade work",
                        enabled = MobilePlaybackPolicy.preferMusicOnly,
                        onChange = MobilePlaybackPolicy::setPreferMusicOnly,
                    )
                    PreferenceDivider()
                    ContentPreferenceRow(
                        icon = Icons.Rounded.VisibilityOff,
                        title = "Hide Recents",
                        enabled = settings.hideRecents,
                        onChange = { settings.flag("hide_recents", settings.hideRecents, it) },
                    )
                    PreferenceDivider()
                    ContentPreferenceRow(
                        icon = Icons.Rounded.VisibilityOff,
                        title = "Hide videos from Home",
                        description = "Shelves of only videos go too. Search, artists and playlists still show them",
                        enabled = settings.hideHomeVideos,
                        onChange = { settings.flag("hide_home_videos", settings.hideHomeVideos, it) },
                    )
                    PreferenceDivider()
                    ContentPreferenceRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "Hide Top Picks for You",
                        enabled = settings.hideTopPicks,
                        onChange = { settings.flag("hide_top_picks", settings.hideTopPicks, it) },
                    )
                    PreferenceDivider()
                    ContentPreferenceRow(
                        icon = Icons.Rounded.StarBorder,
                        title = "Hide Find Your Mood",
                        enabled = settings.hideHomeMoods,
                        onChange = { settings.flag("hide_home_moods", settings.hideHomeMoods, it) },
                    )
                    PreferenceDivider()
                    ContentPreferenceRow(
                        icon = Icons.Rounded.GridView,
                        title = "Square video thumbnails",
                        description = "Show music videos as squares everywhere, including the player",
                        enabled = settings.squareVideoThumbnails,
                        onChange = { settings.flag("square_video_thumbnails", settings.squareVideoThumbnails, it) },
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ContentPreferenceRow(
    icon: ImageVector,
    title: String,
    enabled: StateFlow<Boolean>,
    onChange: (Boolean) -> Unit,
    description: String? = null,
) {
    val checked by enabled.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (description != null) {
                Text(
                    description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PreferenceDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}
