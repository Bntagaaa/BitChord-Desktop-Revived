package com.music.bitchord.reworked

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPlaybackTest {
    @Test fun manualVersionSwapPreservesPausedQueueOccurrenceAndBypassesMusicOnly() = runBlocking {
        val policyCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val first = CompletableDeferred<Unit>()
        val target = CompletableDeferred<Unit>()
        DesktopPlayback(prepareSong = { policyCalls += it.videoId; it }, resolve = {
            if (it.videoId == "album") first.complete(Unit) else target.complete(Unit)
            awaitCancellation()
        }).use { player ->
            player.play(song("album"))
            withTimeout(2_000) { first.await() }
            player.pause()
            val occurrence = player.state.value.song?.queueEntryId
            assertTrue(player.swapToVersion(song("video"), 42_000, occurrence))
            withTimeout(2_000) { target.await() }
            assertEquals(occurrence, player.state.value.song?.queueEntryId)
            assertEquals("video", player.state.value.song?.videoId)
            assertEquals(42_000L, player.state.value.positionMs)
            assertFalse(player.state.value.isPlaying)
            assertEquals(listOf("album"), policyCalls)
            assertFalse(player.swapToVersion(song("stale"), 0, "a-different-occurrence"))
            assertEquals("video", player.state.value.song?.videoId)
        }
    }

    private fun song(id: String) = Song(id, id, "Test artist", null, "3:00")
    private fun waitingPlayer() = DesktopPlayback(resolve = { awaitCancellation() })

    @Test fun repeatedSongsHaveIndependentQueueIdentity() {
        waitingPlayer().use { player ->
            player.playSongs(listOf(song("same"), song("same"), song("end")))
            val entries = player.state.value.queue
            assertEquals(3, entries.map { it.queueEntryId }.distinct().size)
            player.remove(1)
            assertEquals(listOf("same", "end"), player.state.value.queue.map { it.videoId })
            assertEquals(entries.first().queueEntryId, player.state.value.song?.queueEntryId)
        }
    }

    @Test fun reorderingAndRemovingEarlierRowsPreservesPlayingEntry() {
        waitingPlayer().use { player ->
            player.playSongs(listOf(song("a"), song("b"), song("c")), 1)
            val selected = player.state.value.song
            val discontinuity = player.state.value.discontinuity
            player.move(1, 2)
            assertEquals(selected, player.state.value.song)
            assertEquals(2, player.state.value.queueIndex)
            assertEquals(discontinuity, player.state.value.discontinuity)
            player.remove(0)
            assertEquals(selected, player.state.value.song)
            assertEquals(1, player.state.value.queueIndex)
        }
    }

    @Test fun queueBoundsAndRepeatAll() {
        waitingPlayer().use { player ->
            player.playSongs(listOf(song("a"), song("b")), 99)
            assertEquals(1, player.state.value.queueIndex)
            player.next()
            assertFalse(player.state.value.isPlaying)
            assertFalse(player.state.value.isLoading)
            player.cycleRepeat()
            player.cycleRepeat()
            player.next()
            assertEquals("a", player.state.value.song?.videoId)
            assertTrue(player.state.value.isPlaying)
            player.previous()
            assertEquals("b", player.state.value.song?.videoId)
        }
    }

    @Test fun seekAndClearKeepControlsButDropTheTimeline() {
        waitingPlayer().use { player ->
            player.setSpeed(1.5f)
            player.setVolume(0.3f)
            player.play(song("a"))
            player.seek(999_999)
            assertEquals(180_000, player.state.value.positionMs)
            player.clearQueue()
            assertNull(player.state.value.song)
            assertTrue(player.state.value.queue.isEmpty())
            assertEquals(1.5f, player.state.value.speed)
            assertEquals(0.3f, player.state.value.volume)
        }
    }

    @Test fun supersededResolutionCannotStartAnObsoleteSong() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        DesktopPlayback(resolve = {
            entered.complete(Unit)
            withContext(NonCancellable) {
                release.await()
                finished.complete(Unit)
                PlayableStream("/this-obsolete-path-must-never-be-decoded")
            }
        }).use { player ->
            player.play(song("a"))
            withTimeout(2_000) { entered.await() }
            player.next()
            release.complete(Unit)
            withTimeout(2_000) { finished.await() }
            delay(50)
            assertFalse(player.state.value.isPlaying)
            assertFalse(player.state.value.isLoading)
            assertNull(player.state.value.error)
            assertNull(player.state.value.format)
        }
    }

    @Test fun audioClockTracksHeardFramesAcrossSpeedChangesAndSeeks() {
        val clock = AudioDeviceClock(48_000.0)
        clock.reset(0, 1_000.0)
        clock.append(0, 1_000.0, 1.0)
        clock.append(48_000, 2_000.0, 2.0)
        assertEquals(1_500.0, clock.position(24_000), 0.001)
        assertEquals(3_000.0, clock.position(72_000), 0.001)
        clock.reset(72_000, 30_000.0)
        clock.append(72_000, 30_000.0, 0.5)
        assertEquals(30_500.0, clock.position(120_000), 0.001)
    }

    @Test fun restoredQueueWaitsForResumeAndPartyReorderKeepsPosition() {
        waitingPlayer().use { player ->
            val first = song("a").copy(queueEntryId = "server-a")
            val second = song("b").copy(queueEntryId = "server-b")
            player.restoreQueue(listOf(first, second), 0, 42_000)
            assertFalse(player.state.value.isPlaying)
            assertFalse(player.state.value.isLoading)
            player.withRemoteCommands { player.replaceQueue(listOf(second, first), 1) }
            assertEquals("server-a", player.state.value.song?.queueEntryId)
            assertEquals(42_000, player.state.value.positionMs)
            player.resume()
            assertTrue(player.state.value.isLoading)
            assertEquals(42_000, player.state.value.positionMs)
        }
    }

    @Test fun partyInterceptionRequiresAuthoritativeReplyAndCloseStillCloses() {
        val player = waitingPlayer()
        val commands = ArrayList<PlaybackCommand>()
        player.commandInterceptor = { commands += it; true }
        player.play(song("a"))
        assertNull(player.state.value.song)
        player.withRemoteCommands { player.playSongs(listOf(song("a").copy(queueEntryId = "remote-a"))) }
        assertEquals("remote-a", player.state.value.song?.queueEntryId)
        player.pause()
        assertTrue(player.state.value.isPlaying)
        player.withRemoteCommands { player.pause() }
        assertFalse(player.state.value.isPlaying)
        assertEquals(2, commands.size)
        player.close()
        assertTrue(player.state.value.queue.isEmpty())
    }
}
