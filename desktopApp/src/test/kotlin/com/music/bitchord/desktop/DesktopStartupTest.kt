package com.music.bitchord.desktop

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import java.nio.file.Files
import java.util.Base64
import java.util.prefs.Preferences
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class DesktopStartupTest {
    @Test fun `startup waits for both the native window and the real shell draw`() = runBlocking {
        val startup = DesktopStartup()
        val interactive = async { startup.awaitInteractive() }

        startup.nativeWindowShowing()
        assertFalse(interactive.isCompleted)
        startup.shellDrawn()
        withTimeout(3_000) { interactive.await() }
    }

    @Test fun `startup and concurrent requests share one lazy initialization`() = runBlocking {
        val calls = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val task = DesktopStartupTask(this) {
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            "restored"
        }
        assertEquals(0, calls.get())
        val waiting = List(16) { async { task.await() } }
        withTimeout(3_000) { entered.await() }
        task.start()
        assertEquals(1, calls.get())
        assertTrue(waiting.none { it.isCompleted })
        release.complete(Unit)
        assertEquals(List(16) { "restored" }, waiting.awaitAll())
    }

    @Test fun `closing the startup owner cancels an in flight initialization`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val task = DesktopStartupTask(owner) {
            entered.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        task.start()
        withTimeout(3_000) { entered.await() }
        owner.cancel()
        assertFailsWith<CancellationException> { withTimeout(3_000) { task.await() } }
        Unit
    }

    @Test fun `early transport actions run in order after the saved offline queue is restored`() = runBlocking {
        val preferences = Preferences.userRoot().node("bitchord-test-startup-${System.nanoTime()}")
        fun storedSong(id: String) = List(10) { if (it == 0) id else "" }.joinToString("|") {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray())
        }
        DesktopPreferenceChunks.write(preferences, "queue", listOf(storedSong("one"), storedSong("two")).joinToString("\n"))
        preferences.put("queue_index", "1")
        val playback = DesktopPlaybackService(DesktopPersistence(preferences))
        try {
            val commands = mutableListOf<String>()
            withContext(Dispatchers.Swing) {
                assertTrue(playback.deferUntilRestored { commands += "play ${playback.liveQueue.value.current?.videoId}" })
                assertTrue(playback.deferUntilRestored { commands += "pause" })
                assertTrue(commands.isEmpty())
            }
            withTimeout(3_000) { playback.awaitRestored() }
            assertEquals(listOf("play two", "pause"), commands)
            assertEquals(listOf("one", "two"), playback.liveQueue.value.songs.map { it.videoId })
        } finally {
            playback.close()
            preferences.removeNode()
        }
    }

    @Test fun `a failed optional task leaves sibling work usable`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val broken = DesktopStartupTask(owner) { error("Unavailable") }
            val working = DesktopStartupTask(owner) { "ready" }
            assertFailsWith<IllegalStateException> { broken.await() }
            assertEquals("ready", working.await())
        } finally {
            owner.cancel()
        }
    }

    @Test fun `Home cache isolates guest account and profile and survives corruption`() {
        val directory = createTempDirectory("bit-chord-home-cache")
        val path = directory.resolve("home.json")
        try {
            val cache = DesktopHomeCache(path)
            val identity = DesktopHomeCache.Identity("test-account", "test-profile")
            val guest = DesktopHomeCache.Identity(null, null)
            val otherProfile = identity.copy(profileId = "other")
            val otherAccount = identity.copy(accountId = "other")
            val shelf = HomeShelf("Recently Played", listOf(ShelfItem("Track", "Artist", null, "abc", null)))

            cache.write(identity, listOf(shelf))
            assertEquals(listOf(shelf), cache.read(identity))
            assertTrue(cache.read(guest).isEmpty())
            assertTrue(cache.read(otherAccount).isEmpty())
            assertTrue(cache.read(otherProfile).isEmpty())

            Files.writeString(path, "{corrupt")
            assertTrue(cache.read(identity).isEmpty())
            cache.write(guest, listOf(shelf))
            assertEquals(listOf(shelf), cache.read(guest))
            assertTrue(cache.read(identity).isEmpty())
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun `Home cache only accepts a page from its expected authenticated identity`() {
        val account = DesktopHomeCache.Identity("account", "personal")
        val session = DesktopYouTubeAuth.Session(cookie = "fixture", dataSyncId = "account")
        assertTrue(account.matchesSession(session, null))
        assertFalse(account.matchesSession(session.copy(dataSyncId = "another-account"), null))
        assertFalse(account.matchesSession(session.copy(pageId = "another-channel"), null))
        val brand = DesktopYouTubeProfile(profileId = "brand", name = "Brand", pageId = "brand-page")
        assertTrue(account.copy(profileId = "brand").matchesSession(session.copy(pageId = "brand-page"), brand))
        assertFalse(account.copy(profileId = "brand").matchesSession(session, brand))
    }
}
