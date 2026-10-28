package com.music.bitchord.desktop

import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.app_icon
import bitchord.desktopapp.generated.resources.app_icon_light
import java.util.prefs.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.DrawableResource

/** Independent of the app theme: a light window can still use the dark app icon. */
internal enum class DesktopAppIcon(
    val resource: DrawableResource,
    val svgPath: String,
) {
    DARK(Res.drawable.app_icon, "drawable/app_icon_dark.svg"),
    LIGHT(Res.drawable.app_icon_light, "drawable/app_icon_light_source.svg"),
}

internal object DesktopAppIconSettings {
    internal const val KEY = "desktop_app_icon"
    private val persistence = DesktopPersistence()
    private val _selected = MutableStateFlow(read(persistence.preferences))
    val selected: StateFlow<DesktopAppIcon> = _selected

    fun select(icon: DesktopAppIcon) {
        if (_selected.value == icon) return
        persistence.saveString(KEY, icon.name)
        _selected.value = icon
    }

    internal fun read(preferences: Preferences): DesktopAppIcon =
        DesktopAppIcon.entries.firstOrNull { it.name == DesktopPreferenceChunks.read(preferences, KEY) }
            ?: DesktopAppIcon.DARK
}
