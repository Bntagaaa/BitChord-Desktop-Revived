package com.music.bitchord.reworked

import com.music.bitchord.data.listentogether.JamInvite
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.PartyTrack
import com.music.bitchord.data.listentogether.ServerUrlValidationResult
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileIntegrationsTest {
    private fun song(entry: String = "entry-1") = Song("video-1", "Song", "Artist", null, "4:00", queueEntryId = entry)

    @Test fun pausedAndBufferingTimeNeverBecomeListeningTime() {
        val ledger = ListeningLedger()
        val active = PlaybackState(song = song(), isPlaying = true, durationMs = 240_000)
        assertEquals(0L, ledger.sample(active, 1_000, 50_000)!!.listen.playedMs)
        assertEquals(1_000L, ledger.sample(active.copy(positionMs = 1_000), 2_000, 51_000)!!.listen.playedMs)
        ledger.sample(active.copy(isPlaying = false), 2_100, 51_100)
        ledger.sample(active.copy(isPlaying = false), 3_602_100, 3_651_100)
        ledger.sample(active.copy(isLoading = true), 3_603_100, 3_652_100)
        val resumed = ledger.sample(active, 3_604_100, 3_653_100)!!
        assertEquals(1_000L, resumed.listen.playedMs)
        assertTrue(resumed.becameAudible)
        assertEquals(2_000L, ledger.sample(active, 3_605_100, 3_654_100)!!.listen.playedMs)
    }

    @Test fun seekingDoesNotCountTheSkippedPositionAsListening() {
        val ledger = ListeningLedger()
        val active = PlaybackState(song = song(), isPlaying = true, durationMs = 240_000)
        ledger.sample(active, 1_000, 1_000)
        val sample = ledger.sample(active.copy(positionMs = 180_000, discontinuity = 1), 2_000, 2_000)!!
        assertEquals(1_000L, sample.elapsedMs)
        assertEquals(1_000L, sample.listen.playedMs)
        assertFalse(sample.becameAudible)
    }

    @Test fun duplicateQueueEntriesAndFinishedRepeatStartSeparateListens() {
        val ledger = ListeningLedger()
        val state = PlaybackState(song = song(), isPlaying = true, durationMs = 240_000)
        ledger.sample(state, 1_000, 1_000)
        val first = ledger.sample(state.copy(positionMs = 239_800), 2_000, 2_000)!!
        first.listen.lastFmSubmitted = true
        val loop = ledger.sample(state.copy(positionMs = 0, discontinuity = 1), 3_000, 3_000)!!
        assertEquals(0L, loop.listen.playedMs)
        assertFalse(loop.listen.lastFmSubmitted)
        val duplicate = ledger.sample(state.copy(song = song("entry-2")), 4_000, 4_000)!!
        assertEquals("entry-2", duplicate.listen.identity)
        assertEquals(0L, duplicate.listen.playedMs)
    }

    @Test fun aStalledSamplerContributesOnlyTheBoundedInterval() {
        val ledger = ListeningLedger()
        val state = PlaybackState(song = song(), isPlaying = true)
        ledger.sample(state, 10, 10)
        assertEquals(2_000L, ledger.sample(state, 100_000, 100_000)!!.elapsedMs)
    }

    @Test fun partyQueuePreservesDistinctDuplicateIdentitiesAcrossReordering() {
        val initial = reconcilePartyRows(emptyList(), listOf(PartyTrack("a"), PartyTrack("b"), PartyTrack("a")))
        assertNotEquals(initial[0].queueEntryId, initial[2].queueEntryId)
        val reordered = reconcilePartyRows(initial, listOf(PartyTrack("b"), PartyTrack("a"), PartyTrack("a"), PartyTrack("c")))
        assertEquals(initial[1].queueEntryId, reordered[0].queueEntryId)
        assertEquals(initial[0].queueEntryId, reordered[1].queueEntryId)
        assertEquals(initial[2].queueEntryId, reordered[2].queueEntryId)
        assertTrue(reordered[3].queueEntryId!!.startsWith("party:"))
    }

    @Test fun partyInvitesAcceptOnlyTheMobilePublicOriginAndSixCharacterCodes() {
        assertEquals("ABC123", parsePartyCode(" abc123 "))
        assertEquals("ABC123", parsePartyCode(JamInvite.url("ABC123")))
        assertNull(parsePartyCode("https://unrelated.example/invite/ABC123"))
        assertNull(parsePartyCode("ABC1234"))
        assertNull(parsePartyCode("ABC 23"))
    }

    @Test fun mobileServerValidationRejectsNonHttpAndNeverForwardsUserInfo() {
        assertIs<ServerUrlValidationResult.Invalid>(ListenTogether.parseAndNormalizeServerUrl("file:///tmp/party"))
        val withUserInfo = assertIs<ServerUrlValidationResult.Valid>(ListenTogether.parseAndNormalizeServerUrl("https://user:password@example.org"))
        assertEquals("https://example.org", withUserInfo.normalizedUrl)
        assertIs<ServerUrlValidationResult.Invalid>(ListenTogether.parseAndNormalizeServerUrl("https://example.org?token=secret"))
        val result = assertIs<ServerUrlValidationResult.Valid>(ListenTogether.parseAndNormalizeServerUrl("example.org"))
        assertEquals("https://example.org", result.normalizedUrl)
    }
}
