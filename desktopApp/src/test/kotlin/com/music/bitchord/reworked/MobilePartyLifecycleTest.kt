package com.music.bitchord.reworked

import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MobilePartyLifecycleTest {
    @Test fun closingAPartyRestoresThePersonalQueuePausedWithoutResolvingMedia() = runTest {
        val resolutions = AtomicInteger()
        val player = DesktopPlayback(resolve = {
            resolutions.incrementAndGet()
            error("This lifecycle must never resolve media")
        })
        val personal = listOf(
            Song("abcdefghijk", "Own first", "Artist", null, "4:00", queueEntryId = "own-first"),
            Song("abcdefghijk", "Own duplicate", "Artist", null, "4:00", queueEntryId = "own-duplicate"),
        )
        player.restoreQueue(personal, 1, 42_000)
        player.setSpeed(1.25f)
        player.setRepeat(2)
        player.toggleShuffle()
        val room = MutableStateFlow(ListenTogether.State())
        val bridge = MobilePartySync(player, this, {}, room, {})
        try {
            runCurrent()
            room.value = ListenTogether.State(code = "ABCDEF", connection = ListenTogether.Connection.CONNECTING)
            runCurrent()
            assertEquals(0, player.state.value.repeat, "The mobile party protocol has one shared running order")
            assertFalse(player.state.value.shuffle, "The shared order is also reflected in the shuffle control")
            player.withRemoteCommands {
                player.restoreQueue(listOf(Song("zyxwvutsrqp", "Party song", "Other", null, "4:00", queueEntryId = "party-entry")), 0, 12_000)
            }
            bridge.close()
            runCurrent()
            val restored = player.state.value
            assertEquals(listOf("own-first", "own-duplicate"), restored.queue.map { it.queueEntryId })
            assertEquals(1, restored.queueIndex)
            assertEquals(42_000L, restored.positionMs)
            assertEquals(1.25f, restored.speed)
            assertEquals(2, restored.repeat)
            assertTrue(restored.shuffle)
            assertFalse(restored.isPlaying)
            assertFalse(restored.isLoading)
            assertEquals(0, resolutions.get())
            assertNull(player.commandInterceptor)
        } finally { bridge.close(); player.close() }
    }
}
