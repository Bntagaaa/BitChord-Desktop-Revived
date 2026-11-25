package com.music.bitchord.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Cohesive SF-style & Lucide-derived Compose [ImageVector] icon set for BitChord.
 *
 * All icons share a 24x24 viewport, 2.0f primary stroke, [StrokeCap.Round], and
 * [StrokeJoin.Round] to match [BitChordIcons].
 *
 * Licensing:
 * - Lucide-derived icons are adapted from Lucide (ISC License).
 * - Remaining glyphs are original Compose vector drawings inspired by Apple Music /
 *   SF-style iconography without redistributing Apple SF Symbols assets.
 */
object SfStyleIcons {

    private const val STROKE = 2.0f
    private val ink = SolidColor(Color.Black)

    private inline fun vector(
        name: String,
        autoMirror: Boolean = false,
        crossinline block: ImageVector.Builder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).apply(block).build()

    private inline fun ImageVector.Builder.strokePath(
        width: Float = STROKE,
        fill: SolidColor? = null,
        crossinline block: PathBuilder.() -> Unit,
    ) {
        path(
            fill = fill,
            stroke = ink,
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            block()
        }
    }

    private inline fun ImageVector.Builder.fillPath(
        crossinline block: PathBuilder.() -> Unit,
    ) {
        path(fill = ink) { block() }
    }

    // -------------------------------------------------------------------------
    // Navigation & Directional
    // -------------------------------------------------------------------------

    val Close: ImageVector by lazy {
        vector("sf_close") {
            strokePath {
                moveTo(18f, 6f); lineTo(6f, 18f)
                moveTo(6f, 6f); lineTo(18f, 18f)
            }
        }
    }

    val ArrowBack: ImageVector by lazy {
        vector("sf_arrow_back", autoMirror = true) {
            strokePath {
                moveTo(19f, 12f); lineTo(5f, 12f)
                moveTo(11.5f, 5.5f); lineTo(5f, 12f); lineTo(11.5f, 18.5f)
            }
        }
    }

    val ArrowForward: ImageVector by lazy {
        vector("sf_arrow_forward", autoMirror = true) {
            strokePath {
                moveTo(5f, 12f); lineTo(19f, 12f)
                moveTo(12.5f, 5.5f); lineTo(19f, 12f); lineTo(12.5f, 18.5f)
            }
        }
    }

