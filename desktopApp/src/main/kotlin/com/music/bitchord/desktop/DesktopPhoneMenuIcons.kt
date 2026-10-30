package com.music.bitchord.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Desktop-native vector equivalents of the BitChord Android/iOS context menu symbols.
 * Uses Compose geometry rather than the Android-only SF Symbols package.
 * The existing shared BitChordIcons.Star / StarFilled are used directly for Like.
 */
internal object DesktopPhoneMenuIcons {
    private val ink = SolidColor(Color.Black)

    val BrokenHeart: ImageVector by lazy { brokenHeart(false) }
    val BrokenHeartFilled: ImageVector by lazy { brokenHeart(true) }

    private fun brokenHeart(filled: Boolean): ImageVector =
        ImageVector.Builder(
            name = if (filled) "mobile_broken_heart_fill" else "mobile_broken_heart",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = if (filled) ink else null,
                stroke = if (filled) null else ink,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(11f, 20.6f)
                lineTo(5.1f, 15.1f)
                curveTo(1.6f, 11.8f, 2.0f, 7.2f, 5.3f, 4.8f)
                curveTo(7.4f, 3.3f, 10.1f, 4.2f, 11.4f, 6.3f)
                lineTo(9.7f, 9.4f)
                lineTo(12f, 12.1f)
                lineTo(10f, 15.3f)
                close()
            }
            path(
                fill = if (filled) ink else null,
                stroke = if (filled) null else ink,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(13.2f, 20.3f)
                lineTo(18.9f, 15.1f)
                curveTo(22.4f, 11.8f, 22.0f, 7.2f, 18.7f, 4.8f)
                curveTo(16.7f, 3.3f, 14.0f, 4.2f, 12.7f, 6.3f)
                lineTo(14.2f, 9.5f)
                lineTo(12.1f, 12.5f)
                lineTo(14.4f, 15.8f)
                close()
            }
        }.build()

    val Share: ImageVector by lazy {
        ImageVector.Builder(name = "mobile_share", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = ink, strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 15f)
                lineTo(12f, 3f)
                moveTo(7.5f, 7.5f)
                lineTo(12f, 3f)
                lineTo(16.5f, 7.5f)
                moveTo(7f, 10f)
                lineTo(5.8f, 10f)
                curveTo(4.4f, 10f, 4f, 11f, 4f, 12.5f)
                lineTo(4f, 19f)
                curveTo(4f, 20.5f, 4.8f, 21f, 6.2f, 21f)
                lineTo(17.8f, 21f)
                curveTo(19.2f, 21f, 20f, 20.5f, 20f, 19f)
                lineTo(20f, 12.5f)
                curveTo(20f, 11f, 19.6f, 10f, 18.2f, 10f)
                lineTo(17f, 10f)
            }
        }.build()
    }

    val Info: ImageVector by lazy {
        ImageVector.Builder(name = "mobile_info", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = ink, strokeLineWidth = 2.1f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 2.5f)
                curveTo(6.7f, 2.5f, 2.5f, 6.7f, 2.5f, 12f)
                curveTo(2.5f, 17.3f, 6.7f, 21.5f, 12f, 21.5f)
                curveTo(17.3f, 21.5f, 21.5f, 17.3f, 21.5f, 12f)
                curveTo(21.5f, 6.7f, 17.3f, 2.5f, 12f, 2.5f)
                moveTo(12f, 10.5f)
                lineTo(12f, 16.5f)
            }
            path(fill = ink) {
                moveTo(12f, 7f)
                lineTo(12.01f, 7f)
                close()
            }
        }.build()
    }
}
