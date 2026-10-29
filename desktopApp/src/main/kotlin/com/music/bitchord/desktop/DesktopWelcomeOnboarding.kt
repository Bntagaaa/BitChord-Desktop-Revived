package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.prefs.Preferences

/**
 * Welcome is scoped to an installed/portable copy of BitChord, NOT to the signed-in user.
 *
 * Java Preferences and the Google/Spotify sessions are intentionally shared across copies.
 * Keying onboarding to an executable's location and file identity makes the first launch of
 * a newly extracted portable or newly installed launcher show welcome even with old accounts.
 * A subsequent launch of that same copy does not. Replacing the launcher during an update or
 * reinstall gives it a new file identity and a new first-run welcome.
 */
internal object DesktopWelcomeOnboarding {
    internal data class Launch(val showWelcome: Boolean, val markerKey: String)

    private const val PREFERENCES_NODE = "com.music.bitchord.desktop"
    private const val MARKER_PREFIX = "first_run_install_v2_"
    private const val FORCE_WELCOME_ENV = "BITCHORD_SHOW_WELCOME"

    /** Capture this once in main(), before other services initialize or write preferences. */
    fun inspectStartup(): Launch {
        val identity = installationIdentity()
        val forced = forceRequested(System.getenv(FORCE_WELCOME_ENV))
        val preferences = Preferences.userRoot().node(PREFERENCES_NODE)
        return inspect(preferences, identity, forced)
    }

    /** Does not write anything: merely looks up the marker for this particular app copy. */
    internal fun inspect(preferences: Preferences, identity: String, forced: Boolean = false): Launch {
        val key = markerKey(identity)
        return Launch(
            showWelcome = forced || !preferences.getBoolean(key, false),
            markerKey = key,
        )
    }

    /** Mark this installation only; never sign out or clear the listener's existing profile. */
    fun complete(launch: Launch) = complete(DesktopPersistence().preferences, launch)

    internal fun complete(preferences: Preferences, launch: Launch) {
        preferences.putBoolean(launch.markerKey, true)
        DesktopPreferenceFlush.request(preferences)
    }

    internal fun forceRequested(value: String?): Boolean = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "1", "true", "yes" -> true
        else -> false
    }

    /** Preference keys have a short maximum length; never persist the user's install path. */
    internal fun markerKey(identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return MARKER_PREFIX + digest.take(40)
    }

    private fun installationIdentity(): String = installationIdentity(
        // AppImage exports its original file path via APPIMAGE. The jpackage launcher lives
        // under a temporary /tmp/.mount_* or extracted AppDir that changes on every start.
        // Using that transient path creates a new first-run marker on every launch.
        appImagePath = if (DesktopPlatform.isLinux) System.getenv("APPIMAGE") else null,
        launcherPath = System.getProperty("jpackage.app-path"),
        processCommand = runCatching { ProcessHandle.current().info().command().orElse(null) }.getOrNull(),
        javaHome = System.getProperty("java.home"),
    )

    /** Resolve the stable host launcher before hashing it; injectable for launch-path tests. */
    internal fun installationIdentity(
        appImagePath: String?,
        launcherPath: String?,
        processCommand: String?,
        javaHome: String?,
    ): String {
        // The AppImage file wins over the ephemeral executable inside its mount. Other
        // packages keep the existing jpackage/command/runtime fallback behaviour.
        val launcher = sequenceOf(appImagePath, launcherPath, processCommand, javaHome)
            .filterNotNull().firstOrNull(String::isNotBlank)
            ?: "unknown-launcher"
        return runCatching { fileIdentity(Path.of(launcher)) }
            .getOrDefault(launcher)
    }

    /** Creation time detects reinstalls at the same path, even if a copy retains its mtime. */
    internal fun fileIdentity(path: Path): String {
        val normalized = path.toAbsolutePath().normalize().toString().let { value ->
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                value.lowercase(Locale.ROOT)
            } else value
        }
        val attrs = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }
            .getOrNull()
        return listOf(
            normalized,
            attrs?.size() ?: -1L,
            attrs?.creationTime()?.toMillis() ?: -1L,
            attrs?.lastModifiedTime()?.toMillis() ?: -1L,
        ).joinToString("|")
    }
}
