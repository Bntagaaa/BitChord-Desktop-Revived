package com.music.bitchord.desktop

import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSettingsResetTest {
    private val createdNodes = mutableListOf<Preferences>()

    private fun newNode(suffix: String): Preferences {
        val node = Preferences.userRoot().node("com.music.bitchord.desktop.test.${UUID.randomUUID()}.$suffix")
        createdNodes += node
        return node
    }

    @AfterTest
    fun tearDown() {
        for (node in createdNodes) {
            runCatching { node.removeNode() }
        }
        createdNodes.clear()
    }

    @Test
    fun `resetSettings clears known and unknown settings while keeping user data`() {
        val main = newNode("main")
        val pip = newNode("pip")

        // Settings & unknown fork keys (should be removed)
        main.put("desktop_theme_mode", "DARK")
        main.putInt("desktop_ui_scale_percent", 125)
        main.putBoolean("hide_volume_bar", true)
        main.put("legacy_fork_setting_xyz", "conflict")
        main.put("source_configs.0", "chunk0")
        pip.putInt("pip_width", 420)

        // Preserved user data (should be kept)
        main.put("google_accounts_v1.0", "account-chunk-0")
        main.put("google_accounts_v1.1", "account-chunk-1")
        main.put("active_google_account_v1", "acc-1")
        main.put("google_cookie_acc-1", "cookie-data")
        main.put("playlists.0", "playlist-data")
        main.put("playlist_downloads.0", "pl-dl-data")
        main.put("liked_ids", "song-1")
        main.put("history.0", "hist-1")
        main.putBoolean("first_run_install_v2_abc123", true)

        DesktopSettingsReset.resetSettings(preferences = main, pipPreferences = pip)

        val remainingMain = main.keys().toSet()
        assertFalse("desktop_theme_mode" in remainingMain)
        assertFalse("desktop_ui_scale_percent" in remainingMain)
        assertFalse("hide_volume_bar" in remainingMain)
        assertFalse("legacy_fork_setting_xyz" in remainingMain)
        assertFalse("source_configs.0" in remainingMain)
        assertTrue(pip.keys().isEmpty())

        assertEquals("account-chunk-0", main.get("google_accounts_v1.0", null))
        assertEquals("account-chunk-1", main.get("google_accounts_v1.1", null))
        assertEquals("acc-1", main.get("active_google_account_v1", null))
        assertEquals("cookie-data", main.get("google_cookie_acc-1", null))
        assertEquals("playlist-data", main.get("playlists.0", null))
        assertEquals("pl-dl-data", main.get("playlist_downloads.0", null))
        assertEquals("song-1", main.get("liked_ids", null))
        assertEquals("hist-1", main.get("history.0", null))
        assertTrue(main.getBoolean("first_run_install_v2_abc123", false))
    }

    @Test
    fun `resetAllAppData clears all preference nodes and invokes cache clear`() {
        val main = newNode("main")
        val pip = newNode("pip")
        val stats = newNode("stats")

        main.put("google_accounts_v1.0", "account-chunk-0")
        main.put("desktop_theme_mode", "DARK")
        pip.putInt("pip_width", 420)
        stats.putLong("total_plays", 99L)

        var cachesCleared = false
        DesktopSettingsReset.resetAllAppData(
            preferences = main,
            pipPreferences = pip,
            statsPreferences = stats,
            clearCaches = { cachesCleared = true },
        )

        assertTrue(main.keys().isEmpty())
        assertTrue(pip.keys().isEmpty())
        assertTrue(stats.keys().isEmpty())
        assertTrue(cachesCleared)
    }

    @Test
    fun `resolveRelaunchCommand prefers APPIMAGE then jpackage then process fallback`() {
        val tempAppImage = Files.createTempFile("bitchord-test", ".AppImage")
        val tempJpackage = Files.createTempFile("bitchord-test", ".exe")
        try {
            assertEquals(
                listOf(tempAppImage.toAbsolutePath().normalize().toString()),
                DesktopSettingsReset.resolveRelaunchCommand(
                    appImagePath = tempAppImage.toString(),
                    jpackageAppPath = tempJpackage.toString(),
                    processCommand = "/usr/bin/java",
                    processArguments = listOf("-cp", "app.jar", "MainKt"),
                ),
            )
            assertEquals(
                listOf(tempJpackage.toAbsolutePath().normalize().toString()),
                DesktopSettingsReset.resolveRelaunchCommand(
                    appImagePath = null,
                    jpackageAppPath = tempJpackage.toString(),
                    processCommand = "/usr/bin/java",
                    processArguments = listOf("-cp", "app.jar", "MainKt"),
                ),
            )
            assertEquals(
                listOf("/usr/bin/java", "-cp", "app.jar", "MainKt"),
                DesktopSettingsReset.resolveRelaunchCommand(
                    appImagePath = null,
                    jpackageAppPath = null,
                    processCommand = "/usr/bin/java",
                    processArguments = listOf("-cp", "app.jar", "MainKt"),
                ),
            )
            assertNull(
                DesktopSettingsReset.resolveRelaunchCommand(
                    appImagePath = null,
                    jpackageAppPath = null,
                    processCommand = null,
                    processArguments = emptyList(),
                ),
            )
        } finally {
            Files.deleteIfExists(tempAppImage)
            Files.deleteIfExists(tempJpackage)
        }
    }
}
