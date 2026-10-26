package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Each area has a separate persisted preference. Existing installations keep their previous
 * appearance because all six areas start enabled; Reduce dynamic blur remains authoritative.
 */
internal enum class DesktopBlurArea(val title: String, val description: String, val preferenceKey: String) {
    SIDE_PANELS(
        "Lyrics, Queue & Details panels",
        "The frosted right-hand panels on Home",
        "blur_side_panels",
    ),
    QUICK_SEARCH(
        "Quick Search",
        "The Ctrl+K search popup",
        "blur_quick_search",
    ),
    SEARCH_PAGE(
        "Search",
        "The search field, suggestions and Explore landing",
        "blur_search_page",
    ),
    FLOATING_PLAYER(
        "Floating player",
        "The bottom music player and compact navigation",
        "blur_floating_player",
    ),
    MENUS_POPUPS(
        "Menus & popups",
        "Profile selector, sign-in prompts and dropdown menus",
        "blur_menus_popups",
    ),
    APP_CHROME(
        "Top bar & navigation",
        "The in-app toolbar and navigation glass, not the native title bar",
        "blur_app_chrome",
    ),
}

internal enum class DesktopPerformanceProfile(val label: String, val description: String) {
    FULL("Full", "Full visual richness and frosted glass effects across all surfaces"),
    BALANCED("Balanced", "Balanced aesthetic with optimized blur effects"),
    PERFORMANCE("Performance", "Maximum rendering responsiveness with minimal blur"),
    AUTO("Auto", "Automatically select based on system CPU and memory capabilities"),
    ;

    fun resolved(): DesktopPerformanceProfile = when (this) {
        AUTO -> detectHardwareProfile()
        else -> this
    }
}

internal fun detectHardwareProfile(): DesktopPerformanceProfile {
    return runCatching {
        val cores = Runtime.getRuntime().availableProcessors()
        val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        when {
            cores <= 1 || maxMemoryMb < 512 -> DesktopPerformanceProfile.PERFORMANCE
            cores in 2..4 -> DesktopPerformanceProfile.BALANCED
            else -> DesktopPerformanceProfile.FULL
        }
    }.getOrDefault(DesktopPerformanceProfile.BALANCED)
}

/**
 * Master override invariant: Whenever reduceDynamicBlur is true, ALL dynamic blur effects
 * MUST be disabled regardless of performance profile, per-area preferences, or component.
 */
internal fun effectiveDesktopBlur(
    reduceDynamicBlur: Boolean,
    selected: Boolean,
    profile: DesktopPerformanceProfile = DesktopPerformanceProfile.FULL,
): Boolean {
    if (reduceDynamicBlur) return false
    val effectiveProfile = profile.resolved()
    if (effectiveProfile == DesktopPerformanceProfile.PERFORMANCE) return false
    return selected
}

/**
 * The toggles read far from the Settings sheet that sets them.
 *
 * Mirrors Android's `AppSettings` for these few: a composable deep in the player or the lyrics
 * pane can collect one without it being threaded down through every caller.
 */
internal object DesktopAppearanceSettings {

    private fun flag(key: String, default: Boolean) =
        MutableStateFlow(DesktopPersistence().boolean(key, default))

    private val _reduceAnimation = flag(KEY_REDUCE_ANIMATION, false)
    private val _reduceDynamicBlur = flag(KEY_REDUCE_DYNAMIC_BLUR, false)
    private val _blurPreferences = DesktopBlurArea.entries.associateWith { flag(it.preferenceKey, true) }
    private val _performanceProfile = MutableStateFlow(
        runCatching {
            DesktopPerformanceProfile.valueOf(DesktopPersistence().string(KEY_PERFORMANCE_PROFILE, "AUTO"))
        }.getOrDefault(DesktopPerformanceProfile.AUTO),
    )
    // Mica-style navigation is lightweight wallpaper tinting, independent of blur.
    private val _micaSidebar = flag(KEY_MICA_SIDEBAR, DesktopSidebarMica.isSupported)
    private val _hideVolumeBar = flag(KEY_HIDE_VOLUME_BAR, false)
    // New floating bottom player is the default. The original top player is opt-in.
    private val _enableOldTopMusicPlayerStyle = flag(KEY_ENABLE_OLD_TOP_MUSIC_PLAYER_STYLE, false)
    // v1.8 Explore cards remain the default; the classic v1.7 sleeve can be opted into.
    private val _useOldExploreCards = flag(KEY_USE_OLD_EXPLORE_CARDS, false)

