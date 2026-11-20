package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

@Composable
fun DesktopContinuedArtistMotionView(
    url: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackUrl: String? = null,
    maxEdge: Int = DesktopCanvasDecoder.MAX_EDGE,
    publishBackdrop: Boolean = true,
) = DesktopMotionArtwork(
    MotionVideoSource(url, fallbackUrl, maxEdge, MotionVideoSource.Clock.Artist),
    modifier, isPlaying, contentScale, publishBackdrop, trackViewport = true,
)
