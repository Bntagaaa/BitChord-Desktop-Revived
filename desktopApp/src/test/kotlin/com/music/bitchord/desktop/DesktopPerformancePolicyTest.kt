package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow

class DesktopPerformancePolicyTest {
    @Test fun `local scan keeps formats sorting and deduplicates overlapping roots`() = runBlocking {
        val folder = java.nio.file.Files.createTempDirectory("lazy-local-music")
        try {
            java.nio.file.Files.write(folder.resolve("Artist - Zulu.mp3"), byteArrayOf(1))
            java.nio.file.Files.write(folder.resolve("Artist - Alpha.flac"), byteArrayOf(1))
            java.nio.file.Files.write(folder.resolve("notes.txt"), byteArrayOf(1))
            val songs = DesktopLocalMusic.scan(listOf(folder, folder), false)
            assertEquals(listOf("Alpha", "Zulu"), songs.map { it.title })
            assertTrue(songs.all { it.artist == "Artist" })
            assertTrue(DesktopLocalMusic.scan(listOf(folder), true).isEmpty())
        } finally { folder.toFile().deleteRecursively() }
    }

    @Test fun `initial scan waits for watcher registration and cancellation closes registration`() = runBlocking {
        val changes = MutableSharedFlow<Unit>()
        val registered = CompletableDeferred<Unit>()
        var scans = 0
        var stopped = false
        val session = DesktopLocalMusicSession(changes, { registered.await() }, { stopped = true }) { ++scans }
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { session.observe(0) {} }
        assertEquals(0, scans)
        observer.cancelAndJoin()
        assertTrue(stopped)
        assertEquals(0, scans)
        registered.complete(Unit)
        val next = launch(start = CoroutineStart.UNDISPATCHED) { session.observe(0) {} }
        yield()
        assertEquals(1, scans)
        next.cancelAndJoin()
    }

    @Test fun `master blur override wins across every profile area and preference`() {
        for (profile in DesktopGraphicsProfile.entries) for (area in DesktopBlurArea.entries) {
            for (selected in listOf(true, false)) {
                assertFalse(effectiveDesktopBlur(true, selected, profile, area))
            }
            assertEquals(profileAllowsBlur(profile, area), effectiveDesktopBlur(false, true, profile, area))
            assertFalse(effectiveDesktopBlur(false, false, profile, area))
        }
    }

    @Test fun `upgrade preserves existing visual preferences and unknown hardware uses conservative default`() {
        assertEquals(DesktopGraphicsProfile.FULL, initialGraphicsProfile(true, 1, 1))
        assertEquals(DesktopGraphicsProfile.BALANCED, initialGraphicsProfile(false, 0, null))
        assertEquals(DesktopGraphicsProfile.PERFORMANCE, initialGraphicsProfile(false, 2, null))
        assertEquals(DesktopGraphicsProfile.PERFORMANCE, initialGraphicsProfile(false, 8, 4L * 1024 * 1024 * 1024))
        assertEquals(1f, profileBlurScale(DesktopGraphicsProfile.FULL))
    }

    @Test fun `Replay caches by period and invalidates on revision or calendar change`() {
        val cache = DesktopRevisionCache<String, Int>(3)
        var loads = 0
        fun read(period: String, revision: String) = cache.get(period, revision) { ++loads }
        assertEquals(1, read("month", "1/day1"))
        assertEquals(1, read("month", "1/day1"))
        assertEquals(2, read("year", "1/day1"))
        assertEquals(3, read("month", "2/day1"))
        assertEquals(4, read("month", "2/day2"))
    }

    @Test fun `download verification does not discard a concurrent replacement or new download`() {
        val old = Song(videoId = "old", title = "old", artist = "a", thumbnailUrl = null, fromAutoplay = false, localPath = "/missing")
        val replacement = old.copy(localPath = "/new")
        val added = old.copy(videoId = "added")
        assertEquals(listOf(replacement, added), mergeVerifiedDownloads(listOf(old, replacement, added), listOf(old), emptyList()))
        assertEquals(listOf(old), mergeVerifiedDownloads(listOf(old), listOf(old), listOf(old)))
    }

    @Test fun `Local Music is lazy caches visits invalidates revisions and closes its watcher`() = runBlocking {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var scans = 0
        var starts = 0
        var stops = 0
        val session = DesktopLocalMusicSession(changes, { starts++ }, { stops++ }) { ++scans }
        assertEquals(0, scans)
        var published = 0
        suspend fun visit(revision: Int) {
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { session.observe(revision) { published = it } }
            yield()
            observer.cancelAndJoin()
        }
        visit(0)
        assertEquals(1, published)
        visit(0)
        assertEquals(1, scans)
        visit(1)
        assertEquals(2, scans)
        assertEquals(starts, stops)
    }

    @Test fun `filesystem events serialize scans and cancellation never caches a partial result`() = runBlocking {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        var attempts = 0
        var stopped = false
        val session = DesktopLocalMusicSession(changes, {}, { stopped = true }) {
            attempts++
            active++
            maximum = maxOf(maximum, active)
            try { entered.complete(Unit); release.await(); attempts } finally { active-- }
        }
        val first = launch { session.observe(0) {} }
        entered.await()
        changes.emit(Unit)
        yield()
        first.cancelAndJoin()
        assertTrue(stopped)
        assertEquals(0, active)
        assertEquals(1, maximum)
        release.complete(Unit)
        val second = launch(start = CoroutineStart.UNDISPATCHED) { session.observe(0) {} }
        yield()
        second.cancelAndJoin()
        assertEquals(2, attempts)
    }
}