    /** Freezes the main player's gradient instead of drifting. */
    val reduceAnimation: StateFlow<Boolean> = _reduceAnimation

    /** Swaps frosted glass for solid fills across the app. */
    val reduceDynamicBlur: StateFlow<Boolean> = _reduceDynamicBlur

    /** Performance profile for graphics rendering. */
    val performanceProfile: StateFlow<DesktopPerformanceProfile> = _performanceProfile

    /** Individual preferences, observed live by the components that draw the glass. */
    fun blurPreference(area: DesktopBlurArea): StateFlow<Boolean> = requireNotNull(_blurPreferences[area])

    /** Includes the master override, so no caller can accidentally ignore Reduce dynamic blur. */
    @Composable
    fun blurEnabled(area: DesktopBlurArea): Boolean {
        val reduced by reduceDynamicBlur.collectAsState()
        val selected by blurPreference(area).collectAsState()
        val profile by performanceProfile.collectAsState()
        return effectiveDesktopBlur(reduced, selected, profile)
    }

    /** Windows 11 wallpaper-tinted, opaque navigation material. */
    val micaSidebar: StateFlow<Boolean> = _micaSidebar

    /** Removes the volume slider from the main player. */
    val hideVolumeBar: StateFlow<Boolean> = _hideVolumeBar

    /** Restores the original desktop top player instead of the floating bottom dock. */
    val enableOldTopMusicPlayerStyle: StateFlow<Boolean> = _enableOldTopMusicPlayerStyle

    /** Switches Explore category artwork between current and classic v1.7 cards. */
    val useOldExploreCards: StateFlow<Boolean> = _useOldExploreCards

    /** Whether the lyrics pane carries its own log console. */

    fun setReduceAnimation(value: Boolean) = write(KEY_REDUCE_ANIMATION, value, _reduceAnimation)

    fun setReduceDynamicBlur(value: Boolean) = write(KEY_REDUCE_DYNAMIC_BLUR, value, _reduceDynamicBlur)

    fun setPerformanceProfile(profile: DesktopPerformanceProfile) {
        DesktopPersistence().saveString(KEY_PERFORMANCE_PROFILE, profile.name)
        _performanceProfile.value = profile
    }

    fun setBlurPreference(area: DesktopBlurArea, enabled: Boolean) =
        write(area.preferenceKey, enabled, requireNotNull(_blurPreferences[area]))

    fun setMicaSidebar(value: Boolean) = write(KEY_MICA_SIDEBAR, value, _micaSidebar)

    fun setHideVolumeBar(value: Boolean) = write(KEY_HIDE_VOLUME_BAR, value, _hideVolumeBar)

    fun setEnableOldTopMusicPlayerStyle(value: Boolean) =
        write(KEY_ENABLE_OLD_TOP_MUSIC_PLAYER_STYLE, value, _enableOldTopMusicPlayerStyle)

    fun setUseOldExploreCards(value: Boolean) =
        write(KEY_USE_OLD_EXPLORE_CARDS, value, _useOldExploreCards)


    private fun write(key: String, value: Boolean, into: MutableStateFlow<Boolean>) {
        DesktopPersistence().saveBoolean(key, value)
        into.value = value
    }

    internal const val KEY_REDUCE_ANIMATION = "reduce_animation"
    internal const val KEY_REDUCE_DYNAMIC_BLUR = "reduce_dynamic_blur"
    internal const val KEY_PERFORMANCE_PROFILE = "performance_profile"
    internal const val KEY_MICA_SIDEBAR = "mica_sidebar"
    internal const val KEY_HIDE_VOLUME_BAR = "hide_volume_bar"
    internal const val KEY_ENABLE_OLD_TOP_MUSIC_PLAYER_STYLE = "enable_old_top_music_player_style"
    internal const val KEY_USE_OLD_EXPLORE_CARDS = "use_old_explore_cards"
}
