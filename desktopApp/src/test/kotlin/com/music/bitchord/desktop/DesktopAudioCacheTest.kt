package com.music.bitchord.desktop

import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopAudioCacheTest {
    private val directory = Files.createTempDirectory("bitchord-range-cache-")
    @AfterTest fun cleanup() { directory.toFile().deleteRecursively() }
    private fun key(value: String) = DesktopAudioCache.identity(value)

    @Test fun `ranges survive restart and separate sources and renditions`() {
        val cache = DesktopAudioCache(directory)
        cache.write(key("song|youtube|opus|url"), 524288, byteArrayOf(1, 2, 3), 600000, Long.MAX_VALUE)
        val reopened = DesktopAudioCache(directory)
        assertContentEquals(byteArrayOf(1, 2, 3), reopened.read(key("song|youtube|opus|url"), 524288)?.bytes)
        assertNull(reopened.read(key("song|youtube|aac|url"), 524288))
        assertNull(reopened.read(key("song|addon|opus|url"), 524288))
        assertNull(reopened.read(key("song|youtube|opus|new-url"), 524288))
        assertNull(reopened.read(key("song|youtube|opus|url"), 0))
    }

    @Test fun `expired or corrupted spans never reach the decoder`() {
        var now = 1000L
        val cache = DesktopAudioCache(directory, now = { now })
        cache.write(key("expiring"), 0, byteArrayOf(42), 1, 1001)
        now = 1002
        assertNull(cache.read(key("expiring"), 0))
        cache.write(key("damaged"), 0, byteArrayOf(1, 2, 3), 3, 2000)
        val file = Files.list(directory).use { it.filter { f -> f.fileName.toString().startsWith(key("damaged")) }.findFirst().orElseThrow() }
        val bytes = Files.readAllBytes(file); bytes[bytes.lastIndex] = 99; Files.write(file, bytes)
        assertNull(cache.read(key("damaged"), 0))
    }

    @Test fun `LRU eviction stays inside the disk budget including metadata`() {
        var now = 1000L
        val cache = DesktopAudioCache(directory, limitBytes = 140, now = { now++ })
        cache.write(key("old"), 0, ByteArray(8), 8, Long.MAX_VALUE)
        cache.write(key("recent"), 0, ByteArray(8), 8, Long.MAX_VALUE)
        assertNotNull(cache.read(key("old"), 0))
        cache.write(key("new"), 0, ByteArray(8), 8, Long.MAX_VALUE)
        assertNull(cache.read(key("recent"), 0))
        assertNotNull(cache.read(key("old"), 0))
        assertTrue(Files.list(directory).use { it.mapToLong(Files::size).sum() } <= 140)
    }

    @Test fun `concurrent span replacement is atomic and bounded`() {
        val cache = DesktopAudioCache(directory)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 20).map { index -> pool.submit {
                val data = ByteArray(4096) { index.toByte() }
                cache.write(key("shared"), 0, data, data.size.toLong(), Long.MAX_VALUE)
                cache.read(key("shared"), 0)?.bytes?.let { assertEquals(1, it.toSet().size) }
            } }
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertNotNull(cache.read(key("shared"), 0))
    }
    @Test fun `orphaned partial writes count toward the budget on restart`() {
        val orphan = directory.resolve(".span-interrupted.tmp")
        Files.write(orphan, ByteArray(200))
        DesktopAudioCache(directory, limitBytes = 140)
        assertFalse(Files.exists(orphan))
    }

}
