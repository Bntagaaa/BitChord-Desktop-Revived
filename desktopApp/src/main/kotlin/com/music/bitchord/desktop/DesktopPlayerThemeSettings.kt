package com.music.bitchord.desktop

import com.music.bitchord.data.settings.PlayerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Recover existing users' Legacy preference when upgrading to the three-way selector. */
internal fun restoreDesktopPlayerTheme(stored: String, oldLegacyEnabled: Boolean): PlayerTheme =
    PlayerTheme.entries.firstOrNull { it.name == stored }
        ?: if (oldLegacyEnabled) PlayerTheme.LEGACY else PlayerTheme.DEFAULT

/** Persist only a small enum; player rendering still lives in the shared UI module. */
internal object DesktopPlayerThemeSettings {
    private const val KEY_PLAYER_THEME = "player_theme"
    private const val KEY_LEGACY_MESH = "legacy_mesh_gradient"

    private val selected = MutableStateFlow(
        restoreDesktopPlayerTheme(
            DesktopPersistence().string(KEY_PLAYER_THEME, ""),
            DesktopPersistence().boolean(KEY_LEGACY_MESH, false),
        ),
    )
    val theme: StateFlow<PlayerTheme> = selected

    fun setTheme(value: PlayerTheme) {
        if (selected.value == value) return
        DesktopPersistence().saveString(KEY_PLAYER_THEME, value.name)
        selected.value = value
    }
}
