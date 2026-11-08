package com.music.bitchord.reworked

import com.music.bitchord.data.model.Song
import com.my.kizzy.rpc.KizzyRPC
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MobileDiscordTest {
    private fun playing() = PlaybackState(song = Song("abcdefghijk", "Song", "Artist", "https://example.test/cover.jpg", "4:00", albumName = "Album"),
        isPlaying = true, positionMs = 60_000, durationMs = 240_000)

    @Test fun timestampPairUsesListeningSpeedAndSeekPosition() {
        val card = discordPresence(playing().copy(speed = 1.5f), MobileDiscordPreferences(), 1_000_000)!!
        assertEquals(960_000L, card.startMs)
        assertEquals(1_120_000L, card.endMs)
        assertEquals("Song [1.50x]", card.details)
        assertEquals(KizzyRPC.Type.LISTENING, card.type)
        val sought = discordPresence(playing().copy(positionMs = 180_000, speed = 2f), MobileDiscordPreferences(), 1_000_000)!!
        assertEquals(910_000L, sought.startMs)
        assertEquals(1_030_000L, sought.endMs)
        assertNull(discordPresence(playing().copy(isPlaying = false), MobileDiscordPreferences(), 0))
        assertNull(discordPresence(playing().copy(isLoading = true), MobileDiscordPreferences(), 0))
    }

    @Test fun localArtworkAndIdentifiersNeverBecomePublicDiscordLinks() {
        val state = playing().copy(song = playing().song!!.copy(videoId = "local:/private/music.wav", thumbnailUrl = "file:///private/art.png", localPath = "/private/music.wav"))
        val card = discordPresence(state, MobileDiscordPreferences(), 0)!!
        assertNull(card.artwork)
        assertNull(card.watchUrl)
        assertEquals(listOf(DISCORD_BUTTON_PROJECT to DISCORD_PROJECT_URL), card.buttons)
        assertNull(discordPresence(state.copy(durationMs = 0), MobileDiscordPreferences(), 0)!!.endMs)
    }

    @Test fun buttonTemplatesAndDiscordFieldLimitsMatchTheMobileControls() {
        val settings = MobileDiscordPreferences(button1Text = "Play {song_name}", button2Text = "{artist_name}: {album_name}",
            activityName = "A".repeat(180), activityType = "watching", useDetails = true, status = "dnd")
        val card = discordPresence(playing(), settings, 0)!!
        assertEquals("Play Song", card.buttons[0].first)
        assertEquals("Artist: Album", card.buttons[1].first)
        assertEquals(128, card.name.length)
        assertEquals(KizzyRPC.Type.WATCHING, card.type)
        assertEquals(KizzyRPC.StatusDisplayType.DETAILS, card.display)
        assertEquals("dnd", card.status)
        val hidden = discordPresence(playing(), settings.copy(button1Visible = false, button2Visible = false), 0)!!
        assertTrue(hidden.buttons.isEmpty())
    }

    @Test fun losslessBadgeRequiresActualDecoderEvidence() {
        assertNull(discordAudioQualityLine(null))
        assertNull(discordAudioQualityLine(DecodedAudioInfo("aac", 96_000, 2, 24, 320_000, false)))
        val measured = DecodedAudioInfo("flac", 96_000, 2, 24, 4_608_000, true)
        assertEquals("Hi-Res Lossless · FLAC · 4608 kbps · 24-bit · 96 kHz · Stereo", discordAudioQualityLine(measured))
        assertNull(discordPresence(playing().copy(audioInfo = measured), MobileDiscordPreferences(showAudioQuality = false), 0)!!.quality)
    }

    @Test fun lifecyclePublishesOnOptInAndSeekButNotClockTicksAndClearsOnPause() = runTest {
        val state = MutableStateFlow(playing())
        val settings = MutableStateFlow(MobileDiscordPreferences(token = "fixture-token-value"))
        val connections = mutableListOf<RecordingGateway>()
        val service = MobileDiscordService(state, settings, StandardTestDispatcher(testScheduler)) {
            RecordingGateway().also(connections::add)
        }
        try {
            runCurrent()
            assertTrue(connections.isEmpty(), "Saving a token must not implicitly publish")
            settings.value = settings.value.copy(enabled = true)
            runCurrent()
            val gateway = connections.single()
            assertEquals(1, gateway.cards.size)
            repeat(20) { index -> state.value = state.value.copy(positionMs = 61_000L + index * 100); runCurrent() }
            assertEquals(1, gateway.cards.size, "Ordinary device-clock updates must not flood the gateway")
            state.value = state.value.copy(positionMs = 120_000, discontinuity = 1)
            advanceTimeBy(751); runCurrent()
            assertEquals(2, gateway.cards.size)
            assertEquals(120_000L, gateway.cards.last().sinceMs - gateway.cards.last().startMs)
            state.value = state.value.copy(isPlaying = false)
            runCurrent()
            assertEquals(1, gateway.clears)
            settings.value = settings.value.copy(enabled = false)
            runCurrent()
            assertTrue(gateway.disposed)
            assertEquals(1, connections.size)
        } finally { service.shutdown() }
    }

    private class RecordingGateway : MobileDiscordGateway {
        override val connected = true
        val cards = mutableListOf<MobileDiscordPresence>()
        var clears = 0
        var disposed = false
        override suspend fun publish(presence: MobileDiscordPresence) { cards += presence }
        override suspend fun clear() { clears++ }
        override fun dispose() { disposed = true }
    }
}
