package com.music.bitchord.reworked

import com.music.bitchord.data.Http
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Explicit test entry point: a new empty profile, no accounts, no outbound music clients.
 * It still renders the real application and opens actual local files through the normal decoder.
 * Production launches do not enable this policy.
 */
internal object OfflineSmoke {
    var active = false
        private set
    var profile: Path? = null
        private set
    fun install() {
        check(!active)
        active = true
        profile = Files.createTempDirectory("bitchord-reworked-offline-smoke-")
        System.setProperty("bitchord.preferencesNode", "com.music.bitchord.reworked.smoke." + UUID.randomUUID())
        Http.networkAllowed = false
        DesktopStore.preferences.putBoolean("animated_canvas", false)
        DesktopStore.preferences.putBoolean("autoplay", false)
        DesktopStore.preferences.putBoolean("automix", false)
        DesktopStore.preferences.putBoolean("sync_downloaded_playlists", false)
    }
    fun requireNetwork() { check(!active) { "This action is unavailable in the offline smoke test" } }
}
