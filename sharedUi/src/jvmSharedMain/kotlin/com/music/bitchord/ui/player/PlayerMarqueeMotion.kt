package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Freeze at the drawn horizontal position while the metadata's vertical slide owns motion. */
@Composable
internal fun rememberPlayerMarqueeOffset(
    text: String,
    travelPx: Int,
    startDelayMillis: Long,
    paused: Boolean,
    pixelsPerMillisecond: Float,
    restMillis: Long,
): Animatable<Float, androidx.compose.animation.core.AnimationVector1D> {
    val offset = remember(text, travelPx) { Animatable(0f) }
    LaunchedEffect(offset, startDelayMillis, paused, pixelsPerMillisecond, restMillis) {
        if (paused || travelPx <= 0) return@LaunchedEffect
        delay(startDelayMillis)
        while (true) {
            val remaining = (travelPx + offset.value).coerceAtLeast(0f)
            val duration = (remaining / pixelsPerMillisecond).roundToInt().coerceAtLeast(1)
            offset.animateTo(-travelPx.toFloat(), tween(duration, easing = LinearEasing))
            offset.snapTo(0f)
            delay(restMillis)
        }
    }
    return offset
}
