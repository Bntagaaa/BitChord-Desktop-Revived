package com.music.bitchord.desktop

import com.music.bitchord.desktop.icons.DesktopIcons

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

internal fun DesktopSettingsRows.contentPreferenceRows() {
    val prefs = DesktopContentPreferences
    toggle("Prefer music-only version",
        "Music videos play as their audio version; the next queue item is resolved ahead of time for smoother transitions.",
        prefs.preferMusicOnly, prefs::updatePreferMusicOnly, icon = DesktopIcons.Player.MusicVideo)
    toggle("Hide Recents", null, prefs.hideRecents, prefs::updateHideRecents, icon = DesktopIcons.Settings.VisibilityOff)
    toggle("Hide videos from Home",
        "Video-only shelves are hidden. Search, artists and playlists remain unchanged.",
        prefs.hideHomeVideos, prefs::updateHideHomeVideos, icon = DesktopIcons.Settings.VisibilityOff)
    toggle("Hide Top Picks for You", null, prefs.hideTopPicks, prefs::updateHideTopPicks, icon = DesktopIcons.Status.Sparkles)
    toggle("Hide Find Your Mood", null, prefs.hideHomeMoods, prefs::updateHideHomeMoods, icon = DesktopIcons.Player.Like)
    toggle("Square video thumbnails",
        "Display music videos with square covers wherever thumbnail layout supports it.",
        prefs.squareVideos, prefs::updateSquareVideos, icon = DesktopIcons.Navigation.GridView)
}
