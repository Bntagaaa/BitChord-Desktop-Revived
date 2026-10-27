package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Revived's original full-bleed Apple artist art + logo fallbacks.
 *
 * Kept separate from Continued's metadata repository so a missing motion video
 * always shows a wide Apple still, then YouTube Music artwork. Never render a
 * circular portrait-only artist header.
 */
@androidx.compose.runtime.Composable
internal fun DesktopAppleArtistHero(
    fallbackUrl: String?,
    appleUrl: String?,
    fallbackPx: Int,
    modifier: androidx.compose.ui.Modifier,
) {
    val appleBitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = appleUrl,
    ) {
        value = if (appleUrl.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            DesktopArtworkCache.load(appleUrl)
        }
    }
    DesktopArtwork(fallbackUrl, modifier, px = fallbackPx)
    if (appleBitmap != null) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.graphics.painter.BitmapPainter(appleBitmap!!),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier,
        )
    }
}

@Composable
internal fun DesktopAppleArtistLogo(
    url: String?,
    contentDescription: String,
    modifier: androidx.compose.ui.Modifier,
    fallback: @Composable () -> Unit,
) {
    val bitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = url,
    ) {
        value = if (url.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            DesktopArtworkCache.load(url)
        }
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.graphics.painter.BitmapPainter(bitmap!!),
            contentDescription = contentDescription,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        fallback()
    }
}
