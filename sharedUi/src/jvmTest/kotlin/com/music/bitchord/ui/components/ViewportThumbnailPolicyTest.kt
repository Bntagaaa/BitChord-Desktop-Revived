package com.music.bitchord.ui.components

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewportThumbnailPolicyTest {
    private val window = Rect(0f, 0f, 1280f, 720f)
    private val horizontalShelf = Rect(180f, 190f, 1080f, 520f)

    @Test fun visibleThumbnailRequestsImmediately() {
        assertTrue(ViewportThumbnailPolicy.closeEnough(
            Rect(300f, 250f, 450f, 400f), window, listOf(horizontalShelf), 0f,
        ))
    }

    @Test fun nextCardPrimesJustOutsideVisibleShelf() {
        assertTrue(ViewportThumbnailPolicy.closeEnough(
            Rect(1090f, 250f, 1180f, 400f), window, listOf(horizontalShelf), 144f,
        ))
        assertFalse(ViewportThumbnailPolicy.closeEnough(
            Rect(1340f, 250f, 1460f, 400f), window, listOf(horizontalShelf), 144f,
        ))
    }

    @Test fun offscreenVerticalShelfDoesNotRequestImages() {
        assertFalse(ViewportThumbnailPolicy.closeEnough(
            Rect(300f, 1050f, 450f, 1200f), window,
            listOf(Rect(180f, 1000f, 1080f, 1250f)), 144f,
        ))
    }

    @Test fun nestedParentClippingOverridesWindowVisibility() {
        assertFalse(ViewportThumbnailPolicy.closeEnough(
            Rect(1150f, 280f, 1250f, 380f), window,
            listOf(horizontalShelf, Rect(180f, 190f, 1080f, 520f)), 0f,
        ))
    }

    @Test fun noSizeMeansNoNetworkRequest() {
        assertFalse(ViewportThumbnailPolicy.closeEnough(
            Rect(100f, 100f, 100f, 200f), window, emptyList(), 200f,
        ))
    }
}
