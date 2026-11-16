package com.music.bitchord.ui.components

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** This source set actually runs as :desktopUi:jvmTest, unlike sharedUi's Android target. */
class ViewportThumbnailGateTest {
    private val shelfViewport = Rect(160f, 180f, 1080f, 530f)
    private val hidden = Rect.Zero

    @Test fun visibleImageIsAllowedWithoutAnyPrefetchMargin() {
        val art = Rect(240f, 240f, 440f, 440f)
        assertTrue(shouldRequestThumbnail(art, art, shelfViewport, 0f, 0f))
    }

    @Test fun justAheadOfHorizontalShelfIsPrefetched() {
        val next = Rect(1090f, 250f, 1200f, 420f)
        assertTrue(shouldRequestThumbnail(next, hidden, shelfViewport, 200f, 140f))
    }

    @Test fun distantOffscreenImagesAreNotFetched() {
        assertFalse(shouldRequestThumbnail(
            Rect(1390f, 250f, 1510f, 420f), hidden, shelfViewport, 200f, 140f,
        ))
        assertFalse(shouldRequestThumbnail(
            Rect(240f, 960f, 440f, 1150f), hidden, shelfViewport, 200f, 140f,
        ))
    }

    @Test fun hysteresisRetainsImageJustOutsideEntranceMargin() {
        val justOutside = Rect(1290f, 230f, 1390f, 350f)
        assertFalse(shouldRequestThumbnail(justOutside, hidden, shelfViewport, 200f, 140f))
        assertTrue(shouldRequestThumbnail(justOutside, hidden, shelfViewport, 280f, 220f))
    }

    @Test fun neverFetchZeroAreaOrUnclippedUnknownViewport() {
        assertFalse(shouldRequestThumbnail(
            Rect(40f, 50f, 40f, 250f), hidden, shelfViewport, 200f, 140f,
        ))
        assertFalse(shouldRequestThumbnail(
            Rect(1100f, 250f, 1200f, 430f), hidden, null, 200f, 140f,
        ))
    }
}
