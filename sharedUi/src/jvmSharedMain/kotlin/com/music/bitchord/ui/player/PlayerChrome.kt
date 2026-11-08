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
 * On desktop the artwork-tinted palette is drawn directly, never in an offscreen
 * additive layer: Skia's offscreen layer loses native glyph edge antialiasing
 * and makes song titles and captions look jagged on HiDPI and fractional scaling.
 */
internal val LocalPlayerChrome = compositionLocalOf { false }

internal const val CHROME_FULL_ALPHA = 0.73f

/** Desktop's direct-rendered equivalent of mobile's bright, blended grab handle. */
internal const val DESKTOP_PLAYER_HANDLE_ALPHA = 0.88f

/** The mobile disc's neutral ink before the small ColorDodge layer is applied. */
internal val VibrantDiscInk = Color(0xFF999999).copy(alpha = 0.85f)
private const val VIBRANT_DISC_FLOOR = 0.12f
private const val VIBRANT_DISC_GAIN = 2.5f

/** Active/inactive disc animation uses mobile's 0.34/0.18 alpha values. */
internal fun playerDiscPaintAlpha(alpha: Float, blended: Boolean): Float =
    if (blended) (alpha * VIBRANT_DISC_GAIN).coerceIn(0f, 1f)
    else desktopDirectChromeAlpha(alpha)
private const val CHROME_ALPHA_DROP = 0.15f

/** The same opacity values as mobile's PlayerChrome, independently testable. */
internal fun mobileChromeAlpha(alpha: Float, blended: Boolean): Float =
    (if (blended) alpha - CHROME_ALPHA_DROP else alpha).coerceIn(0f, 1f)

@Composable
@ReadOnlyComposable
internal fun chromeWhite(alpha: Float): Color =
    Color.White.copy(alpha = mobileChromeAlpha(alpha, LocalPlayerChrome.current))

/**
 * The mobile player's additive white ink brightens the coloured backdrop
 * underneath each glyph. Desktop deliberately draws glyphs directly to avoid
 * the jagged edges caused by recording a Skia BlendMode.Plus text layer.
 * It therefore needs a brighter direct-rendered fallback, otherwise mobile's
 * artist and other secondary labels become dark grey.
 *
 * This multiplier applies to text and controls only; it does not alter the
 * album-derived backdrop or its cached blur, and preserves the alpha hierarchy.
 */
internal fun desktopDirectChromeAlpha(alpha: Float): Float =
    (alpha * 1.55f).coerceIn(0f, 1f)

/**
 * Use the artwork's softly lifted tint for desktop secondary text. The seed is
 * already decoded for the player's status-bar treatment and cached by art URL.
 * On Android keep the existing fork behaviour unchanged.
 */
@Composable
@ReadOnlyComposable
internal fun playerSecondaryColor(alpha: Float): Color = when {
    LocalPlayerChrome.current && PlayerPlatform.host.mobileTextChrome ->
        LocalPlayerTint.current.copy(alpha = desktopDirectChromeAlpha(alpha))
    LocalPlayerChrome.current -> chromeWhite(alpha)
    else -> LocalPlayerTint.current.copy(alpha = alpha)
}

/**
 * Only use an offscreen additive layer on platforms that can preserve its
 * native glyph rasterization. Desktop Skia loses subpixel/text edge smoothing
 * when white SF Pro glyphs are drawn into a recorded layer and composited back
 * with BlendMode.Plus. Keep their mobile opacity values but draw them directly.
 *
 * Desktop must not make another offscreen bitmap for every text label; this
 * also avoids blurry text when the application uses fractional display scaling.
 */
internal fun shouldUseOffscreenChromeLayer(
    enabled: Boolean,
    desktopTextChrome: Boolean,
    blendSupported: Boolean,
): Boolean = enabled && !desktopTextChrome && blendSupported

internal fun Modifier.additiveChrome(enabled: Boolean): Modifier =
    if (!enabled || !shouldUseOffscreenChromeLayer(
            enabled = enabled,
            desktopTextChrome = PlayerPlatform.host.mobileTextChrome,
            blendSupported = BlendMode.Plus.isSupported(),
        )
    ) this
    else drawWithCache {
        val layer = obtainGraphicsLayer().apply { blendMode = BlendMode.Plus }
        onDrawWithContent {
            layer.record { this@onDrawWithContent.drawContent() }
            drawLayer(layer)
        }
    }

/**
 * Mobile's vibrant disc blend, isolated to the button background.
 *
 * Unlike the old fullscreen text chrome, no text or vector icon is recorded
 * here; the white 19dp icon stays in Compose's native antialiased draw path.
 * Only two 34dp circles can create these cached layers.
 */
internal fun Modifier.vibrantDiscChrome(enabled: Boolean): Modifier =
    if (!enabled) this
    else drawWithCache {
        val floor = obtainGraphicsLayer().apply {
            blendMode = BlendMode.Plus
            alpha = VIBRANT_DISC_FLOOR
        }
        val dodge = obtainGraphicsLayer().apply { blendMode = BlendMode.ColorDodge }
        onDrawWithContent {
            floor.record { this@onDrawWithContent.drawContent() }
            dodge.record { this@onDrawWithContent.drawContent() }
            drawLayer(floor)
            drawLayer(dodge)
        }
    }
