package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.optimizedHazeEffect
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

/** v1.10's shared frosted glass for the like and menu controls. A null source is flat. */
internal data class PlayerGlassMaterial(val hazeState: HazeState?)
internal val LocalPlayerGlass = compositionLocalOf<PlayerGlassMaterial?> { null }
private val GlassCharcoal = Color(0xFF1C1C1E)
private const val GLASS_SLAB_ALPHA = 0.38f
private const val GLASS_FLAT_ALPHA = 0.72f

private val DiscPairShape = GenericShape { size, _ ->
    val d = size.height
    addOval(Rect(0f, 0f, d, d))
    if (size.width >= d * 1.5f) addOval(Rect(size.width - d, 0f, size.width, d))
}

internal fun Modifier.glassRim(shape: Shape): Modifier = border(
    width = 1.dp,
    brush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.42f),
        1f to Color.White.copy(alpha = 0.14f),
    ),
    shape = shape,
)

@Composable
@OptIn(ExperimentalHazeApi::class)
internal fun GlassDiscPair(content: @Composable RowScope.() -> Unit) {
    val material = LocalPlayerGlass.current
    if (material == null) {
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
        return
    }
    val state = material.hazeState
    Box {
        Box(
            Modifier.matchParentSize().clip(DiscPairShape).then(
                if (state == null) {
                    Modifier.background(GlassCharcoal.copy(alpha = GLASS_FLAT_ALPHA))
                } else {
                    Modifier.optimizedHazeEffect(
                        state = state,
                        style = HazeStyle(
                            backgroundColor = GlassCharcoal,
                            tints = listOf(HazeTint(GlassCharcoal.copy(alpha = GLASS_SLAB_ALPHA))),
                            blurRadius = 24.dp,
                            noiseFactor = 0f,
                            fallbackTint = HazeTint(GlassCharcoal.copy(alpha = GLASS_FLAT_ALPHA)),
                        ),
                    ) {
                        // One tenth resolution and one effect for both discs, like upstream.
                        inputScale = HazeInputScale.Fixed(0.10f)
                        canDrawArea = { true }
                    }
                },
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
