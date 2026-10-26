package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesktopPerformance183Test {

    @Test
    fun testStartupDiagnosticsMemorySummary() {
        val summary = DesktopStartupDiagnostics.memorySummary()
        assertNotNull(summary)
        assertTrue(summary.contains("JVM Heap:"))
    }

    @Test
    fun testStartupDiagnosticsMilestoneRecording() {
        DesktopStartupDiagnostics.record(
            DesktopStartupDiagnostics.Milestone.PROCESS_ENTRY,
            "test process entry",
        )
        DesktopStartupDiagnostics.record(
            DesktopStartupDiagnostics.Milestone.HOME_FEED_RENDERED,
            "test first home render",
        )
    }

    @Test
    fun testListeningStatsCachingAndInvalidation() {
        DesktopListeningStats.clear()
        val initialSummary = DesktopListeningStats.summary(DesktopReplayPeriod.ALL_TIME)
        assertTrue(initialSummary.isEmpty)

        // Repeat call should return cached instance
        val cachedSummary = DesktopListeningStats.summary(DesktopReplayPeriod.ALL_TIME)
        assertEquals(initialSummary, cachedSummary)

        // Recording a song should invalidate cache and compute updated summary
        val testSong = Song(
            videoId = "test_vid_1",
            title = "Test Song",
            artist = "Test Artist",
            thumbnailUrl = null,
        )
        DesktopListeningStats.record(testSong, 180_000L, countsAsPlay = true)

        val updatedSummary = DesktopListeningStats.summary(DesktopReplayPeriod.ALL_TIME)
        assertFalse(updatedSummary.isEmpty)
        assertEquals(1, updatedSummary.distinctSongs)

        DesktopListeningStats.clear()
    }

    @Test
    fun testHardwareProfileDetection() {
        val detected = detectHardwareProfile()
        assertNotNull(detected)
        assertTrue(detected in DesktopPerformanceProfile.entries)

        val resolvedAuto = DesktopPerformanceProfile.AUTO.resolved()
        assertEquals(detected, resolvedAuto)
    }

    @Test
    fun testMasterReduceDynamicBlurOverrideInvariant() {
        val profiles = listOf(
            DesktopPerformanceProfile.FULL,
            DesktopPerformanceProfile.BALANCED,
            DesktopPerformanceProfile.PERFORMANCE,
            DesktopPerformanceProfile.AUTO,
        )

        for (profile in profiles) {
            // WHENEVER reduceDynamicBlur is TRUE, effectiveDesktopBlur MUST BE FALSE!
            assertFalse(
                "When reduceDynamicBlur is true, blur MUST be false for profile $profile",
                effectiveDesktopBlur(reduceDynamicBlur = true, selected = true, profile = profile),
            )
            assertFalse(
                "When reduceDynamicBlur is true, blur MUST be false for profile $profile",
                effectiveDesktopBlur(reduceDynamicBlur = true, selected = false, profile = profile),
            )
        }
    }

    @Test
    fun testPerformanceProfileBlurBehavior() {
        // Under PERFORMANCE profile, dynamic blur is disabled even if reduceDynamicBlur is false and selected is true
        assertFalse(
            effectiveDesktopBlur(
                reduceDynamicBlur = false,
                selected = true,
                profile = DesktopPerformanceProfile.PERFORMANCE,
            ),
        )

        // Under FULL profile, when reduceDynamicBlur is false, user selection is respected
        assertTrue(
            effectiveDesktopBlur(
                reduceDynamicBlur = false,
                selected = true,
                profile = DesktopPerformanceProfile.FULL,
            ),
        )
        assertFalse(
            effectiveDesktopBlur(
                reduceDynamicBlur = false,
                selected = false,
                profile = DesktopPerformanceProfile.FULL,
            ),
        )
    }

    @Test
    fun testWindowHiddenMemoryOptimizer() {
        // Calling onWindowHidden should execute safely without exceptions
        DesktopMemoryOptimizer.onWindowHidden()
        assertEquals(0L, DesktopArtworkCache.heldBytes)
    }

    @Test
    fun testDownloadVerificationFiltering() {
        val tempFile = File.createTempFile("bitchord_test_dl_", ".mp3")
        tempFile.writeBytes(ByteArray(100))
        tempFile.deleteOnExit()

        val existingSong = Song(
            videoId = "existing_1",
            title = "Downloaded",
            artist = "Artist",
            thumbnailUrl = null,
            localPath = tempFile.absolutePath,
        )
        val missingSong = Song(
            videoId = "missing_1",
            title = "Missing",
            artist = "Artist",
            thumbnailUrl = null,
            localPath = "/nonexistent/path/song.mp3",
        )

        val verified = DesktopDownloadManager.verified(listOf(existingSong, missingSong))
        assertEquals(1, verified.size)
        assertEquals("existing_1", verified.first().videoId)
    }
}
