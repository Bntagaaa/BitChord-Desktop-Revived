package com.music.bitchord.reworked

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Reworked's desktop choices. Playback and shared screens remain upstream's. */
internal object ReworkedAppearance {
    enum class Theme { SYSTEM, LIGHT, DARK }
    enum class Font { DEFAULT, LEGACY }
    private val store = DesktopStore.preferences
    var theme by mutableStateOf(runCatching { Theme.valueOf(store.get("reworked_theme", "SYSTEM")) }.getOrDefault(Theme.SYSTEM))
        private set
    var font by mutableStateOf(runCatching { Font.valueOf(store.get("reworked_font", "DEFAULT")) }.getOrDefault(Font.DEFAULT))
        private set
    var pitchBlack by mutableStateOf(store.getBoolean("reworked_pitch_black", false))
        private set
    var collapsedSidebar by mutableStateOf(store.getBoolean("reworked_sidebar_collapsed", false))
        private set
    var bottomPlayer by mutableStateOf(store.getBoolean("reworked_bottom_player", true))
        private set
    var zoom by mutableStateOf(store.getInt("reworked_zoom", 100).takeIf { it in listOf(80,90,100,110,125) } ?: 100)
        private set
    var systemDark by mutableStateOf(true)
    val dark: Boolean get() = theme == Theme.DARK || theme == Theme.SYSTEM && systemDark
    val black: Boolean get() = theme == Theme.DARK && pitchBlack
    val foreground: Color get() = if (dark) Color.White else Color(0xFF18181B)
    val background: Color get() = if (black) Color.Black else if (dark) Color(0xFF202020) else Color(0xFFF5F5F7)
    val surface: Color get() = if (black) Color.Black else if (dark) Color(0xFF18181B) else Color.White
    val raised: Color get() = if (dark) Color(0xFF2B2B2E) else Color(0xFFE8E8ED)
    val secondary: Color get() = if (dark) Color(0xFFAAAAAF) else Color(0xFF626269)
    val divider: Color get() = if (dark) Color(0xFF35353A) else Color(0xFFD5D5DB)
    val accent = Color(0xFFFA2D48)
    @JvmName("updateTheme")
    fun setTheme(value: Theme) { theme = value; store.put("reworked_theme", value.name) }
    @JvmName("updateFont")
    fun setFont(value: Font) { font = value; store.put("reworked_font", value.name) }
    @JvmName("updatePitchBlack")
    fun setPitchBlack(value: Boolean) { pitchBlack = value; store.putBoolean("reworked_pitch_black", value) }
    @JvmName("updateCollapsedSidebar")
    fun setCollapsedSidebar(value: Boolean) { collapsedSidebar = value; store.putBoolean("reworked_sidebar_collapsed", value) }
    @JvmName("updateBottomPlayer")
    fun setBottomPlayer(value: Boolean) { bottomPlayer = value; store.putBoolean("reworked_bottom_player", value) }
    @JvmName("updateZoom")
    fun setZoom(value: Int) {
        require(value in listOf(80,90,100,110,125))
        zoom = value; store.putInt("reworked_zoom", value)
    }
}

@Composable
internal fun ReworkedAppearanceSettings() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Desktop appearance", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReworkedAppearance.Theme.entries.forEach { value ->
                FilterChip(selected = ReworkedAppearance.theme == value, onClick = { ReworkedAppearance.setTheme(value) },
                    label = { Text(value.name.lowercase().replaceFirstChar(Char::titlecase)) })
            }
        }
        if (ReworkedAppearance.theme == ReworkedAppearance.Theme.DARK) {
            ReworkedToggle("Pitch black", ReworkedAppearance.pitchBlack, ReworkedAppearance::setPitchBlack)
        }
        Text("Font", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReworkedAppearance.Font.entries.forEach { value ->
                FilterChip(selected = ReworkedAppearance.font == value, onClick = { ReworkedAppearance.setFont(value) },
                    label = { Text(value.name.lowercase().replaceFirstChar(Char::titlecase)) })
            }
        }
        Text("Interface size", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(80,90,100,110,125).forEach { value ->
                FilterChip(selected = ReworkedAppearance.zoom == value, onClick = { ReworkedAppearance.setZoom(value) }, label = { Text("$value%") })
            }
        }
        ReworkedToggle("Collapsed sidebar", ReworkedAppearance.collapsedSidebar, ReworkedAppearance::setCollapsedSidebar)
        ReworkedToggle("Player at the bottom", ReworkedAppearance.bottomPlayer, ReworkedAppearance::setBottomPlayer)
    }
}

@Composable
internal fun ReworkedToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}
