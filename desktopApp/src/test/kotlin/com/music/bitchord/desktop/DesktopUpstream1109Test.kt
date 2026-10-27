package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.*
import kotlin.test.*

class DesktopUpstream1109Test {
    @Test fun sharedPlayerObservesTheExistingDesktopMasterOverrides() {
        assertSame(DesktopAppearanceSettings.reduceDynamicBlur, DesktopPlayerSettings.reduceDynamicBlur)
        assertSame(DesktopAppearanceSettings.reduceAnimation, DesktopPlayerSettings.reduceAnimation)
    }

    @Test fun customTimerLimitsAndInvalidInput() {
        assertEquals(5, DesktopSleepTimer.customMinutes(" 5 "))
        assertEquals(720, DesktopSleepTimer.customMinutes("720"))
        listOf("4", "721", "-1", "0", "", "5.5", "999999999999").forEach {
            assertNull(DesktopSleepTimer.customMinutes(it), it)
        }
    }
    private fun song(id: String, album: String?, artist: String) = Song(
        videoId = id, title = id, artist = artist, thumbnailUrl = null, albumName = album,
    )
    @Test fun albumIdSurvivesChangesToArtistCredits() {
        val first = song("a", "Album", "Singer").copy(albumId = "MPRE123")
        val second = song("b", "Album", "Singer feat. Guest").copy(albumId = "MPRE123")
        assertEquals(DesktopListeningStats.albumKey(first), DesktopListeningStats.albumKey(second))
        assertNotEquals(DesktopListeningStats.albumKey(first), DesktopListeningStats.albumKey(second.copy(albumId = "MPRE456")))
    }
    @Test fun localAlbumsKeepTheirArtistAndUnknownAlbumsStillShareOneRow() {
        assertNotEquals(DesktopListeningStats.albumKey(song("a", "Greatest Hits", "A")),
            DesktopListeningStats.albumKey(song("b", "Greatest Hits", "B")))
        assertEquals(DesktopListeningStats.albumKey(song("a", null, "A")),
            DesktopListeningStats.albumKey(song("b", null, "B")))
    }
    @Test fun videoFollowsTheAudioClockAndSpeedAndStopsForBuffering() {
        val state = DesktopPlaybackState(positionMs = 1000, positionSampledAtNanos = 1_000_000_000, isPlaying = true)
        assertEquals(1500, videoClockPosition(state, 1f, 1_500_000_000))
        assertEquals(2000, videoClockPosition(state, 2f, 1_500_000_000))
        assertEquals(1000, videoClockPosition(state.copy(isPlaying = false), 1f, 1_500_000_000))
        assertEquals(1000, videoClockPosition(state.copy(awaitingAudio = true), 1f, 1_500_000_000))
        assertEquals(1000, videoClockPosition(state, 1f, 500_000_000))
    }
    @Test fun videoFrameDrawCanOutliveItsNativeImageWrapper() {
        val bytes = ByteArray(8 * 8 * 4) { if (it % 4 >= 2) 255.toByte() else 0 }
        val frame = VideoFrame(8, 8, bytes)
        org.jetbrains.skia.PictureRecorder().use { recorder ->
            val target = recorder.beginRecording(org.jetbrains.skia.Rect.makeWH(8f, 8f))
            // Painter drawing occurs inside Compose; the underlying ownership contract is Skia's.
            org.jetbrains.skia.Image.makeRaster(org.jetbrains.skia.ImageInfo.makeN32(8, 8, org.jetbrains.skia.ColorAlphaType.OPAQUE), bytes, 32).use {
                target.drawImage(it, 0f, 0f)
            }
            recorder.finishRecordingAsPicture().use { picture ->
                org.jetbrains.skia.Surface.makeRasterN32Premul(8, 8).use { surface ->
                    surface.canvas.drawPicture(picture)
                    surface.makeImageSnapshot().use { assertEquals(8, it.width) }
                }
            }
        }
        assertEquals(androidx.compose.ui.geometry.Size(8f, 8f), frame.intrinsicSize)
    }
}
