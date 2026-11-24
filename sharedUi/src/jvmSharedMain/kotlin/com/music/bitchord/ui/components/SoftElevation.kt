package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Desktop's opaque, softly lit surfaces. No backdrop capture, blur or shader. */
enum class SoftSurfaceRole { POPUP, BUTTON, QUIET_BUTTON }

data class SoftSurfacePaint(
    val top: Color,
    val bottom: Color,
    val edge: Color,
    val elevation: Dp,
)

fun softSurfacePaint(
    fill: Color,
    light: Boolean,
    role: SoftSurfaceRole,
    enabled: Boolean = true,
): SoftSurfacePaint {
    val ink = if (light) Color.Black else Color.White
    val stateAlpha = if (enabled) 1f else 0.45f
    return when (role) {
        SoftSurfaceRole.POPUP -> {
            // A neutral, slightly raised surface; never a bright glass outline.
            val base = if (light) fill.copy(alpha = 1f)
                else lerp(fill.copy(alpha = 1f), Color(0xFF30323B), 0.48f)
            SoftSurfacePaint(
                top = lerp(base, Color.White, if (light) 0.24f else 0.035f),
                bottom = lerp(base, Color.Black, if (light) 0.025f else 0.018f),
                edge = ink.copy(alpha = if (light) 0.10f else 0.075f),
                elevation = 14.dp,
            )
        }
        SoftSurfaceRole.BUTTON -> SoftSurfacePaint(
            top = lerp(fill, Color.White.copy(alpha = fill.alpha), 0.075f),
            bottom = lerp(fill, Color.Black.copy(alpha = fill.alpha), 0.065f),
            edge = ink.copy(alpha = 0.11f * stateAlpha),
            elevation = if (enabled) 2.dp else 0.dp,
        )
        SoftSurfaceRole.QUIET_BUTTON -> SoftSurfacePaint(
            top = ink.copy(alpha = 0.075f * stateAlpha),
            bottom = ink.copy(alpha = 0.035f * stateAlpha),
            edge = ink.copy(alpha = 0.055f * stateAlpha),
            elevation = 0.dp,
        )
    }
}

/** Decoration only: keeps the caller's layout, click target, typography and colors. */
fun Modifier.softElevatedSurface(
    shape: Shape,
    fill: Color,
    light: Boolean,
    role: SoftSurfaceRole = SoftSurfaceRole.POPUP,
    enabled: Boolean = true,
): Modifier {
    val paint = softSurfacePaint(fill, light, role, enabled)
    val shadowed = if (paint.elevation > 0.dp) {
        shadow(
            elevation = paint.elevation,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = if (role == SoftSurfaceRole.POPUP) 0.26f else 0.15f),
            spotColor = Color.Black.copy(alpha = if (role == SoftSurfaceRole.POPUP) 0.42f else 0.22f),
        )
    } else this
    return shadowed
        .clip(shape)
        .background(Brush.verticalGradient(listOf(paint.top, paint.bottom)))
        .border(0.5.dp, paint.edge, shape)
}
