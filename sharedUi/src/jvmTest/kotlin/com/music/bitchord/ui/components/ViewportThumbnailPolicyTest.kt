package com.music.bitchord.ui.components

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewportThumbnailPolicyTest {
    private val viewport = Rect(180f, 190f, 1080f, 520f)
    private val clippedOut = Rect(0f, 0f, 0f, 0f)

    @Test fun fullyVisibleThumbnailRequestsImmediately() {
        val image = Rect(300f, 250f, 450f, 400f)
        assertTrue(shouldRequestThumbnail(image, image, viewport, 0f, 0f))
    }

    @Test fun nextCardPrimesJustOutsideVisibleShelf() {
        assertTrue(shouldRequestThumbnail(
            Rect(1090f, 250f, 1180f, 400f), clippedOut, viewport, 200f, 140f,
        ))
        assertFalse(shouldRequestThumbnail(
            Rect(1340f, 250f, 1460f, 400f), clippedOut, viewport, 200f, 140f,
        ))
    }

    @Test fun farBelowShelfNeverRequestsImage() {
        assertFalse(shouldRequestThumbnail(
            Rect(300f, 1050f, 450f, 1200f), clippedOut, viewport, 200f, 140f,
        ))
    }

    @Test fun clippedImageBeyondOverscanDoesNotRequest() {
        assertFalse(shouldRequestThumbnail(
            Rect(1150f, 280f, 1250f, 380f), clippedOut, viewport, 0f, 0f,
        ))
    }

    @Test fun missingSizeOrViewportNeverIssuesRequest() {
        assertFalse(shouldRequestThumbnail(
            Rect(100f, 100f, 100f, 200f), clippedOut, viewport, 200f, 140f,
        ))
        assertFalse(shouldRequestThumbnail(
            Rect(1120f, 270f, 1200f, 350f), clippedOut, null, 200f, 140f,
        ))
    }
}