    val ChevronLeft: ImageVector by lazy {
        vector("sf_chevron_left", autoMirror = true) {
            strokePath {
                moveTo(15f, 18f); lineTo(9f, 12f); lineTo(15f, 6f)
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        vector("sf_chevron_right", autoMirror = true) {
            strokePath {
                moveTo(9f, 18f); lineTo(15f, 12f); lineTo(9f, 6f)
            }
        }
    }

    val ChevronUp: ImageVector by lazy {
        vector("sf_chevron_up") {
            strokePath {
                moveTo(18f, 15f); lineTo(12f, 9f); lineTo(6f, 15f)
            }
        }
    }

    val ChevronDown: ImageVector by lazy {
        vector("sf_chevron_down") {
            strokePath {
                moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
            }
        }
    }

    val ChevronsUpDown: ImageVector by lazy {
        vector("sf_chevrons_up_down") {
            strokePath {
                moveTo(7f, 15f); lineTo(12f, 20f); lineTo(17f, 15f)
                moveTo(7f, 9f); lineTo(12f, 4f); lineTo(17f, 9f)
            }
        }
    }

    val NorthWest: ImageVector by lazy {
        vector("sf_north_west", autoMirror = true) {
            strokePath {
                moveTo(17f, 17f); lineTo(7f, 7f)
                moveTo(7f, 15f); lineTo(7f, 7f); lineTo(15f, 7f)
            }
        }
    }

    val OpenInNew: ImageVector by lazy {
        vector("sf_open_in_new", autoMirror = true) {
            strokePath {
                moveTo(15f, 3f); lineTo(21f, 3f); lineTo(21f, 9f)
                moveTo(10f, 14f); lineTo(21f, 3f)
                moveTo(18f, 13f); lineTo(18f, 19f)
                quadTo(18f, 21f, 16f, 21f)
                lineTo(5f, 21f)
                quadTo(3f, 21f, 3f, 19f)
                lineTo(3f, 8f)
                quadTo(3f, 6f, 5f, 6f)
                lineTo(11f, 6f)
            }
        }
    }

    val Sort: ImageVector by lazy {
        vector("sf_sort", autoMirror = true) {
            strokePath {
                moveTo(4f, 6f); lineTo(20f, 6f)
                moveTo(4f, 12f); lineTo(14f, 12f)
                moveTo(4f, 18f); lineTo(9f, 18f)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Playback & Transport
    // -------------------------------------------------------------------------

    val Play: ImageVector by lazy {
        vector("sf_play") {
            strokePath(fill = ink) {
                moveTo(6.8f, 4.8f)
                lineTo(19.2f, 12f)
                lineTo(6.8f, 19.2f)
                close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        vector("sf_pause") {
            strokePath(fill = ink) {
                moveTo(7f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(7f, 19f); close()
                moveTo(14f, 5f); lineTo(17f, 5f); lineTo(17f, 19f); lineTo(14f, 19f); close()
            }
        }
    }

    val PlayCircle: ImageVector by lazy {
        vector("sf_play_circle") {
            strokePath {
                moveTo(12f, 2.5f)
                arcToRelative(9.5f, 9.5f, 0f, true, true, 0f, 19f)
                arcToRelative(9.5f, 9.5f, 0f, true, true, 0f, -19f)
                close()
            }
            strokePath(width = 1.6f, fill = ink) {
                moveTo(10f, 8.6f); lineTo(16f, 12f); lineTo(10f, 15.4f); close()
            }
        }
    }

    val FastForward: ImageVector by lazy {
        vector("sf_fast_forward") {
            strokePath(width = 1.8f, fill = ink) {
                moveTo(4f, 6.5f); lineTo(11.5f, 12f); lineTo(4f, 17.5f); close()
                moveTo(12.5f, 6.5f); lineTo(20f, 12f); lineTo(12.5f, 17.5f); close()
            }
        }
    }

    val FastRewind: ImageVector by lazy {
        vector("sf_fast_rewind") {
            strokePath(width = 1.8f, fill = ink) {
                moveTo(20f, 6.5f); lineTo(12.5f, 12f); lineTo(20f, 17.5f); close()
                moveTo(11.5f, 6.5f); lineTo(4f, 12f); lineTo(11.5f, 17.5f); close()
            }
        }
    }

    val QueueMusic: ImageVector by lazy {
        vector("sf_queue_music", autoMirror = true) {
            strokePath {
                moveTo(3f, 6.5f); lineTo(14f, 6.5f)
                moveTo(3f, 11.5f); lineTo(14f, 11.5f)
                moveTo(3f, 16.5f); lineTo(10f, 16.5f)
                moveTo(16.5f, 17f); lineTo(16.5f, 7.5f); lineTo(21f, 6f)
                moveTo(16.5f, 17f)
                arcToRelative(2.2f, 2.2f, 0f, true, true, -4.4f, 0f)
                arcToRelative(2.2f, 2.2f, 0f, true, true, 4.4f, 0f)
            }
        }
    }

    val PlaylistPlay: ImageVector by lazy {
        vector("sf_playlist_play", autoMirror = true) {
            strokePath {
                moveTo(3f, 6.5f); lineTo(17f, 6.5f)
                moveTo(3f, 11.5f); lineTo(17f, 11.5f)
                moveTo(3f, 16.5f); lineTo(11f, 16.5f)
            }
            strokePath(width = 1.6f, fill = ink) {
                moveTo(15f, 14.5f); lineTo(20.5f, 17.5f); lineTo(15f, 20.5f); close()
            }
        }
    }

    val PlaylistAdd: ImageVector by lazy {
        vector("sf_playlist_add", autoMirror = true) {
            strokePath {
                moveTo(3f, 6.5f); lineTo(15f, 6.5f)
                moveTo(3f, 11.5f); lineTo(15f, 11.5f)
                moveTo(3f, 16.5f); lineTo(10.5f, 16.5f)
                moveTo(18f, 13.5f); lineTo(18f, 20f)
                moveTo(14.75f, 16.75f); lineTo(21.25f, 16.75f)
            }
        }
    }

    val Videocam: ImageVector by lazy {
        vector("sf_videocam") {
            strokePath {
                moveTo(4f, 6.5f)
                lineTo(13.5f, 6.5f)
                quadTo(15.5f, 6.5f, 15.5f, 8.5f)
                lineTo(15.5f, 15.5f)
                quadTo(15.5f, 17.5f, 13.5f, 17.5f)
                lineTo(4f, 17.5f)
                quadTo(2f, 17.5f, 2f, 15.5f)
                lineTo(2f, 8.5f)
                quadTo(2f, 6.5f, 4f, 6.5f)
                close()
                moveTo(15.5f, 10.2f); lineTo(21.5f, 7.2f); lineTo(21.5f, 16.8f); lineTo(15.5f, 13.8f)
            }
        }
    }

    val Bedtime: ImageVector by lazy {
        vector("sf_sleep_timer") {
            strokePath {
                moveTo(20.5f, 14.2f)
                arcToRelative(8.5f, 8.5f, 0f, true, true, -10.7f, -10.7f)
                arcToRelative(7f, 7f, 0f, false, false, 10.7f, 10.7f)
                close()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Audio & Devices
    // -------------------------------------------------------------------------

    val GraphicEq: ImageVector by lazy {
        vector("sf_graphic_eq") {
            strokePath(width = 2.2f) {
                moveTo(4f, 10f); lineTo(4f, 14f)
                moveTo(8f, 6.5f); lineTo(8f, 17.5f)
                moveTo(12f, 3.5f); lineTo(12f, 20.5f)
                moveTo(16f, 7.5f); lineTo(16f, 16.5f)
                moveTo(20f, 10f); lineTo(20f, 14f)
            }
        }
    }

    val Tune: ImageVector by lazy {
        vector("sf_tune") {
            strokePath {
                moveTo(4f, 7f); lineTo(20f, 7f)
                moveTo(4f, 12f); lineTo(20f, 12f)
                moveTo(4f, 17f); lineTo(20f, 17f)
            }
            strokePath(fill = ink) {
                moveTo(9f, 5.2f); lineTo(9f, 8.8f)
                moveTo(15f, 10.2f); lineTo(15f, 13.8f)
                moveTo(10f, 15.2f); lineTo(10f, 18.8f)
            }
        }
    }

    val VolumeUp: ImageVector by lazy {
        vector("sf_volume_up", autoMirror = true) {
            strokePath {
                moveTo(11f, 5f); lineTo(6f, 9f); lineTo(2.5f, 9f); lineTo(2.5f, 15f)
                lineTo(6f, 15f); lineTo(11f, 19f); close()
                moveTo(15.2f, 8.8f)
                arcToRelative(4.5f, 4.5f, 0f, false, true, 0f, 6.4f)
                moveTo(18.2f, 5.8f)
                arcToRelative(8.7f, 8.7f, 0f, false, true, 0f, 12.4f)
            }
        }
    }

    val VolumeDown: ImageVector by lazy {
        vector("sf_volume_down", autoMirror = true) {
            strokePath {
                moveTo(11f, 5f); lineTo(6f, 9f); lineTo(2.5f, 9f); lineTo(2.5f, 15f)
                lineTo(6f, 15f); lineTo(11f, 19f); close()
                moveTo(15.5f, 8.8f)
                arcToRelative(4.5f, 4.5f, 0f, false, true, 0f, 6.4f)
            }
        }
    }

    val VolumeOff: ImageVector by lazy {
        vector("sf_volume_off", autoMirror = true) {
            strokePath {
                moveTo(11f, 5f); lineTo(6f, 9f); lineTo(2.5f, 9f); lineTo(2.5f, 15f)
                lineTo(6f, 15f); lineTo(11f, 19f); close()
                moveTo(21.5f, 9.5f); lineTo(16.5f, 14.5f)
                moveTo(16.5f, 9.5f); lineTo(21.5f, 14.5f)
            }
        }
    }

    val Headphones: ImageVector by lazy {
        vector("sf_headphones") {
            strokePath {
                moveTo(3.5f, 14f)
                lineTo(3.5f, 12f)
                arcToRelative(8.5f, 8.5f, 0f, false, true, 17f, 0f)
                lineTo(20.5f, 14f)
                moveTo(20.5f, 18f)
                quadTo(20.5f, 20f, 18.5f, 20f)
                lineTo(17.5f, 20f)
                quadTo(16f, 20f, 16f, 18.5f)
                lineTo(16f, 14.5f)
                quadTo(16f, 13f, 17.5f, 13f)
                lineTo(20.5f, 13f)
                close()
                moveTo(3.5f, 18f)
                quadTo(3.5f, 20f, 5.5f, 20f)
                lineTo(6.5f, 20f)
                quadTo(8f, 20f, 8f, 18.5f)
                lineTo(8f, 14.5f)
                quadTo(8f, 13f, 6.5f, 13f)
                lineTo(3.5f, 13f)
                close()
            }
        }
    }

    val Speaker: ImageVector by lazy {
        vector("sf_speaker") {
            strokePath {
                moveTo(6.5f, 2.5f); lineTo(17.5f, 2.5f)
                quadTo(19f, 2.5f, 19f, 4f); lineTo(19f, 20f)
                quadTo(19f, 21.5f, 17.5f, 21.5f); lineTo(6.5f, 21.5f)
                quadTo(5f, 21.5f, 5f, 20f); lineTo(5f, 4f)
                quadTo(5f, 2.5f, 6.5f, 2.5f); close()
                moveTo(12f, 10.5f)
                arcToRelative(3.8f, 3.8f, 0f, true, true, 0f, 7.6f)
                arcToRelative(3.8f, 3.8f, 0f, true, true, 0f, -7.6f)
            }
            fillPath {
                moveTo(12f, 5.4f)
                arcToRelative(1.2f, 1.2f, 0f, true, true, 0f, 2.4f)
                arcToRelative(1.2f, 1.2f, 0f, true, true, 0f, -2.4f)
            }
        }
    }

    val Cast: ImageVector by lazy {
        vector("sf_cast") {
            strokePath {
                moveTo(2.5f, 8f)
                lineTo(2.5f, 6f)
                quadTo(2.5f, 4.5f, 4f, 4.5f)
                lineTo(20f, 4.5f)
                quadTo(21.5f, 4.5f, 21.5f, 6f)
                lineTo(21.5f, 18f)
                quadTo(21.5f, 19.5f, 20f, 19.5f)
                lineTo(14.5f, 19.5f)
                moveTo(2.5f, 12f)
                arcToRelative(7.5f, 7.5f, 0f, false, true, 7.5f, 7.5f)
                moveTo(2.5f, 15.8f)
                arcToRelative(3.7f, 3.7f, 0f, false, true, 3.7f, 3.7f)
            }
            fillPath {
                moveTo(2.5f, 18.6f)
                arcToRelative(1.1f, 1.1f, 0f, true, true, 0f, 2.2f)
                arcToRelative(1.1f, 1.1f, 0f, true, true, 0f, -2.2f)
            }
        }
    }

    val CastConnected: ImageVector by lazy {
        vector("sf_cast_connected") {
            strokePath {
                moveTo(2.5f, 8f)
                lineTo(2.5f, 6f)
                quadTo(2.5f, 4.5f, 4f, 4.5f)
                lineTo(20f, 4.5f)
                quadTo(21.5f, 4.5f, 21.5f, 6f)
                lineTo(21.5f, 18f)
                quadTo(21.5f, 19.5f, 20f, 19.5f)
                lineTo(14.5f, 19.5f)
                moveTo(2.5f, 12f)
                arcToRelative(7.5f, 7.5f, 0f, false, true, 7.5f, 7.5f)
                moveTo(2.5f, 15.8f)
                arcToRelative(3.7f, 3.7f, 0f, false, true, 3.7f, 3.7f)
            }
            strokePath(width = 1.6f, fill = ink) {
                moveTo(7f, 8.5f); lineTo(17.5f, 8.5f); lineTo(17.5f, 15.5f); lineTo(13f, 15.5f)
            }
            fillPath {
                moveTo(2.5f, 18.6f)
                arcToRelative(1.1f, 1.1f, 0f, true, true, 0f, 2.2f)
                arcToRelative(1.1f, 1.1f, 0f, true, true, 0f, -2.2f)
            }
        }
    }

    val Bluetooth: ImageVector by lazy {
        vector("sf_bluetooth") {
            strokePath {
                moveTo(7f, 7f); lineTo(17f, 16.5f); lineTo(12f, 21f); lineTo(12f, 3f)
                lineTo(17f, 7.5f); lineTo(7f, 17f)
            }
        }
    }

    val Usb: ImageVector by lazy {
        vector("sf_usb") {
            strokePath {
                moveTo(12f, 21f); lineTo(12f, 4f)
                moveTo(9.5f, 6.5f); lineTo(12f, 3.5f); lineTo(14.5f, 6.5f)
                moveTo(12f, 16f); lineTo(7f, 12.5f); lineTo(7f, 9.5f)
                moveTo(12f, 13.5f); lineTo(17f, 10.5f); lineTo(17f, 8.5f)
                moveTo(15.5f, 7f); lineTo(18.5f, 7f)
            }
            fillPath {
                moveTo(7f, 8.2f)
                arcToRelative(1.3f, 1.3f, 0f, true, true, 0f, 2.6f)
                arcToRelative(1.3f, 1.3f, 0f, true, true, 0f, -2.6f)
                moveTo(12f, 18.6f)
                arcToRelative(1.6f, 1.6f, 0f, true, true, 0f, 3.2f)
                arcToRelative(1.6f, 1.6f, 0f, true, true, 0f, -3.2f)
            }
        }
    }

    val Phone: ImageVector by lazy {
        vector("sf_phone") {
            strokePath {
                moveTo(7.5f, 2.5f); lineTo(16.5f, 2.5f)
                quadTo(18f, 2.5f, 18f, 4f); lineTo(18f, 20f)
                quadTo(18f, 21.5f, 16.5f, 21.5f); lineTo(7.5f, 21.5f)
                quadTo(6f, 21.5f, 6f, 20f); lineTo(6f, 4f)
                quadTo(6f, 2.5f, 7.5f, 2.5f); close()
                moveTo(10.5f, 18f); lineTo(13.5f, 18f)
            }
        }
    }

    val Tv: ImageVector by lazy {
        vector("sf_tv") {
            strokePath {
                moveTo(4f, 4.5f); lineTo(20f, 4.5f)
                quadTo(21.5f, 4.5f, 21.5f, 6f); lineTo(21.5f, 16f)
                quadTo(21.5f, 17.5f, 20f, 17.5f); lineTo(4f, 17.5f)
                quadTo(2.5f, 17.5f, 2.5f, 16f); lineTo(2.5f, 6f)
                quadTo(2.5f, 4.5f, 4f, 4.5f); close()
                moveTo(8.5f, 20.5f); lineTo(15.5f, 20.5f)
                moveTo(12f, 17.5f); lineTo(12f, 20.5f)
            }
        }
    }

    val Watch: ImageVector by lazy {
        vector("sf_watch") {
            strokePath {
                moveTo(8f, 6.5f); lineTo(16f, 6.5f)
                quadTo(17.5f, 6.5f, 17.5f, 8f); lineTo(17.5f, 16f)
                quadTo(17.5f, 17.5f, 16f, 17.5f); lineTo(8f, 17.5f)
                quadTo(6.5f, 17.5f, 6.5f, 16f); lineTo(6.5f, 8f)
                quadTo(6.5f, 6.5f, 8f, 6.5f); close()
                moveTo(9f, 6.5f); lineTo(9.5f, 3f); lineTo(14.5f, 3f); lineTo(15f, 6.5f)
                moveTo(9f, 17.5f); lineTo(9.5f, 21f); lineTo(14.5f, 21f); lineTo(15f, 17.5f)
                moveTo(12f, 9.5f); lineTo(12f, 12f); lineTo(14f, 13.5f)
            }
        }
    }

    val Car: ImageVector by lazy {
        vector("sf_car") {
            strokePath {
                moveTo(4f, 11.5f); lineTo(6f, 6.5f)
                quadTo(6.6f, 5.5f, 8f, 5.5f); lineTo(16f, 5.5f)
                quadTo(17.4f, 5.5f, 18f, 6.5f); lineTo(20f, 11.5f)
                lineTo(20.5f, 11.5f); quadTo(21.5f, 11.5f, 21.5f, 13f)
                lineTo(21.5f, 16.5f); lineTo(2.5f, 16.5f); lineTo(2.5f, 13f)
                quadTo(2.5f, 11.5f, 3.5f, 11.5f); close()
                moveTo(5f, 16.5f); lineTo(5f, 19f); lineTo(7.5f, 19f); lineTo(7.5f, 16.5f)
                moveTo(16.5f, 16.5f); lineTo(16.5f, 19f); lineTo(19f, 19f); lineTo(19f, 16.5f)
                moveTo(6.5f, 14f); lineTo(7.5f, 14f)
                moveTo(16.5f, 14f); lineTo(17.5f, 14f)
            }
        }
    }

    val HighQuality: ImageVector by lazy {
        vector("sf_high_quality") {
            strokePath {
                moveTo(4f, 4.5f); lineTo(20f, 4.5f)
                quadTo(21.5f, 4.5f, 21.5f, 6f); lineTo(21.5f, 18f)
                quadTo(21.5f, 19.5f, 20f, 19.5f); lineTo(4f, 19.5f)
                quadTo(2.5f, 19.5f, 2.5f, 18f); lineTo(2.5f, 6f)
                quadTo(2.5f, 4.5f, 4f, 4.5f); close()
                // H
                moveTo(6.5f, 8.8f); lineTo(6.5f, 15.2f)
                moveTo(10.5f, 8.8f); lineTo(10.5f, 15.2f)
                moveTo(6.5f, 12f); lineTo(10.5f, 12f)
                // Q
                moveTo(15.5f, 8.8f)
                arcToRelative(2.3f, 3.1f, 0f, true, true, 0f, 6.2f)
                arcToRelative(2.3f, 3.1f, 0f, true, true, 0f, -6.2f)
                moveTo(16.2f, 13.8f); lineTo(18f, 15.6f)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Window & Desktop Controls
    // -------------------------------------------------------------------------

    val PictureInPicture: ImageVector by lazy {
        vector("sf_picture_in_picture") {
            strokePath {
                moveTo(4f, 4.5f); lineTo(20f, 4.5f)
                quadTo(21.5f, 4.5f, 21.5f, 6f); lineTo(21.5f, 18f)
                quadTo(21.5f, 19.5f, 20f, 19.5f); lineTo(4f, 19.5f)
                quadTo(2.5f, 19.5f, 2.5f, 18f); lineTo(2.5f, 6f)
                quadTo(2.5f, 4.5f, 4f, 4.5f); close()
            }
            strokePath(width = 1.6f, fill = ink) {
                moveTo(12.5f, 12f); lineTo(18.5f, 12f); lineTo(18.5f, 16.5f); lineTo(12.5f, 16.5f); close()
            }
        }
    }

    val Fullscreen: ImageVector by lazy {
        vector("sf_fullscreen") {
            strokePath {
                moveTo(8f, 3.5f); lineTo(3.5f, 3.5f); lineTo(3.5f, 8f)
                moveTo(16f, 3.5f); lineTo(20.5f, 3.5f); lineTo(20.5f, 8f)
                moveTo(20.5f, 16f); lineTo(20.5f, 20.5f); lineTo(16f, 20.5f)
                moveTo(8f, 20.5f); lineTo(3.5f, 20.5f); lineTo(3.5f, 16f)
            }
        }
    }

    val FullscreenExit: ImageVector by lazy {
        vector("sf_fullscreen_exit") {
            strokePath {
                moveTo(3.5f, 8.5f); lineTo(8.5f, 8.5f); lineTo(8.5f, 3.5f)
                moveTo(20.5f, 8.5f); lineTo(15.5f, 8.5f); lineTo(15.5f, 3.5f)
                moveTo(15.5f, 20.5f); lineTo(15.5f, 15.5f); lineTo(20.5f, 15.5f)
                moveTo(8.5f, 20.5f); lineTo(8.5f, 15.5f); lineTo(3.5f, 15.5f)
            }
        }
    }

    val OpenInFull: ImageVector by lazy {
        vector("sf_open_in_full") {
            strokePath {
                moveTo(15f, 3.5f); lineTo(20.5f, 3.5f); lineTo(20.5f, 9f)
                moveTo(20.5f, 3.5f); lineTo(14f, 10f)
                moveTo(9f, 20.5f); lineTo(3.5f, 20.5f); lineTo(3.5f, 15f)
                moveTo(3.5f, 20.5f); lineTo(10f, 14f)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Actions, Editing & Menus
    // -------------------------------------------------------------------------

    val MoreHorizontal: ImageVector by lazy {
        vector("sf_more_horizontal") {
            fillPath {
                moveTo(5.5f, 10.6f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, 2.8f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, -2.8f)
                moveTo(12f, 10.6f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, 2.8f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, -2.8f)
                moveTo(18.5f, 10.6f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, 2.8f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, -2.8f)
            }
        }
    }

    val MoreVertical: ImageVector by lazy {
        vector("sf_more_vertical") {
            fillPath {
                moveTo(12f, 4.1f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, 2.8f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, -2.8f)
                moveTo(12f, 10.6f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, 2.8f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, -2.8f)
                moveTo(12f, 17.1f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, 2.8f); arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, -2.8f)
            }
        }
    }

    val DragHandle: ImageVector by lazy {
        vector("sf_drag_handle") {
            strokePath {
                moveTo(4.5f, 9f); lineTo(19.5f, 9f)
                moveTo(4.5f, 15f); lineTo(19.5f, 15f)
            }
        }
    }

    val Share: ImageVector by lazy {
        vector("sf_share") {
            strokePath {
                moveTo(12f, 15f); lineTo(12f, 3f)
                moveTo(7.5f, 7.5f); lineTo(12f, 3f); lineTo(16.5f, 7.5f)
                moveTo(7f, 10f); lineTo(5.8f, 10f)
                curveTo(4.4f, 10f, 4f, 11f, 4f, 12.5f)
                lineTo(4f, 19f)
                curveTo(4f, 20.5f, 4.8f, 21f, 6.2f, 21f)
                lineTo(17.8f, 21f)
                curveTo(19.2f, 21f, 20f, 20.5f, 20f, 19f)
                lineTo(20f, 12.5f)
                curveTo(20f, 11f, 19.6f, 10f, 18.2f, 10f)
                lineTo(17f, 10f)
            }
        }
    }

    val Link: ImageVector by lazy {
        vector("sf_link") {
            strokePath {
                moveTo(10f, 13f)
                arcToRelative(5f, 5f, 0f, false, false, 7.54f, 0.54f)
                lineTo(20.54f, 10.54f)
                arcToRelative(5f, 5f, 0f, false, false, -7.07f, -7.07f)
                lineTo(11.75f, 5.18f)
                moveTo(14f, 11f)
                arcToRelative(5f, 5f, 0f, false, false, -7.54f, -0.54f)
                lineTo(3.46f, 13.46f)
                arcToRelative(5f, 5f, 0f, false, false, 7.07f, 7.07f)
                lineTo(12.24f, 18.82f)
            }
        }
    }

    val Copy: ImageVector by lazy {
        vector("sf_copy") {
            strokePath {
                moveTo(10f, 8f); lineTo(19f, 8f)
                quadTo(21f, 8f, 21f, 10f); lineTo(21f, 19f)
                quadTo(21f, 21f, 19f, 21f); lineTo(10f, 21f)
                quadTo(8f, 21f, 8f, 19f); lineTo(8f, 10f)
                quadTo(8f, 8f, 10f, 8f); close()
                moveTo(4f, 16f); quadTo(3f, 16f, 3f, 15f)
                lineTo(3f, 5f); quadTo(3f, 3f, 5f, 3f)
                lineTo(15f, 3f); quadTo(16f, 3f, 16f, 4f)
            }
        }
    }

    val Paste: ImageVector by lazy {
        vector("sf_paste") {
            strokePath {
                moveTo(9f, 4f); lineTo(15f, 4f)
                quadTo(16f, 4f, 16f, 5f); lineTo(16f, 6f); lineTo(8f, 6f); lineTo(8f, 5f)
                quadTo(8f, 4f, 9f, 4f); close()
                moveTo(16f, 5f); lineTo(18f, 5f)
                quadTo(19.5f, 5f, 19.5f, 6.5f); lineTo(19.5f, 19.5f)
                quadTo(19.5f, 21f, 18f, 21f); lineTo(6f, 21f)
                quadTo(4.5f, 21f, 4.5f, 19.5f); lineTo(4.5f, 6.5f)
                quadTo(4.5f, 5f, 6f, 5f); lineTo(8f, 5f)
                moveTo(8.5f, 11.5f); lineTo(15.5f, 11.5f)
                moveTo(8.5f, 15.5f); lineTo(13.5f, 15.5f)
            }
        }
    }

    val Edit: ImageVector by lazy {
        vector("sf_edit") {
            strokePath {
                moveTo(16.5f, 3.8f)
                arcToRelative(2.1f, 2.1f, 0f, false, true, 3f, 3f)
                lineTo(7.8f, 18.5f)
                lineTo(3.5f, 20.5f)
                lineTo(5.5f, 16.2f)
                close()
                moveTo(14.5f, 5.8f); lineTo(17.5f, 8.8f)
            }
        }
    }

    val Delete: ImageVector by lazy {
        vector("sf_delete") {
            strokePath {
                moveTo(4f, 6.5f); lineTo(20f, 6.5f)
                moveTo(9f, 6.5f); lineTo(9.8f, 3.8f); lineTo(14.2f, 3.8f); lineTo(15f, 6.5f)
                moveTo(6.5f, 6.5f); lineTo(7.4f, 19f)
                quadTo(7.6f, 20.5f, 9.2f, 20.5f); lineTo(14.8f, 20.5f)
                quadTo(16.4f, 20.5f, 16.6f, 19f); lineTo(17.5f, 6.5f)
                moveTo(10.2f, 10.5f); lineTo(10.5f, 16.8f)
                moveTo(13.8f, 10.5f); lineTo(13.5f, 16.8f)
            }
        }
    }

    val DeleteSweep: ImageVector by lazy {
        vector("sf_delete_sweep") {
            strokePath {
                moveTo(3f, 6.5f); lineTo(14.5f, 6.5f)
                moveTo(6.5f, 6.5f); lineTo(7.2f, 4f); lineTo(10.3f, 4f); lineTo(11f, 6.5f)
                moveTo(4.5f, 6.5f); lineTo(5.2f, 18.8f)
                quadTo(5.4f, 20.2f, 6.8f, 20.2f); lineTo(10.7f, 20.2f)
                quadTo(12.1f, 20.2f, 12.3f, 18.8f); lineTo(13f, 6.5f)
                moveTo(15.5f, 10f); lineTo(21f, 10f)
                moveTo(15.5f, 14f); lineTo(19.5f, 14f)
                moveTo(15.5f, 18f); lineTo(18f, 18f)
            }
        }
    }

    val Refresh: ImageVector by lazy {
        vector("sf_refresh") {
            strokePath {
                moveTo(20.5f, 12f)
                arcToRelative(8.5f, 8.5f, 0f, true, true, -2.5f, -6f)
                lineTo(20.5f, 8.5f)
                moveTo(20.5f, 3.8f); lineTo(20.5f, 8.5f); lineTo(15.8f, 8.5f)
            }
        }
    }

    val Cached: ImageVector by lazy {
        vector("sf_cached") {
            strokePath {
                moveTo(19.5f, 9f)
                arcToRelative(8f, 8f, 0f, false, false, -14.5f, -1.5f)
                moveTo(4.5f, 15f)
                arcToRelative(8f, 8f, 0f, false, false, 14.5f, 1.5f)
                moveTo(16f, 9f); lineTo(20f, 9f); lineTo(20f, 5f)
                moveTo(8f, 15f); lineTo(4f, 15f); lineTo(4f, 19f)
            }
        }
    }

    val Undo: ImageVector by lazy {
        vector("sf_undo", autoMirror = true) {
            strokePath {
                moveTo(4.5f, 9f); lineTo(14f, 9f)
                arcToRelative(5.5f, 5.5f, 0f, false, true, 0f, 11f)
                lineTo(11f, 20f)
                moveTo(8.5f, 5f); lineTo(4.5f, 9f); lineTo(8.5f, 13f)
            }
        }
    }

    val DownloadDone: ImageVector by lazy {
        vector("sf_download_done") {
            strokePath {
                moveTo(12f, 2.5f)
                arcToRelative(9.5f, 9.5f, 0f, true, true, 0f, 19f)
                arcToRelative(9.5f, 9.5f, 0f, true, true, 0f, -19f)
                close()
                moveTo(8.2f, 12.2f); lineTo(10.8f, 14.8f); lineTo(15.8f, 9.4f)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Settings, Account & System
    // -------------------------------------------------------------------------

    val Settings: ImageVector by lazy {
        vector("sf_settings") {
            strokePath {
                moveTo(12f, 15f)
                arcToRelative(3f, 3f, 0f, true, true, 0f, -6f)
                arcToRelative(3f, 3f, 0f, true, true, 0f, 6f)
                close()
                moveTo(19.4f, 15f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 0.33f, 1.82f)
                lineToRelative(0.06f, 0.06f)
                arcToRelative(2f, 2f, 0f, false, true, -2.83f, 2.83f)
                lineToRelative(-0.06f, -0.06f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1.82f, -0.33f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1f, 1.51f)
                verticalLineTo(21f)
                arcToRelative(2f, 2f, 0f, false, true, -4f, 0f)
                verticalLineToRelative(-0.09f)
                arcTo(1.65f, 1.65f, 0f, false, false, 9f, 19.4f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1.82f, 0.33f)
                lineToRelative(-0.06f, 0.06f)
                arcToRelative(2f, 2f, 0f, false, true, -2.83f, -2.83f)
                lineToRelative(0.06f, -0.06f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 0.33f, -1.82f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1.51f, -1f)
                horizontalLineTo(3f)
                arcToRelative(2f, 2f, 0f, false, true, 0f, -4f)
                horizontalLineToRelative(0.09f)
                arcTo(1.65f, 1.65f, 0f, false, false, 4.6f, 9f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -0.33f, -1.82f)
                lineToRelative(-0.06f, -0.06f)
                arcToRelative(2f, 2f, 0f, false, true, 2.83f, -2.83f)
                lineToRelative(0.06f, 0.06f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 1.82f, 0.33f)
                horizontalLineTo(9f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 1f, -1.51f)
                verticalLineTo(3f)
                arcToRelative(2f, 2f, 0f, false, true, 4f, 0f)
                verticalLineToRelative(0.09f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 1f, 1.51f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 1.82f, -0.33f)
                lineToRelative(0.06f, -0.06f)
                arcToRelative(2f, 2f, 0f, false, true, 2.83f, 2.83f)
                lineToRelative(-0.06f, 0.06f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -0.33f, 1.82f)
                verticalLineTo(9f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 1.51f, 1f)
                horizontalLineTo(21f)
                arcToRelative(2f, 2f, 0f, false, true, 0f, 4f)
                horizontalLineToRelative(-0.09f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1.51f, 1f)
                close()
            }
        }
    }

    val Person: ImageVector by lazy {
        vector("sf_person") {
            strokePath {
                moveTo(12f, 4f)
                arcToRelative(3.6f, 3.6f, 0f, true, true, 0f, 7.2f)
                arcToRelative(3.6f, 3.6f, 0f, true, true, 0f, -7.2f)
                moveTo(5f, 20f)
                curveTo(5f, 16.4f, 8.1f, 14.5f, 12f, 14.5f)
                curveTo(15.9f, 14.5f, 19f, 16.4f, 19f, 20f)
            }
        }
    }

    val ManageAccounts: ImageVector by lazy {
        vector("sf_manage_accounts") {
            strokePath {
                moveTo(10f, 4.5f)
                arcToRelative(3.3f, 3.3f, 0f, true, true, 0f, 6.6f)
                arcToRelative(3.3f, 3.3f, 0f, true, true, 0f, -6.6f)
                moveTo(3.5f, 19.5f)
                curveTo(3.5f, 16.3f, 6.3f, 14.5f, 10f, 14.5f)
                curveTo(11.6f, 14.5f, 13f, 14.8f, 14.1f, 15.4f)
                moveTo(17.5f, 14.5f)
                arcToRelative(2.2f, 2.2f, 0f, true, true, 0f, 4.4f)
                arcToRelative(2.2f, 2.2f, 0f, true, true, 0f, -4.4f)
                moveTo(17.5f, 13.2f); lineTo(17.5f, 14.3f)
                moveTo(17.5f, 19.1f); lineTo(17.5f, 20.2f)
                moveTo(14f, 16.7f); lineTo(15.1f, 16.7f)
                moveTo(19.9f, 16.7f); lineTo(21f, 16.7f)
            }
        }
    }

    val Groups: ImageVector by lazy {
        vector("sf_groups") {
            strokePath {
                moveTo(16f, 20.5f)
                curveTo(16f, 17.5f, 13.5f, 15.5f, 9.5f, 15.5f)
                curveTo(5.5f, 15.5f, 3f, 17.5f, 3f, 20.5f)
                moveTo(9.5f, 5f)
                arcToRelative(3.5f, 3.5f, 0f, true, true, 0f, 7f)
                arcToRelative(3.5f, 3.5f, 0f, true, true, 0f, -7f)
                moveTo(21f, 20.5f)
                curveTo(21f, 18.2f, 19.5f, 16.5f, 17.2f, 16f)
                moveTo(15.5f, 5.5f)
                arcToRelative(3f, 3f, 0f, false, true, 0f, 6f)
            }
        }
    }

    val Folder: ImageVector by lazy {
        vector("sf_folder") {
            strokePath {
                moveTo(3f, 6.5f)
                quadTo(3f, 5f, 4.5f, 5f)
                lineTo(9.5f, 5f)
                lineTo(11.5f, 7.5f)
                lineTo(19.5f, 7.5f)
                quadTo(21f, 7.5f, 21f, 9f)
                lineTo(21f, 18f)
                quadTo(21f, 19.5f, 19.5f, 19.5f)
                lineTo(4.5f, 19.5f)
                quadTo(3f, 19.5f, 3f, 18f)
                close()
            }
        }
    }

    val Storage: ImageVector by lazy {
        vector("sf_storage") {
            strokePath {
                moveTo(3.5f, 5f); lineTo(20.5f, 5f)
                quadTo(21.5f, 5f, 21.5f, 6f); lineTo(21.5f, 9.5f)
                quadTo(21.5f, 10.5f, 20.5f, 10.5f); lineTo(3.5f, 10.5f)
                quadTo(2.5f, 10.5f, 2.5f, 9.5f); lineTo(2.5f, 6f)
                quadTo(2.5f, 5f, 3.5f, 5f); close()
                moveTo(3.5f, 13.5f); lineTo(20.5f, 13.5f)
                quadTo(21.5f, 13.5f, 21.5f, 14.5f); lineTo(21.5f, 18f)
                quadTo(21.5f, 19f, 20.5f, 19f); lineTo(3.5f, 19f)
                quadTo(2.5f, 19f, 2.5f, 18f); lineTo(2.5f, 14.5f)
                quadTo(2.5f, 13.5f, 3.5f, 13.5f); close()
            }
            fillPath {
                moveTo(6f, 6.8f); arcToRelative(1f, 1f, 0f, true, true, 0f, 2f); arcToRelative(1f, 1f, 0f, true, true, 0f, -2f)
                moveTo(6f, 15.3f); arcToRelative(1f, 1f, 0f, true, true, 0f, 2f); arcToRelative(1f, 1f, 0f, true, true, 0f, -2f)
            }
        }
    }

    val Language: ImageVector by lazy {
        vector("sf_language") {
            strokePath {
                moveTo(12f, 2.5f)
                arcToRelative(9.5f, 9.5f, 0f, true, true, 0f, 19f)
                arcToRelative(9.5f, 9.5f, 0f, true, true, 0f, -19f)
                close()
                moveTo(2.5f, 12f); lineTo(21.5f, 12f)
                moveTo(12f, 2.5f)
                arcToRelative(14f, 14f, 0f, false, true, 3.8f, 9.5f)
                arcToRelative(14f, 14f, 0f, false, true, -3.8f, 9.5f)
                arcToRelative(14f, 14f, 0f, false, true, -3.8f, -9.5f)
                arcToRelative(14f, 14f, 0f, false, true, 3.8f, -9.5f)
                close()
            }
        }
    }

    val Translate: ImageVector by lazy {
        vector("sf_translate") {
            strokePath {
                moveTo(4f, 6f); lineTo(12f, 6f)
                moveTo(8f, 4f); lineTo(8f, 6f)
                moveTo(10.5f, 6f); curveTo(10f, 9.8f, 7.5f, 12.5f, 4.5f, 14f)
                moveTo(6f, 9.5f); curveTo(7.2f, 11.2f, 8.8f, 12.5f, 10.5f, 13.5f)
                moveTo(13.5f, 19.5f); lineTo(17f, 10.5f); lineTo(20.5f, 19.5f)
                moveTo(14.7f, 16.8f); lineTo(19.3f, 16.8f)
            }
        }
    }

    val Extension: ImageVector by lazy {
        vector("sf_extension") {
            strokePath {
                moveTo(5.5f, 7.5f); lineTo(9.5f, 7.5f)
                arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
                lineTo(17.5f, 7.5f)
                quadTo(19f, 7.5f, 19f, 9f)
                lineTo(19f, 11.5f)
                arcToRelative(2f, 2f, 0f, true, true, 0f, 4f)
                lineTo(19f, 18.5f)
                quadTo(19f, 20f, 17.5f, 20f)
                lineTo(5.5f, 20f)
                quadTo(4f, 20f, 4f, 18.5f)
                lineTo(4f, 9f)
                quadTo(4f, 7.5f, 5.5f, 7.5f)
                close()
            }
        }
    }

    val Lock: ImageVector by lazy {
        vector("sf_lock") {
            strokePath {
                moveTo(6.5f, 10.5f); lineTo(17.5f, 10.5f)
                quadTo(19f, 10.5f, 19f, 12f); lineTo(19f, 19f)
                quadTo(19f, 20.5f, 17.5f, 20.5f); lineTo(6.5f, 20.5f)
                quadTo(5f, 20.5f, 5f, 19f); lineTo(5f, 12f)
                quadTo(5f, 10.5f, 6.5f, 10.5f); close()
                moveTo(8f, 10.5f); lineTo(8f, 7.5f)
                arcToRelative(4f, 4f, 0f, false, true, 8f, 0f)
                lineTo(16f, 10.5f)
            }
        }
    }

    val LockOpen: ImageVector by lazy {
        vector("sf_lock_open") {
            strokePath {
                moveTo(6.5f, 10.5f); lineTo(17.5f, 10.5f)
                quadTo(19f, 10.5f, 19f, 12f); lineTo(19f, 19f)
                quadTo(19f, 20.5f, 17.5f, 20.5f); lineTo(6.5f, 20.5f)
                quadTo(5f, 20.5f, 5f, 19f); lineTo(5f, 12f)
                quadTo(5f, 10.5f, 6.5f, 10.5f); close()
                moveTo(8f, 10.5f); lineTo(8f, 7.5f)
                arcToRelative(4f, 4f, 0f, false, true, 7.6f, -1.7f)
            }
        }
    }

    val VisibilityOff: ImageVector by lazy {
        vector("sf_visibility_off") {
            strokePath {
                moveTo(3f, 3f); lineTo(21f, 21f)
                moveTo(10.6f, 10.6f); arcToRelative(2.2f, 2.2f, 0f, false, false, 2.8f, 2.8f)
                moveTo(6.7f, 6.7f); curveTo(4.7f, 8f, 3.2f, 9.8f, 2.5f, 12f)
                curveTo(4.1f, 16.1f, 7.8f, 18.5f, 12f, 18.5f)
                curveTo(13.8f, 18.5f, 15.5f, 18f, 16.9f, 17.1f)
                moveTo(9.8f, 5.7f); curveTo(10.5f, 5.5f, 11.2f, 5.5f, 12f, 5.5f)
                curveTo(16.2f, 5.5f, 19.9f, 7.9f, 21.5f, 12f)
                curveTo(21f, 13.2f, 20.3f, 14.3f, 19.4f, 15.2f)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Status, Feedback & Music Indicators
    // -------------------------------------------------------------------------

    val Info: ImageVector by lazy {
        vector("sf_info") {
            strokePath {
                moveTo(12f, 2.5f)
                arcToRelative(9.5f, 9.5f, 0f, true, true, 0f, 19f)
                arcToRelative(9.5f, 9.5f, 0f, true, true, 0f, -19f)
                close()
                moveTo(12f, 10.8f); lineTo(12f, 16.5f)
            }
            fillPath {
                moveTo(12f, 6.7f)
                arcToRelative(1.1f, 1.1f, 0f, true, true, 0f, 2.2f)
                arcToRelative(1.1f, 1.1f, 0f, true, true, 0f, -2.2f)
            }
        }
    }

    val Error: ImageVector by lazy {
        vector("sf_error") {
            strokePath {
                moveTo(12f, 2.5f)
                arcToRelative(9.5f, 9.5f, 0f, true, true, 0f, 19f)
                arcToRelative(9.5f, 9.5f, 0f, true, true, 0f, -19f)
                close()
                moveTo(12f, 7.8f); lineTo(12f, 12.8f)
            }
            fillPath {
                moveTo(12f, 15.2f)
                arcToRelative(1.1f, 1.1f, 0f, true, true, 0f, 2.2f)
                arcToRelative(1.1f, 1.1f, 0f, true, true, 0f, -2.2f)
            }
        }
    }

    val CheckCircle: ImageVector by lazy {
        vector("sf_check_circle") {
            strokePath {
                moveTo(12f, 2.5f)
                arcToRelative(9.5f, 9.5f, 0f, true, true, 0f, 19f)
                arcToRelative(9.5f, 9.5f, 0f, true, true, 0f, -19f)
                close()
                moveTo(8.3f, 12.3f); lineTo(10.8f, 14.8f); lineTo(15.8f, 9.5f)
            }
        }
    }

    val RemoveCircle: ImageVector by lazy {
        vector("sf_remove_circle") {
            strokePath {
                moveTo(12f, 2.5f)
                arcToRelative(9.5f, 9.5f, 0f, true, true, 0f, 19f)
                arcToRelative(9.5f, 9.5f, 0f, true, true, 0f, -19f)
                close()
                moveTo(8.2f, 12f); lineTo(15.8f, 12f)
            }
        }
    }

    val Sparkles: ImageVector by lazy {
        vector("sf_sparkles") {
            strokePath(width = 1.8f) {
                moveTo(10.5f, 3.5f)
                lineTo(12.2f, 8.8f); lineTo(17.5f, 10.5f)
                lineTo(12.2f, 12.2f); lineTo(10.5f, 17.5f)
                lineTo(8.8f, 12.2f); lineTo(3.5f, 10.5f)
                lineTo(8.8f, 8.8f); close()
                moveTo(18f, 15f); lineTo(18f, 20f)
                moveTo(15.5f, 17.5f); lineTo(20.5f, 17.5f)
            }
        }
    }

    val LibraryMusic: ImageVector by lazy {
        vector("sf_library_music") {
            strokePath {
                moveTo(4.5f, 6.5f); lineTo(4.5f, 18f)
                quadTo(4.5f, 20f, 6.5f, 20f); lineTo(18f, 20f)
                moveTo(9f, 4f); lineTo(19f, 4f)
                quadTo(20.5f, 4f, 20.5f, 5.5f); lineTo(20.5f, 15f)
                quadTo(20.5f, 16.5f, 19f, 16.5f); lineTo(9f, 16.5f)
                quadTo(7.5f, 16.5f, 7.5f, 15f); lineTo(7.5f, 5.5f)
                quadTo(7.5f, 4f, 9f, 4f); close()
                moveTo(13.5f, 13f); lineTo(13.5f, 7.5f); lineTo(16.5f, 6.8f)
                moveTo(13.5f, 13f)
                arcToRelative(1.4f, 1.4f, 0f, true, true, -2.8f, 0f)
                arcToRelative(1.4f, 1.4f, 0f, true, true, 2.8f, 0f)
            }
        }
    }
}
