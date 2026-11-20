package com.music.bitchord.desktop

import org.junit.Test
import kotlin.test.assertEquals

class DesktopGpuVideoSurfaceScaleTest {
    @Test fun cropWideVideoIntoSquareCover() {
        assertEquals(16f / 9f, gpuVideoCoverScale(500f, 500f, 16f / 9f, fit = false), .001f)
    }

    @Test fun cropPortraitVideoIntoWideArtistHero() {
        assertEquals(3.0f, gpuVideoCoverScale(900f, 300f, 1f, fit = false), .001f)
    }

    @Test fun fitVideoDoesNotCrop() {
        assertEquals(1f, gpuVideoCoverScale(900f, 300f, 1f, fit = true))
    }

    @Test fun correctAspectDoesNotResize() {
        assertEquals(1f, gpuVideoCoverScale(1920f, 1080f, 16f / 9f, fit = false))
    }

    @Test fun invalidMediaSizeLeavesSurfaceUsable() {
        assertEquals(1f, gpuVideoCoverScale(0f, 720f, Float.NaN, fit = false))
        assertEquals(1f, gpuVideoCoverScale(1280f, 720f, 0f, fit = false))
    }
}
