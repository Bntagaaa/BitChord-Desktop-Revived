package com.music.bitchord.desktop.icons

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.desktop.DesktopPhoneMenuIcons
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.icons.SfStyleIcons

/**
 * Centralized semantic icon registry for BitChord Desktop.
 *
 * Maps every desktop semantic action to a single canonical vector from:
 * - [BitChordIcons] (Tier 2: original upstream BitChord iconography)
 * - [SfStyleIcons] (Tier 1/3/4: SF-style & Lucide-derived Compose vectors)
 * - [DesktopPhoneMenuIcons] (Tier 2/4: desktop-native context menu vectors)
 *
 * Direct `androidx.compose.material.icons` usage is prohibited in migrated desktop UI.
 */
object DesktopIcons {

    /** Optical size tokens matching the desktop visual hierarchy. */
    object Size {
        val Status: Dp = 14.dp
        val Compact: Dp = 16.dp
        val Menu: Dp = 18.dp
        val Toolbar: Dp = 20.dp
        val Navigation: Dp = 22.dp
        val Standard: Dp = 24.dp
    }

    object Navigation {
        val Home: ImageVector get() = BitChordIcons.Home
        val New: ImageVector get() = BitChordIcons.TabExplore
        val Search: ImageVector get() = BitChordIcons.TabSearch
        val SearchField: ImageVector get() = BitChordIcons.Search
        val Radio: ImageVector get() = BitChordIcons.TabRadio
        val Library: ImageVector get() = BitChordIcons.TabLibrary
        val LibraryOutline: ImageVector get() = BitChordIcons.Library
        val Explore: ImageVector get() = BitChordIcons.Explore
        val ChevronRight: ImageVector get() = SfStyleIcons.ChevronRight
        val ChevronLeft: ImageVector get() = SfStyleIcons.ChevronLeft
        val ArrowBack: ImageVector get() = SfStyleIcons.ArrowBack
        val ArrowForward: ImageVector get() = SfStyleIcons.ArrowForward
        val NorthWest: ImageVector get() = SfStyleIcons.NorthWest
        val OpenInNew: ImageVector get() = SfStyleIcons.OpenInNew
        val OpenInFull: ImageVector get() = SfStyleIcons.OpenInFull
        val GridView: ImageVector get() = BitChordIcons.GridView
        val ListView: ImageVector get() = BitChordIcons.ListView
        val Sort: ImageVector get() = SfStyleIcons.Sort
        val Menu: ImageVector get() = SfStyleIcons.ChevronsUpDown
    }

    object Player {
        val Play: ImageVector get() = SfStyleIcons.Play
        val Pause: ImageVector get() = SfStyleIcons.Pause
        val PlayCircle: ImageVector get() = SfStyleIcons.PlayCircle
        val FastForward: ImageVector get() = SfStyleIcons.FastForward
        val FastRewind: ImageVector get() = SfStyleIcons.FastRewind
        val Shuffle: ImageVector get() = BitChordIcons.Shuffle
        val Repeat: ImageVector get() = BitChordIcons.Repeat
        val RepeatOne: ImageVector get() = BitChordIcons.RepeatOne
        val Infinity: ImageVector get() = BitChordIcons.Infinity
        val Queue: ImageVector get() = BitChordIcons.Queue
        val QueueMusic: ImageVector get() = SfStyleIcons.QueueMusic
        val Lyrics: ImageVector get() = SfStyleIcons.Notes
        val LyricsOutline: ImageVector get() = BitChordIcons.Lyrics
        val Favorite: ImageVector get() = BitChordIcons.Heart
        val FavoriteFilled: ImageVector get() = BitChordIcons.HeartFilled
        val Like: ImageVector get() = SfStyleIcons.Star
        val LikeFilled: ImageVector get() = SfStyleIcons.StarFilled
        val Dislike: ImageVector get() = DesktopPhoneMenuIcons.BrokenHeart
        val DislikeFilled: ImageVector get() = DesktopPhoneMenuIcons.BrokenHeartFilled
        val MusicNote: ImageVector get() = BitChordIcons.MusicNote
        val MusicVideo: ImageVector get() = SfStyleIcons.Videocam
        val SleepTimer: ImageVector get() = SfStyleIcons.SleepTimer
        val StartRadio: ImageVector get() = SfStyleIcons.Radio
    }

