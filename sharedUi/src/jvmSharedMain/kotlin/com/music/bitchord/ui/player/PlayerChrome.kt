package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSupported
import androidx.compose.ui.graphics.layer.drawLayer

/**
 * Mobile BitChord's secondary-text chrome: white ink is added to the blurred
 * album colours instead of tinting the glyphs from a second palette sample.
 *
 * Enabled by the desktop host only. Android retains the existing fork's look.
 * Reduce Blur and video mode disable the blend, so there is no full-screen
 * offscreen compositing and normal white text remains readable.
 */
internal val LocalPlayerChrome = compositionLocalOf { false }

internal const val CHROME_FULL_ALPHA = 0.73f
private const val CHROME_ALPHA_DROP = 0.15f

/** The same opacity values as mobile's PlayerChrome, independently testable. */
internal fun mobileChromeAlpha(alpha: Float, blended: Boolean): Float =
    (if (blended) alpha - CHROME_ALPHA_DROP else alpha).coerceIn(0f, 1f)

@Composable
@ReadOnlyComposable
internal fun chromeWhite(alpha: Float): Color =
    Color.White.copy(alpha = mobileChromeAlpha(alpha, LocalPlayerChrome.current))

/** Keep the fork's existing artist tint on Android; neutral white on desktop. */
@Composable
@ReadOnlyComposable
internal fun playerSecondaryColor(alpha: Float): Color =
    if (LocalPlayerChrome.current) chromeWhite(alpha)
    else LocalPlayerTint.current.copy(alpha = alpha)

/**
 * Cache the glyph layer and blend only the element asking for it.
 * Nothing ever requests a full-window layer just to blend a caption.
 */
internal fun Modifier.additiveChrome(enabled: Boolean): Modifier =
    if (!enabled || !BlendMode.Plus.isSupported()) this
    else drawWithCache {
        val layer = obtainGraphicsLayer().apply { blendMode = BlendMode.Plus }
        onDrawWithContent {
            layer.record { this@onDrawWithContent.drawContent() }
            drawLayer(layer)
        }
    }
