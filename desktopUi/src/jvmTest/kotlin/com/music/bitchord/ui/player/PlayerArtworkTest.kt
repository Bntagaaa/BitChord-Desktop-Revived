package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import coil3.PlatformContext
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerArtworkTest {
    @Test
    fun aSuccessfulCoverSurvivesRefreshAndReopeningButDoesNotCrossTracks() {
        val context = PlatformContext.INSTANCE
        val retained = RetainedPlayerCover("playing")
        val art = PlayerArtwork("https://example.test/cover", context, retained)
        val cover = ImageBitmap(2, 2).asSkiaBitmap().asImage()
        val originalRequest = art.request
        art.onState(AsyncImagePainter.State.Success(TestPainter, SuccessResult(cover, originalRequest)))
        art.onState(AsyncImagePainter.State.Empty)
        assertTrue(art.loaded)
        assertSame(originalRequest, art.request)

        val reopened = PlayerArtwork("https://example.test/refreshed", context, retained)
        assertTrue(reopened.loaded)
        assertSame(cover, reopened.request.placeholder())
        val failure = ErrorResult(cover, reopened.request, IllegalStateException("Offline"))
        reopened.onState(AsyncImagePainter.State.Error(TestPainter, failure))
        assertTrue(reopened.loaded)
        assertSame(cover, reopened.request.error())

        val next = PlayerArtwork(art.url, context, RetainedPlayerCover("next"))
        assertFalse(next.loaded)
        assertNull(next.request.placeholder())
    }

    private object TestPainter : androidx.compose.ui.graphics.painter.Painter() {
        override val intrinsicSize = androidx.compose.ui.geometry.Size.Unspecified
        override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() = Unit
    }
}
