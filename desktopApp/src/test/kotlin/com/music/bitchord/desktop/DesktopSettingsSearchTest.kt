package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSettingsSearchTest {
    @Test
    fun `terms can match across the category title and keywords`() {
        val query = DesktopSettingsQuery("  AUDIO   Float  ")
        assertTrue(query.matches("Audio processing", "Output precision", "16-bit PCM, 32-bit float"))
        assertFalse(query.matches("Audio processing", "Skip silence", "Shorten long gaps"))
    }

    @Test
    fun `filtering retains only matching rows and keeps distinct source ids`() {
        val rows = DesktopSettingsRows(DesktopSettingsQuery("source FLAC"), "Music sources")
        rows.row("My server", "FLAC playback", id = "source-a") {}
        rows.row("My server", "FLAC playback", id = "source-b") {}
        rows.row("YouTube Music", "Lossy fallback", id = "youtube") {}
        assertEquals(listOf("source-a", "source-b"), rows.entries.map { it.id })
    }

    @Test
    fun `whitespace restores all rows`() {
        val query = DesktopSettingsQuery("  \t\n")
        assertTrue(query.matches("Playback", "Crossfade"))
    }
}
