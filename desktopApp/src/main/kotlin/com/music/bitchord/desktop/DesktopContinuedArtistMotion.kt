package com.music.bitchord.desktop

import kotlinx.coroutines.flow.first

import androidx.compose.runtime.snapshotFlow

import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.runtime.collectAsState

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

/** Continued's HLS renderer used only by Apple Artist Page. */
/** The motion artwork, drawn as frames rather than played by a native child. */
@Composable
fun DesktopContinuedArtistMotionView(
    url: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
    /** Tried once if [url] will not decode — the Apple provider's second rendition of the clip. */
    fallbackUrl: String? = null,
    maxEdge: Int = DesktopContinuedArtistDecoder.MAX_EDGE,
    /** Off for clips that are not the playing track's, so the player's backdrop is left alone. */
    publishBackdrop: Boolean = true,
) {
    var frame by remember(url) { mutableStateOf<DesktopCanvasFrame?>(null) }
    val windowVisible by DesktopRenderVisibility.visible.collectAsState()
    val running by rememberUpdatedState(isPlaying && windowVisible)

    LaunchedEffect(url, fallbackUrl, maxEdge, publishBackdrop) {
        // Round and round until the track ends or the player is closed. A canvas is a few seconds
        // long, so one pass through it is not the feature — and the decoder cannot be relied on to
        // rewind itself: its own seek does not take on an HLS manifest, which is what Apple serves,
        // and the clip then stopped dead on its last frame for the rest of the song.
        while (isActive) {
            snapshotFlow { running }.first { it }
            val decoder = DesktopContinuedArtistDecoder(maxEdge)
            var shown = 0
            try {
                val opened = withContext(Dispatchers.IO) {
                    var last: Result<Unit> = Result.failure(IllegalStateException("the clip could not be fetched"))
                    for (candidate in listOfNotNull(url, fallbackUrl)) {
                        val source = if (isManifest(candidate)) {
                            candidate
                        } else {
                            DesktopCanvasCache.fileFor(candidate)?.toAbsolutePath()?.toString() ?: continue
                        }
                        last = decoder.open(source)
                        if (last.isSuccess) break
                    }
                    last
                }
                if (opened.isFailure) {
                    DesktopTrackLog.log("canvas: ${opened.exceptionOrNull()?.message}")
                    return@LaunchedEffect
                }
                val pixels = ByteArray(decoder.width * decoder.height * 4)
                val info = ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)
                while (isActive) {
                    snapshotFlow { running }.first { it }
                    val started = System.currentTimeMillis()
                    val decoded = withContext(Dispatchers.IO) { decoder.nextFrame(pixels) }
                    if (!decoded) break
                    shown++
                    // Keep only an immutable JVM frame; temporary native draw images are closed immediately.
                    val next = DesktopCanvasFrame(info, pixels)
                    frame = next
                    // The backdrop reads the first frame and holds it: re-meshing every frame would
                    // be a full resample twenty-five times a second for a wash nobody is watching
                    // closely, and the clip's palette does not change much across it anyway.
                    if (shown == 1 && publishBackdrop) DesktopCanvasBackdrop.publish(url, next.capture())
                    val spent = System.currentTimeMillis() - started
                    delay((decoder.frameIntervalMillis - spent).coerceAtLeast(0L))
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { decoder.close() }
            }
            // A pass that drew nothing would spin: reopening cannot fix a clip that has no frames
            // in it, and retrying immediately is a busy loop over the network.
            if (shown == 0) {
                DesktopTrackLog.log("canvas: the clip decoded no frames; not looping it")
                return@LaunchedEffect
            }
        }
    }

    DisposableEffect(url) {
        onDispose { DesktopCanvasBackdrop.clear(url) }
    }

    // Nothing at all until the first frame: a placeholder here would flash between the still cover
    // and the clip on every track change.
    frame?.let {
        Image(
            painter = it,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

