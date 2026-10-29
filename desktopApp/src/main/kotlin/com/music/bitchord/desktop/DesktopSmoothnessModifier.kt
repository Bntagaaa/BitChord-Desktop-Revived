package com.music.bitchord.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

/** Added only to developer captures; ordinary launches keep the original modifier chain. */
internal fun Modifier.smoothnessTrace(): Modifier = if(!DesktopSmoothnessTrace.enabled) this else drawWithContent {
    val start=System.nanoTime()
    drawContent()
    DesktopSmoothnessTrace.drawn(start)
}


/** Sparse requests only: idle captures do not create an artificial continuous animation loop. */
@androidx.compose.runtime.Composable
internal fun DesktopSmoothnessFrameProbe() {
    if (!DesktopSmoothnessTrace.enabled) return
    androidx.compose.runtime.LaunchedEffect(Unit) {
        for (request in DesktopSmoothnessTrace.frameRequests) {
            androidx.compose.runtime.withFrameNanos { DesktopSmoothnessTrace.frameCallback() }
        }
    }
}