    object Actions {
        val Share: ImageVector get() = SfStyleIcons.Share
        val Download: ImageVector get() = SfStyleIcons.Download
        val Downloaded: ImageVector get() = SfStyleIcons.DownloadDone
        val Delete: ImageVector get() = SfStyleIcons.Delete
        val DeleteSweep: ImageVector get() = SfStyleIcons.DeleteSweep
        val Edit: ImageVector get() = SfStyleIcons.Edit
        val Add: ImageVector get() = BitChordIcons.Plus
        val AddToPlaylist: ImageVector get() = SfStyleIcons.PlaylistAdd
        val RemoveFromPlaylist: ImageVector get() = SfStyleIcons.RemoveCircle
        val PlaylistPlay: ImageVector get() = SfStyleIcons.PlaylistPlay
        val PlaylistAdd: ImageVector get() = SfStyleIcons.PlaylistAdd
        val PlayNext: ImageVector get() = SfStyleIcons.PlayNext
        val PlayLast: ImageVector get() = SfStyleIcons.PlayLast
        val Pin: ImageVector get() = BitChordIcons.Pin
        val More: ImageVector get() = SfStyleIcons.MoreHorizontal
        val MoreVertical: ImageVector get() = SfStyleIcons.MoreVertical
        val Copy: ImageVector get() = SfStyleIcons.Copy
        val Paste: ImageVector get() = SfStyleIcons.Paste
        val Refresh: ImageVector get() = SfStyleIcons.Refresh
        val Undo: ImageVector get() = SfStyleIcons.Undo
        val Close: ImageVector get() = SfStyleIcons.Close
        val Check: ImageVector get() = BitChordIcons.Check
        val CheckCircle: ImageVector get() = SfStyleIcons.CheckCircle
        val RemoveCircle: ImageVector get() = SfStyleIcons.RemoveCircle
        val Link: ImageVector get() = SfStyleIcons.Link
        val DragHandle: ImageVector get() = SfStyleIcons.DragHandle
        val ViewArtist: ImageVector get() = SfStyleIcons.Artist
        val ViewAlbum: ImageVector get() = SfStyleIcons.Album
    }

    object Audio {
        val AirPlay: ImageVector get() = BitChordIcons.AirPlay
        val Headphones: ImageVector get() = SfStyleIcons.Headphones
        val Speaker: ImageVector get() = SfStyleIcons.Speaker
        val Cast: ImageVector get() = SfStyleIcons.Cast
        val CastConnected: ImageVector get() = SfStyleIcons.CastConnected
        val Bluetooth: ImageVector get() = SfStyleIcons.Bluetooth
        val Usb: ImageVector get() = SfStyleIcons.Usb
        val VolumeUp: ImageVector get() = SfStyleIcons.VolumeUp
        val VolumeDown: ImageVector get() = SfStyleIcons.VolumeDown
        val VolumeOff: ImageVector get() = SfStyleIcons.VolumeOff
        val GraphicEq: ImageVector get() = SfStyleIcons.GraphicEq
        val Equalizer: ImageVector get() = SfStyleIcons.Tune
        val HighQuality: ImageVector get() = SfStyleIcons.HighQuality
        val SurroundSound: ImageVector get() = SfStyleIcons.SurroundSound
        val Phone: ImageVector get() = SfStyleIcons.Phone
        val Tv: ImageVector get() = SfStyleIcons.Tv
        val Watch: ImageVector get() = SfStyleIcons.Watch
        val Car: ImageVector get() = SfStyleIcons.Car
    }

