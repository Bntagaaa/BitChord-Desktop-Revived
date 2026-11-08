package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Desktop-port of mobile's six Content Preferences options.
 * Shares persistent DesktopPersistence keys with the runtime policies below.
 */
internal object DesktopContentPreferences {
    private val persistence = DesktopPersistence()
    var preferMusicOnly by mutableStateOf(persistence.boolean("content_prefer_music_only", false))
        private set
    var hideRecents by mutableStateOf(persistence.boolean("content_hide_recents", false))
        private set
    var hideHomeVideos by mutableStateOf(persistence.boolean("content_hide_home_videos", false))
        private set
    var hideTopPicks by mutableStateOf(persistence.boolean("content_hide_top_picks", false))
        private set
    var hideHomeMoods by mutableStateOf(persistence.boolean("content_hide_home_moods", false))
        private set
    var squareVideos by mutableStateOf(persistence.boolean("content_square_video_thumbnails", false))
        private set

    fun updatePreferMusicOnly(value: Boolean) { preferMusicOnly = value; persistence.saveBoolean("content_prefer_music_only", value) }
    fun updateHideRecents(value: Boolean) { hideRecents = value; persistence.saveBoolean("content_hide_recents", value) }
    fun updateHideHomeVideos(value: Boolean) { hideHomeVideos = value; persistence.saveBoolean("content_hide_home_videos", value) }
    fun updateHideTopPicks(value: Boolean) { hideTopPicks = value; persistence.saveBoolean("content_hide_top_picks", value) }
    fun updateHideHomeMoods(value: Boolean) { hideHomeMoods = value; persistence.saveBoolean("content_hide_home_moods", value) }
    fun updateSquareVideos(value: Boolean) { squareVideos = value; persistence.saveBoolean("content_square_video_thumbnails", value) }
}

@Composable
internal fun DesktopContentPreferencesPage(onBack: () -> Unit) {
    val prefs = DesktopContentPreferences
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 860.dp).fillMaxWidth()) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to Settings") }
            Spacer(Modifier.height(10.dp))
            Text("Content Preferences", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(22.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column {
                    PreferenceOption(Icons.Rounded.Videocam, "Prefer music-only version",
                        "Music videos play as their audio version; the next queue item is resolved ahead of time for smoother transitions.",
                        prefs.preferMusicOnly, prefs::updatePreferMusicOnly)
                    PreferenceSeparator()
                    PreferenceOption(Icons.Rounded.VisibilityOff, "Hide Recents", null, prefs.hideRecents, prefs::updateHideRecents)
                    PreferenceSeparator()
                    PreferenceOption(Icons.Rounded.VisibilityOff, "Hide videos from Home",
                        "Video-only shelves are hidden. Search, artists and playlists remain unchanged.",
                        prefs.hideHomeVideos, prefs::updateHideHomeVideos)
                    PreferenceSeparator()
                    PreferenceOption(Icons.Rounded.AutoAwesome, "Hide Top Picks for You", null, prefs.hideTopPicks, prefs::updateHideTopPicks)
                    PreferenceSeparator()
                    PreferenceOption(Icons.Rounded.StarBorder, "Hide Find Your Mood", null, prefs.hideHomeMoods, prefs::updateHideHomeMoods)
                    PreferenceSeparator()
                    PreferenceOption(Icons.Rounded.GridView, "Square video thumbnails",
                        "Display music videos with square covers wherever thumbnail layout supports it.",
                        prefs.squareVideos, prefs::updateSquareVideos)
                }
            }
        }
    }
}

@Composable
private fun PreferenceSeparator() {
    HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PreferenceOption(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(horizontal = 22.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
