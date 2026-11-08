package com.music.bitchord.ui.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp

// LyricsPanel bleeds into the 30dp gutter for its row highlight and glow.
// Clip vertically while preserving that horizontal drawing room.
internal fun Modifier.clipLandscapeLyricsVertically(): Modifier = drawWithContent {
    val bleed = 30.dp.toPx()
    clipRect(left = -bleed, right = size.width + bleed) { this@drawWithContent.drawContent() }
}

internal fun Modifier.landscapeLyricEdges(
    top: () -> Float = { 0f },
    fadeTop: Boolean = true,
    fadeBottom: Boolean = true,
): Modifier = drawWithContent {
    val edge = top().coerceIn(0f, size.height)
    if (edge >= size.height) return@drawWithContent
    val bleed = 30.dp.toPx()
    val bounds = Rect(-bleed, edge, size.width + bleed, size.height)
    val fade = minOf(28.dp.toPx(), bounds.height / 2f)
    // A widened layer avoids cropping the left/right rounded corners, unlike
    // an offscreen graphicsLayer whose surface ends at the lyric text bounds.
    drawIntoCanvas { canvas ->
        canvas.saveLayer(bounds, Paint())
        clipRect(left = bounds.left, top = edge, right = bounds.right) {
            this@drawWithContent.drawContent()
            fun mask(brush: Brush) = drawRect(brush,
                topLeft = Offset(bounds.left, edge), size = Size(bounds.width, bounds.height),
                blendMode = BlendMode.DstIn)
            if (fadeTop) mask(Brush.verticalGradient(
                listOf(Color.Transparent, Color.White), startY = edge, endY = edge + fade))
            if (fadeBottom) mask(Brush.verticalGradient(
                listOf(Color.White, Color.Transparent), startY = size.height - fade, endY = size.height))
        }
        canvas.restore()
    }
}