    object Window {
        val PictureInPicture: ImageVector get() = SfStyleIcons.PictureInPicture
        val Fullscreen: ImageVector get() = SfStyleIcons.Fullscreen
        val ExitFullscreen: ImageVector get() = SfStyleIcons.FullscreenExit
        val ExpandLess: ImageVector get() = SfStyleIcons.ChevronUp
        val ExpandMore: ImageVector get() = SfStyleIcons.ChevronDown
    }

    object Settings {
        val General: ImageVector get() = SfStyleIcons.Settings
        val Tune: ImageVector get() = SfStyleIcons.Tune
        val Account: ImageVector get() = SfStyleIcons.Person
        val ManageAccounts: ImageVector get() = SfStyleIcons.ManageAccounts
        val Folder: ImageVector get() = SfStyleIcons.Folder
        val Storage: ImageVector get() = SfStyleIcons.Storage
        val Cached: ImageVector get() = SfStyleIcons.Cached
        val Language: ImageVector get() = SfStyleIcons.Language
        val Translate: ImageVector get() = SfStyleIcons.Translate
        val Extension: ImageVector get() = SfStyleIcons.Extension
        val Lock: ImageVector get() = SfStyleIcons.Lock
        val LockOpen: ImageVector get() = SfStyleIcons.LockOpen
        val VisibilityOff: ImageVector get() = SfStyleIcons.VisibilityOff
        val Performance: ImageVector get() = BitChordIcons.Performance
        val FrameRate: ImageVector get() = BitChordIcons.FrameRate
        val Bug: ImageVector get() = SfStyleIcons.Bug
        val Upgrade: ImageVector get() = SfStyleIcons.Upgrade
        val Appearance: ImageVector get() = SfStyleIcons.Appearance
        val Wifi: ImageVector get() = SfStyleIcons.Wifi
        val Sliders: ImageVector get() = SfStyleIcons.Sliders
        val Font: ImageVector get() = SfStyleIcons.Font
        val BlurOn: ImageVector get() = SfStyleIcons.BlurOn
        val BarChart: ImageVector get() = SfStyleIcons.BarChart
        val History: ImageVector get() = SfStyleIcons.History
        val Brightness: ImageVector get() = SfStyleIcons.Brightness
        val Animation: ImageVector get() = SfStyleIcons.Animation
        val Miscellaneous: ImageVector get() = SfStyleIcons.Miscellaneous
    }

    object Status {
        val Info: ImageVector get() = SfStyleIcons.Info
        val Error: ImageVector get() = SfStyleIcons.Error
        val Groups: ImageVector get() = SfStyleIcons.Groups
        val LibraryMusic: ImageVector get() = SfStyleIcons.LibraryMusic
        val Schedule: ImageVector get() = BitChordIcons.Clock
        val Sparkles: ImageVector get() = SfStyleIcons.Sparkles
        val Instrumental: ImageVector get() = BitChordIcons.Instrumental
    }
}

/**
 * Reusable desktop icon composable with consistent sizing, tint, and disabled alpha.
 */
@Composable
fun DesktopIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = DesktopIcons.Size.Standard,
    tint: Color = LocalContentColor.current,
    enabled: Boolean = true,
    alpha: Float = 1f,
) {
    val effectiveAlpha = if (enabled) alpha else (alpha * 0.38f)
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = if (effectiveAlpha < 1f) tint.copy(alpha = tint.alpha * effectiveAlpha) else tint,
        modifier = modifier.size(size),
    )
}

/**
 * Reusable desktop icon composable for [Painter] resources (e.g. brand marks or transport SVGs).
 */
@Composable
fun DesktopIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = DesktopIcons.Size.Standard,
    tint: Color = LocalContentColor.current,
    enabled: Boolean = true,
    alpha: Float = 1f,
) {
    val effectiveAlpha = if (enabled) alpha else (alpha * 0.38f)
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = if (effectiveAlpha < 1f) tint.copy(alpha = tint.alpha * effectiveAlpha) else tint,
        modifier = modifier.size(size),
    )
}
