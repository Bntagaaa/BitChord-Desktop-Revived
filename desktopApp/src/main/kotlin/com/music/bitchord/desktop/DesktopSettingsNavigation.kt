package com.music.bitchord.desktop

import com.music.bitchord.desktop.icons.DesktopIcons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.screens.desktopTopScrollFade

internal enum class DesktopSettingsPage(private val key: String, private val fallback: String) {
    MAIN("settings", "Settings"),
    INTEGRATIONS("account_integrations", "Account & integrations"),
    LISTEN_TOGETHER("listen_together", "Listen Together"),
    SOURCES("d_music_sources", "Music sources"),
    AUDIO_QUALITY("audio_quality", "Audio quality"),
    DOWNLOADS("download_channel_name", "Downloads"),
    PLAYBACK("playback", "Playback"),
    DESKTOP_PLAYBACK("d_desktop_playback", "Desktop playback"),
    AUDIO_OUTPUT("audio_output", "Audio output"),
    AUDIO_PROCESSING("d_audio_processing", "Audio processing"),
    CONTENT_PREFERENCES("content_preferences", "Content preferences"),
    APPEARANCE("appearance", "Appearance"),
    PERFORMANCE("performance", "Performance"),
    WINDOW_SYSTEM("d_window_system", "Window & system"),
    LYRICS("open_lyrics", "Lyrics"),
    LOCAL_MUSIC("local_music", "Local music"),
    STORAGE("storage", "Storage"),
    LANGUAGE("language", "Language"),
    MISCELLANEOUS("miscellaneous", "Miscellaneous"),
    ADVANCED("advanced_options", "Advanced options"),
    APP_UPDATES("d_app_updates", "App updates"),
    FONT("font", "Font"),
    DOWNLOAD_QUALITY("download_quality", "Download quality"),
    AUTOMIX_PERFORMANCE("automix_performance", "Automix performance"),
    OUTPUT_PRECISION("d_output_precision", "Output precision"),
    APP_LANGUAGE("app_language", "App language"),
    EQUALIZER("equalizer", "Equalizer"),
    LYRICS_SOURCES("lyrics_sources", "Lyrics sources"),
    TRANSLATION_LANGUAGE("translation_language", "Translation language"),
    SPOTIFY_CANVAS("spotify_canvas_setup", "Spotify Canvas setup"),
    LICENSES("d_third_party_licenses", "Third-party licenses"),
    ;

    val title: String get() = DesktopStrings[key, fallback]
}

@Stable
internal class DesktopSettingsScroll {
    val overview = LazyListState()
    val results = LazyListState()
    private val pages = mutableMapOf<DesktopSettingsPage, LazyListState>()
    fun forPage(page: DesktopSettingsPage): LazyListState = pages.getOrPut(page) { LazyListState() }
}

internal class DesktopSettingsSections(
    private val page: DesktopSettingsPage,
    private val searching: Boolean,
    private val query: DesktopSettingsQuery,
) {
    val groups = mutableListOf<Pair<DesktopSettingsPage, DesktopSettingsRows>>()

    // Inline so reads of preference state in the declaration and rendering the rows share a recompose scope.
    @Composable
    inline fun section(route: DesktopSettingsPage, content: @Composable DesktopSettingsRows.() -> Unit) {
        if (page != route && !searching) return
        val rows = DesktopSettingsRows(query, route.title)
        rows.content()
        if (rows.entries.isNotEmpty()) groups += route to rows
    }
}

private class Category(val page: DesktopSettingsPage, val icon: ImageVector)
private val categories = listOf(
    listOf(
        Category(DesktopSettingsPage.INTEGRATIONS, DesktopIcons.Settings.Account),
        Category(DesktopSettingsPage.LISTEN_TOGETHER, DesktopIcons.Status.Groups),
        Category(DesktopSettingsPage.SOURCES, BitChordIcons.MusicNote),
        Category(DesktopSettingsPage.AUDIO_QUALITY, DesktopIcons.Audio.Headphones),
    ),
    listOf(
        Category(DesktopSettingsPage.DOWNLOADS, BitChordIcons.Download),
        Category(DesktopSettingsPage.PLAYBACK, DesktopIcons.Player.PlayCircle),
        Category(DesktopSettingsPage.DESKTOP_PLAYBACK, DesktopIcons.Settings.Appearance),
        Category(DesktopSettingsPage.AUDIO_OUTPUT, DesktopIcons.Audio.Speaker),
        Category(DesktopSettingsPage.AUDIO_PROCESSING, DesktopIcons.Settings.Tune),
        Category(DesktopSettingsPage.CONTENT_PREFERENCES, DesktopIcons.Player.MusicVideo),
        Category(DesktopSettingsPage.APPEARANCE, DesktopIcons.Settings.Appearance),
        Category(DesktopSettingsPage.PERFORMANCE, BitChordIcons.Performance),
        Category(DesktopSettingsPage.WINDOW_SYSTEM, DesktopIcons.Settings.General),
        Category(DesktopSettingsPage.LYRICS, BitChordIcons.Lyrics),
        Category(DesktopSettingsPage.LOCAL_MUSIC, BitChordIcons.Library),
        Category(DesktopSettingsPage.STORAGE, DesktopIcons.Settings.Storage),
        Category(DesktopSettingsPage.LANGUAGE, DesktopIcons.Settings.Language),
        Category(DesktopSettingsPage.MISCELLANEOUS, DesktopIcons.Status.Info),
        Category(DesktopSettingsPage.ADVANCED, DesktopIcons.Settings.Bug),
        Category(DesktopSettingsPage.APP_UPDATES, DesktopIcons.Settings.Upgrade),
    ),
)

@Composable
internal fun DesktopSettingsOverview(
    hasUpdater: Boolean,
    audioQuality: String,
    onOpenPage: (DesktopSettingsPage) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        categories.forEach { group ->
            DesktopSettingsGroup {
                group.filter { it.page != DesktopSettingsPage.APP_UPDATES || hasUpdater }.forEachIndexed { index, category ->
                    if (index > 0) DesktopSettingsDivider(insetForIcon = true)
                    DesktopSettingsRow(
                        category.page.title,
                        icon = category.icon,
                        value = audioQuality.takeIf { category.page == DesktopSettingsPage.AUDIO_QUALITY },
                        onClick = { onOpenPage(category.page) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun <T> DesktopSettingsChoicePage(
    title: String,
    options: List<T>,
    selected: T,
    listState: LazyListState,
    label: (T) -> String,
    detail: ((T) -> String)? = null,
    footer: String? = null,
    onSelect: (T) -> Unit,
) {
    DesktopSettingsPageFrame {
        DesktopSettingsPageTitle(title)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().desktopTopScrollFade(listState),
            state = listState,
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                DesktopSettingsGroup(footer = footer) {
                    Column(Modifier.selectableGroup()) {
                        options.forEachIndexed { index, option ->
                            if (index > 0) DesktopSettingsDivider()
                            DesktopSettingsRow(
                                label(option),
                                subtitle = detail?.invoke(option),
                                selected = option == selected,
                                onClick = { onSelect(option) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DesktopSettingsSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = DesktopStrings["settings_search_hint", "Search settings"],
) {
    DesktopSearchField(
        query,
        onQueryChange,
        onSearch = {},
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        placeholder = placeholder,
        pill = true,
        settings = true,
    )
}
