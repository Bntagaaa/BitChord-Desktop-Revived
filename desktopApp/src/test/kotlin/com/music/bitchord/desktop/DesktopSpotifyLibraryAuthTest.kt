package com.music.bitchord.desktop

import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSpotifyLibraryAuthTest {

    @AfterTest
    fun restoreFxProvider() {
        DesktopSpotifyLibraryAuth.fxAvailableProvider = { DesktopPoTokenWebView.available }
    }

    @Test
    fun harvestViaWebEngineChecksFxAvailabilityBeforePlatformRunLater() = runBlocking {
        val checked = AtomicBoolean(false)
        DesktopSpotifyLibraryAuth.fxAvailableProvider = {
            checked.set(true)
            false
        }

        val result = DesktopSpotifyLibraryAuth.harvestViaWebEngine("dummy-sp-dc")

        assertTrue(checked.get())
        assertNull(result)
    }
}
