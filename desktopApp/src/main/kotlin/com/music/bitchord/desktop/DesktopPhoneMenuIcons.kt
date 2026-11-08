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

    // Original Compose vectors inspired by the mobile menu glyph vocabulary.
    // Not redistributed Apple SF Symbols; these are desktop-native path drawings.
    // Vectors are lazy-built once, never parsed or rasterized per recomposition.
    private fun vector(name: String, draw: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply(draw).build()

    val AddToPlaylist: ImageVector by lazy {
        vector("desktop_mobile_playlist_add") {
            path(stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(3f, 7f); lineTo(15f, 7f)
                moveTo(3f, 11.5f); lineTo(15f, 11.5f)
                moveTo(3f, 16f); lineTo(10f, 16f)
                moveTo(18f, 14f); lineTo(18f, 21f)
                moveTo(14.5f, 17.5f); lineTo(21.5f, 17.5f)
            }
        }
    }
    val Radio: ImageVector by lazy {
        vector("desktop_mobile_radio_waves") {
            path(stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 8f); lineTo(12f, 16f)
                moveTo(9.2f, 6.2f); curveTo(6.8f, 9f, 6.8f, 15f, 9.2f, 17.8f)
                moveTo(14.8f, 6.2f); curveTo(17.2f, 9f, 17.2f, 15f, 14.8f, 17.8f)
                moveTo(6.1f, 3.4f); curveTo(1.7f, 8f, 1.7f, 16f, 6.1f, 20.6f)
                moveTo(17.9f, 3.4f); curveTo(22.3f, 8f, 22.3f, 16f, 17.9f, 20.6f)
            }
        }
    }
    private fun queue(first: Boolean): ImageVector =
        vector(if (first) "desktop_mobile_play_next" else "desktop_mobile_add_queue") {
            path(stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                for (y in listOf(5.5f, 10f, 14.5f, 19f)) {
                    moveTo(7.5f, y); lineTo(21f, y)
                }
            }
            path(fill = ink) {
                if (first) {
                    moveTo(3f, 3f); lineTo(3f, 10f); lineTo(7f, 6.5f)
                } else {
                    moveTo(3f, 14f); lineTo(3f, 21f); lineTo(7f, 17.5f)
                }
                close()
            }
        }
    val PlayNext: ImageVector by lazy { queue(true) }
    val AddToQueue: ImageVector by lazy { queue(false) }

    val Download: ImageVector by lazy {
        vector("desktop_mobile_download_circle") {
            path(stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round) {
                moveTo(21f, 12f)
                curveTo(21f, 17f, 17f, 21f, 12f, 21f)
                curveTo(7f, 21f, 3f, 17f, 3f, 12f)
                curveTo(3f, 7f, 7f, 3f, 12f, 3f)
                curveTo(17f, 3f, 21f, 7f, 21f, 12f)
                moveTo(12f, 7f); lineTo(12f, 16f)
                moveTo(8.5f, 12.5f); lineTo(12f, 16f); lineTo(15.5f, 12.5f)
            }
        }
    }
    val Album: ImageVector by lazy {
        vector("desktop_mobile_album_stack") {
            path(stroke = ink, strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round,
                strokeLineCap = StrokeCap.Round) {
                moveTo(5.5f, 5f); lineTo(18.5f, 5f)
                moveTo(4.5f, 8f); lineTo(19.5f, 8f)
                moveTo(5f, 11f); lineTo(19f, 11f)
                curveTo(20f, 11f, 20.5f, 11.7f, 20.5f, 12.7f)
                lineTo(20.5f, 20f); lineTo(3.5f, 20f); lineTo(3.5f, 12.7f)
                curveTo(3.5f, 11.7f, 4f, 11f, 5f, 11f)
                close()
            }
        }
    }
    val Artist: ImageVector by lazy {
        vector("desktop_mobile_artist_mic") {
            path(stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round) {
                moveTo(11f, 16f); lineTo(6f, 21f)
                moveTo(13.5f, 18.3f); lineTo(10.5f, 21.3f)
                moveTo(8f, 14f); lineTo(12f, 18f)
                moveTo(8f, 14f); lineTo(14.5f, 6.7f)
                moveTo(12f, 18f); lineTo(19.3f, 11.5f)
                moveTo(14.5f, 6.7f)
                curveTo(17f, 4f, 21f, 5f, 21f, 8.4f)
                curveTo(21f, 10f, 20.2f, 11f, 19.3f, 11.5f)
            }
        }
    }
    val SleepTimer: ImageVector by lazy {
        vector("desktop_mobile_sleep_timer") {
            path(stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round) {
                moveTo(17.5f, 19.5f)
                curveTo(11f, 22f, 4f, 17f, 5f, 9f)
                curveTo(5.5f, 6f, 7f, 4.5f, 9f, 3f)
                curveTo(8f, 10f, 12.5f, 15f, 19.5f, 14f)
            }
            path(stroke = ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round) {
                moveTo(15f, 5f); lineTo(19f, 5f); lineTo(15f, 8f); lineTo(19f, 8f)
                moveTo(19f, 2.5f); lineTo(22f, 2.5f); lineTo(19f, 4.5f); lineTo(22f, 4.5f)
            }
        }
    }

}
