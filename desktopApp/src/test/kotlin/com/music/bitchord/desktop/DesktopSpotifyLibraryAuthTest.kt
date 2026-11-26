package com.music.bitchord.desktop

import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSpotifyLibraryAuthTest {

    private val defaultChromiumHarvester = DesktopSpotifyLibraryAuth.chromiumHarvester

    @AfterTest
    fun restoreProviders() {
        DesktopSpotifyLibraryAuth.fxAvailableProvider = { DesktopPoTokenWebView.available }
        DesktopSpotifyLibraryAuth.fxStartedProvider = { DesktopPoTokenWebView.isStarted }
        DesktopSpotifyLibraryAuth.chromiumAvailableProvider = { DesktopSpotifyChromiumToken.isAvailable() }
        DesktopSpotifyLibraryAuth.webEngineHarvester = { DesktopSpotifyLibraryAuth.harvestViaWebEngine(it) }
        DesktopSpotifyLibraryAuth.chromiumHarvester = defaultChromiumHarvester
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

    @Test
    fun harvestTokenPrefersChromiumWhenFxNotStartedAndChromiumAvailable() = runBlocking {
        val order = mutableListOf<String>()
        val expected = DesktopSpotifyLibraryAuth.HarvestedToken("tok-chr", Long.MAX_VALUE, null)
        DesktopSpotifyLibraryAuth.fxStartedProvider = { false }
        DesktopSpotifyLibraryAuth.chromiumAvailableProvider = { true }
        DesktopSpotifyLibraryAuth.chromiumHarvester = {
            order += "chromium"
            expected
        }
        DesktopSpotifyLibraryAuth.webEngineHarvester = {
            order += "webengine"
            null
        }

        val actual = DesktopSpotifyLibraryAuth.harvestToken("dummy-sp-dc")

        assertEquals(expected, actual)
        assertEquals(listOf("chromium"), order)
    }

    @Test
    fun harvestTokenFallsBackToWebEngineWhenChromiumFails() = runBlocking {
        val order = mutableListOf<String>()
        val expected = DesktopSpotifyLibraryAuth.HarvestedToken("tok-fx", Long.MAX_VALUE, null)
        DesktopSpotifyLibraryAuth.fxStartedProvider = { false }
        DesktopSpotifyLibraryAuth.chromiumAvailableProvider = { true }
        DesktopSpotifyLibraryAuth.chromiumHarvester = {
            order += "chromium"
            null
        }
        DesktopSpotifyLibraryAuth.webEngineHarvester = {
            order += "webengine"
            expected
        }

        val actual = DesktopSpotifyLibraryAuth.harvestToken("dummy-sp-dc")

        assertEquals(expected, actual)
        assertEquals(listOf("chromium", "webengine"), order)
    }

    @Test
    fun harvestTokenPrefersWebEngineWhenFxAlreadyStarted() = runBlocking {
        val order = mutableListOf<String>()
        val expected = DesktopSpotifyLibraryAuth.HarvestedToken("tok-fx", Long.MAX_VALUE, null)
        DesktopSpotifyLibraryAuth.fxStartedProvider = { true }
        DesktopSpotifyLibraryAuth.chromiumAvailableProvider = { true }
        DesktopSpotifyLibraryAuth.webEngineHarvester = {
            order += "webengine"
            expected
        }
        DesktopSpotifyLibraryAuth.chromiumHarvester = {
            order += "chromium"
            null
        }

        val actual = DesktopSpotifyLibraryAuth.harvestToken("dummy-sp-dc")

        assertEquals(expected, actual)
        assertEquals(listOf("webengine"), order)
    }
}
