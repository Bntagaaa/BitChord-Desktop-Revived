package com.music.bitchord.desktop

import com.music.bitchord.data.settings.PlayerLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Restore the persisted expanded player layout, defaulting to [PlayerLayout.CLASSIC]. */
internal fun restoreDesktopPlayerLayout(stored: String): PlayerLayout =
    PlayerLayout.entries.firstOrNull { it.name == stored } ?: PlayerLayout.CLASSIC

/** Persist the expanded player layout selection for desktop. */
internal object DesktopPlayerLayoutSettings {
    private const val KEY_PLAYER_LAYOUT = "player_layout"

    private val selected = MutableStateFlow(
        restoreDesktopPlayerLayout(DesktopPersistence().string(KEY_PLAYER_LAYOUT, "")),
    )
    val layout: StateFlow<PlayerLayout> = selected

    fun setLayout(value: PlayerLayout) {
        if (selected.value == value) return
        DesktopPersistence().saveString(KEY_PLAYER_LAYOUT, value.name)
        selected.value = value
    }
}
