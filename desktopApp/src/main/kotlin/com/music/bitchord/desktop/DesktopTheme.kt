package com.music.bitchord.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Follow the OS until the listener explicitly picks a theme. */
internal enum class DesktopThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");

    companion object {
        fun fromStored(value: String): DesktopThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/**
 * One persisted theme selection, with snapshot state so reads of palette tokens inside Compose
 * automatically invalidate on a live change. No window recreation or account-state reset.
 */
internal object DesktopTheme {
    private const val KEY = "desktop_theme_mode"
    private const val KEY_PITCH_BLACK = "desktop_pitch_black"

    var mode: DesktopThemeMode by mutableStateOf(
        DesktopThemeMode.fromStored(DesktopPersistence().string(KEY, DesktopThemeMode.SYSTEM.name)),
    )
        private set

    private var systemDark: Boolean by mutableStateOf(DesktopSystemTheme.isDark())

    /** Saved independently of the system/light/dark choice; defaults to classic dark. */
    var pitchBlack: Boolean by mutableStateOf(DesktopPersistence().boolean(KEY_PITCH_BLACK, false))
        private set

    /** Effective theme. Use this for *all* UI decisions, never mode == LIGHT/DARK. */
    val isDark: Boolean get() = resolveDark(mode, systemDark)
    val isLight: Boolean get() = !isDark
    val palette: DesktopThemePalette get() = resolvePalette(isDark, pitchBlack)

    internal fun resolvePalette(dark: Boolean, pitchBlack: Boolean): DesktopThemePalette = when {
        !dark -> LIGHT_PALETTE
        pitchBlack -> PITCH_BLACK_PALETTE
        else -> DARK_PALETTE
    }

    internal fun resolveDark(choice: DesktopThemeMode, osDark: Boolean): Boolean = when (choice) {
        DesktopThemeMode.SYSTEM -> osDark
        DesktopThemeMode.LIGHT -> false
        DesktopThemeMode.DARK -> true
    }

    /** Called from the main Compose window after sampling the OS on a worker dispatcher. */
    fun updateSystemDark(value: Boolean) {
        if (systemDark != value) systemDark = value
    }

    fun selectMode(value: DesktopThemeMode) {
        if (mode == value) return
        DesktopPersistence().saveString(KEY, value.name)
        mode = value
    }

    fun setPitchBlack(enabled: Boolean) {
        if (pitchBlack == enabled) return
        DesktopPersistence().saveBoolean(KEY_PITCH_BLACK, enabled)
        pitchBlack = enabled
    }
}

/** Semantic desktop tokens; artwork overlays may deliberately keep white-on-black treatment. */
internal data class DesktopThemePalette(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val glass: Color,
    val glassStrong: Color,
    val barGlass: Color,
    val barEdge: Color,
    val accent: Color,
    val onAccent: Color,
    val foreground: Color,
    val secondary: Color,
    val divider: Color,
    val destructive: Color,
    val cardFill: Color,
    val cardInsetFill: Color,
    val cardEdge: Color,
    val rowHover: Color,
    val scrim: Color,
    val searchSurface: Color,
    val topPlayerSurface: Color,
    val floatingPlayerSurface: Color,
    val subtleFill: Color,
    val stripedRow: Color,
    val navAccent: Color,
    val sidebarSurface: Color,
    val chromeSurface: Color,
    val lyricsSurface: Color,
    val replaySurface: Color,
)

internal val DARK_PALETTE = DesktopThemePalette(
    background = Color(0xFF202020),
    surface = Color(0xFF0D0D0F),
    surfaceRaised = Color(0xFF1C1C1E),
    glass = Color(0x661C1C1E),
    glassStrong = Color(0xCC0D0D0F),
    barGlass = Color(0xB324242A),
    barEdge = Color(0x1AFFFFFF),
    accent = Color.White,
    onAccent = Color.Black,
    foreground = Color.White,
    secondary = Color(0xFF8E8E93),
    divider = Color(0xFF2C2C2E),
    destructive = Color(0xFFFF453A),
    cardFill = Color(0xFA1A1A1D),
    cardInsetFill = Color(0x14FFFFFF),
    cardEdge = Color(0x1FFFFFFF),
    rowHover = Color(0x1AFFFFFF),
    scrim = Color(0x8C000000),
    searchSurface = Color(0xFF27272B),
    topPlayerSurface = Color(0xFF323236),
    floatingPlayerSurface = Color(0xFF202124),
    subtleFill = Color(0x14FFFFFF),
    stripedRow = Color(0x0AFFFFFF),
    navAccent = Color(0xFFFA586A),
    sidebarSurface = Color(0xFF0D0D0F),
    chromeSurface = Color(0xFF0D0D0F),
    lyricsSurface = Color(0xFF202020),
    replaySurface = Color(0xFF202020),
)

/** Opt-in near-black content and charcoal navigation; other dark tokens are unchanged. */
internal val PITCH_BLACK_PALETTE = DARK_PALETTE.copy(
    background = Color(0xFF0D0D0F),
    sidebarSurface = Color(0xFF202020),
)

/**
 * Neutral, slightly warm daylight surfaces and charcoal controls, with BitChord's rose navigation
 * identity retained. Strong text/icon contrast also works when blur is disabled.
 */
internal val LIGHT_PALETTE = DesktopThemePalette(
    background = Color(0xFFF7F8FB),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEDEFF4),
    glass = Color(0xDDEFF1F5),
    glassStrong = Color(0xF4F7F8FC),
    barGlass = Color(0xE8E7EBF1),
    barEdge = Color(0x251D2636),
    accent = Color(0xFF1C1D21),
    onAccent = Color.White,
    foreground = Color(0xFF1B1C20),
    secondary = Color(0xFF4D5767),
    divider = Color(0xFFD5DAE4),
    destructive = Color(0xFFBE3545),
    cardFill = Color(0xFFFFFFFF),
    cardInsetFill = Color(0xFFEEF1F6),
    cardEdge = Color(0x241D2636),
    rowHover = Color(0x1E25324D),
    scrim = Color(0x750D0E13),
    searchSurface = Color(0xFFE8ECF3),
    topPlayerSurface = Color(0xFFE4E8EF),
    floatingPlayerSurface = Color(0xFFE5E9F0),
    subtleFill = Color(0x111D2636),
    stripedRow = Color(0x0D1D2636),
    navAccent = Color(0xFFCD3659),
    sidebarSurface = Color(0xFFE9ECF2),
    chromeSurface = Color(0xFFF0F2F6),
    lyricsSurface = Color(0xFFEDF0F6),
    replaySurface = Color(0xFFF5F6FA),
)
