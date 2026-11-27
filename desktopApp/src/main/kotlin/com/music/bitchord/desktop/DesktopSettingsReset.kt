package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.system.exitProcess

internal enum class DesktopResetMode {
    SETTINGS_ONLY,
    ALL_DATA,
}

/**
 * Resets desktop configuration (or full app state) stored in Java [Preferences] and caches.
 */
internal object DesktopSettingsReset {
    internal const val MAIN_PREFERENCES_NODE = "com.music.bitchord.desktop"
    internal const val PIP_PREFERENCES_NODE = "com.music.bitchord.desktop/pip-v2"
    internal const val STATS_PREFERENCES_NODE = "com.music.bitchord.desktop.stats"

    private val PRESERVED_USER_DATA_KEYS = setOf(
        "google_accounts_v1",
        "active_google_account_v1",
        "active_google_profile_v1",
        "liked_ids",
        "disliked_ids",
        "original_versions",
        "history",
        "queue",
        "queue_index",
        "downloads",
        "downloaded_playlists",
        "playlists",
        "playlist_downloads",
        "pinned_playlists",
    )

    private val PRESERVED_USER_DATA_PREFIXES = listOf(
        "google_cookie_",
        "first_run_install_v2_",
    )

    internal fun isPreservedUserDataKey(key: String): Boolean {
        if (key in PRESERVED_USER_DATA_KEYS) return true
        val lastDot = key.lastIndexOf('.')
        if (lastDot > 0) {
            val suffix = key.substring(lastDot + 1)
            if (suffix.isNotEmpty() && suffix.all(Char::isDigit)) {
                val base = key.substring(0, lastDot)
                if (base in PRESERVED_USER_DATA_KEYS) return true
            }
        }
        return PRESERVED_USER_DATA_PREFIXES.any { prefix -> key.startsWith(prefix) }
    }

    /**
     * Removes every preference key in [preferences] except preserved user-data keys, and clears
     * [pipPreferences]. Leaving unknown/fork keys in the removal set ensures stale settings from
     * other forks or versions are cleaned up as well.
     */
    fun resetSettings(
        preferences: Preferences = Preferences.userRoot().node(MAIN_PREFERENCES_NODE),
        pipPreferences: Preferences = Preferences.userRoot().node(PIP_PREFERENCES_NODE),
    ) {
        val keys = runCatching { preferences.keys() }.getOrDefault(emptyArray())
        for (key in keys) {
            if (!isPreservedUserDataKey(key)) {
                preferences.remove(key)
            }
        }
        runCatching { preferences.flush() }
        runCatching {
            pipPreferences.clear()
            pipPreferences.flush()
        }
    }

    /**
     * Clears all desktop preferences nodes (including accounts, library metadata, and listening
     * stats) as well as media, audio, and artwork caches. Downloaded files in ~/Music/BitChord
     * are not deleted.
     */
    fun resetAllAppData(
        preferences: Preferences = Preferences.userRoot().node(MAIN_PREFERENCES_NODE),
        pipPreferences: Preferences = Preferences.userRoot().node(PIP_PREFERENCES_NODE),
        statsPreferences: Preferences = Preferences.userRoot().node(STATS_PREFERENCES_NODE),
        clearCaches: () -> Unit = ::clearAllCaches,
    ) {
        runCatching {
            preferences.clear()
            preferences.flush()
        }
        runCatching {
            pipPreferences.clear()
            pipPreferences.flush()
        }
        runCatching {
            statsPreferences.clear()
            statsPreferences.flush()
        }
        runCatching { clearCaches() }
    }

    private fun clearAllCaches() {
        DesktopMediaCache.clear()
        DesktopAudioCache.shared.clear()
        DesktopArtworkCache.clear()
    }

    internal fun resolveRelaunchCommand(
        appImagePath: String? = System.getenv("APPIMAGE"),
        jpackageAppPath: String? = System.getProperty("jpackage.app-path"),
        processCommand: String? = runCatching { ProcessHandle.current().info().command().orElse(null) }.getOrNull(),
        processArguments: List<String> = runCatching {
            ProcessHandle.current().info().arguments().orElse(emptyArray()).toList()
        }.getOrDefault(emptyList()),
    ): List<String>? {
        val appImage = appImagePath?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        if (appImage != null && Files.isRegularFile(appImage)) {
            return listOf(appImage.toAbsolutePath().normalize().toString())
        }
        val jpackageApp = jpackageAppPath?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        if (jpackageApp != null && Files.isRegularFile(jpackageApp)) {
            return listOf(jpackageApp.toAbsolutePath().normalize().toString())
        }
        val command = processCommand?.takeIf { it.isNotBlank() } ?: return null
        return listOf(command) + processArguments
    }

    /**
     * Spawns a fresh instance of BitChord and exits the current process. Returns an error message
     * only if relaunching could not be started.
     */
    fun relaunchApplication(): String? {
        val command = resolveRelaunchCommand()
            ?: return DesktopStrings[
                "d_reset_restart_unavailable",
                "Unable to determine the BitChord executable. Please restart BitChord manually.",
            ]
        return runCatching {
            ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            DesktopApplicationResources.close()
            exitProcess(0)
        }.exceptionOrNull()?.let { error ->
            "Could not restart BitChord automatically (${error.message ?: "unknown error"}). Please restart manually."
        }
    }
}
