package com.music.bitchord.desktop

import androidx.compose.ui.graphics.vector.ImageVector
import com.music.bitchord.desktop.icons.DesktopIcons
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.icons.SfStyleIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopIconsTest {

    private fun assertValidVector(label: String, vector: ImageVector) {
        assertTrue(vector.defaultWidth.value > 0f, "$label defaultWidth must be > 0")
        assertTrue(vector.defaultHeight.value > 0f, "$label defaultHeight must be > 0")
        assertTrue(vector.viewportWidth > 0f, "$label viewportWidth must be > 0")
        assertTrue(vector.viewportHeight > 0f, "$label viewportHeight must be > 0")
        assertTrue(vector.root.size > 0, "$label must contain at least one path/group node")
    }

    @Test
    fun allSemanticDesktopIconsBuildWithValidGeometry() {
        val entries: List<Pair<String, ImageVector>> = listOf(
            // Navigation
            "Navigation.Home" to DesktopIcons.Navigation.Home,
            "Navigation.New" to DesktopIcons.Navigation.New,
            "Navigation.Search" to DesktopIcons.Navigation.Search,
            "Navigation.SearchField" to DesktopIcons.Navigation.SearchField,
            "Navigation.Radio" to DesktopIcons.Navigation.Radio,
            "Navigation.Library" to DesktopIcons.Navigation.Library,
            "Navigation.LibraryOutline" to DesktopIcons.Navigation.LibraryOutline,
            "Navigation.Explore" to DesktopIcons.Navigation.Explore,
            "Navigation.ChevronRight" to DesktopIcons.Navigation.ChevronRight,
            "Navigation.ChevronLeft" to DesktopIcons.Navigation.ChevronLeft,
            "Navigation.ArrowBack" to DesktopIcons.Navigation.ArrowBack,
            "Navigation.ArrowForward" to DesktopIcons.Navigation.ArrowForward,
            "Navigation.NorthWest" to DesktopIcons.Navigation.NorthWest,
            "Navigation.OpenInNew" to DesktopIcons.Navigation.OpenInNew,
            "Navigation.OpenInFull" to DesktopIcons.Navigation.OpenInFull,
            "Navigation.GridView" to DesktopIcons.Navigation.GridView,
            "Navigation.ListView" to DesktopIcons.Navigation.ListView,
            "Navigation.Sort" to DesktopIcons.Navigation.Sort,
            "Navigation.Menu" to DesktopIcons.Navigation.Menu,
            // Player
            "Player.Play" to DesktopIcons.Player.Play,
            "Player.Pause" to DesktopIcons.Player.Pause,
            "Player.PlayCircle" to DesktopIcons.Player.PlayCircle,
            "Player.FastForward" to DesktopIcons.Player.FastForward,
            "Player.FastRewind" to DesktopIcons.Player.FastRewind,
            "Player.Shuffle" to DesktopIcons.Player.Shuffle,
            "Player.Repeat" to DesktopIcons.Player.Repeat,
            "Player.RepeatOne" to DesktopIcons.Player.RepeatOne,
            "Player.Infinity" to DesktopIcons.Player.Infinity,
            "Player.Queue" to DesktopIcons.Player.Queue,
            "Player.QueueMusic" to DesktopIcons.Player.QueueMusic,
            "Player.Lyrics" to DesktopIcons.Player.Lyrics,
            "Player.LyricsOutline" to DesktopIcons.Player.LyricsOutline,
            "Player.Favorite" to DesktopIcons.Player.Favorite,
            "Player.FavoriteFilled" to DesktopIcons.Player.FavoriteFilled,
            "Player.Like" to DesktopIcons.Player.Like,
            "Player.LikeFilled" to DesktopIcons.Player.LikeFilled,
            "Player.Dislike" to DesktopIcons.Player.Dislike,
            "Player.DislikeFilled" to DesktopIcons.Player.DislikeFilled,
            "Player.MusicNote" to DesktopIcons.Player.MusicNote,
            "Player.MusicVideo" to DesktopIcons.Player.MusicVideo,
            "Player.SleepTimer" to DesktopIcons.Player.SleepTimer,
            "Player.StartRadio" to DesktopIcons.Player.StartRadio,
            // Actions
            "Actions.Share" to DesktopIcons.Actions.Share,
            "Actions.Download" to DesktopIcons.Actions.Download,
            "Actions.Downloaded" to DesktopIcons.Actions.Downloaded,
            "Actions.Delete" to DesktopIcons.Actions.Delete,
            "Actions.DeleteSweep" to DesktopIcons.Actions.DeleteSweep,
            "Actions.Edit" to DesktopIcons.Actions.Edit,
            "Actions.Add" to DesktopIcons.Actions.Add,
            "Actions.AddToPlaylist" to DesktopIcons.Actions.AddToPlaylist,
            "Actions.RemoveFromPlaylist" to DesktopIcons.Actions.RemoveFromPlaylist,
            "Actions.PlaylistPlay" to DesktopIcons.Actions.PlaylistPlay,
            "Actions.PlaylistAdd" to DesktopIcons.Actions.PlaylistAdd,
            "Actions.PlayNext" to DesktopIcons.Actions.PlayNext,
            "Actions.PlayLast" to DesktopIcons.Actions.PlayLast,
            "Actions.Pin" to DesktopIcons.Actions.Pin,
            "Actions.More" to DesktopIcons.Actions.More,
            "Actions.MoreVertical" to DesktopIcons.Actions.MoreVertical,
            "Actions.Copy" to DesktopIcons.Actions.Copy,
            "Actions.Paste" to DesktopIcons.Actions.Paste,
            "Actions.Refresh" to DesktopIcons.Actions.Refresh,
            "Actions.Undo" to DesktopIcons.Actions.Undo,
            "Actions.Close" to DesktopIcons.Actions.Close,
            "Actions.Check" to DesktopIcons.Actions.Check,
            "Actions.CheckCircle" to DesktopIcons.Actions.CheckCircle,
            "Actions.RemoveCircle" to DesktopIcons.Actions.RemoveCircle,
            "Actions.Link" to DesktopIcons.Actions.Link,
            "Actions.DragHandle" to DesktopIcons.Actions.DragHandle,
            "Actions.ViewArtist" to DesktopIcons.Actions.ViewArtist,
            "Actions.ViewAlbum" to DesktopIcons.Actions.ViewAlbum,
            // Audio
            "Audio.AirPlay" to DesktopIcons.Audio.AirPlay,
            "Audio.Headphones" to DesktopIcons.Audio.Headphones,
            "Audio.Speaker" to DesktopIcons.Audio.Speaker,
            "Audio.Cast" to DesktopIcons.Audio.Cast,
            "Audio.CastConnected" to DesktopIcons.Audio.CastConnected,
            "Audio.Bluetooth" to DesktopIcons.Audio.Bluetooth,
            "Audio.Usb" to DesktopIcons.Audio.Usb,
            "Audio.VolumeUp" to DesktopIcons.Audio.VolumeUp,
            "Audio.VolumeDown" to DesktopIcons.Audio.VolumeDown,
            "Audio.VolumeOff" to DesktopIcons.Audio.VolumeOff,
            "Audio.GraphicEq" to DesktopIcons.Audio.GraphicEq,
            "Audio.Equalizer" to DesktopIcons.Audio.Equalizer,
            "Audio.HighQuality" to DesktopIcons.Audio.HighQuality,
            "Audio.SurroundSound" to DesktopIcons.Audio.SurroundSound,
            "Audio.Phone" to DesktopIcons.Audio.Phone,
            "Audio.Tv" to DesktopIcons.Audio.Tv,
            "Audio.Watch" to DesktopIcons.Audio.Watch,
            "Audio.Car" to DesktopIcons.Audio.Car,
            // Window
            "Window.PictureInPicture" to DesktopIcons.Window.PictureInPicture,
            "Window.Fullscreen" to DesktopIcons.Window.Fullscreen,
            "Window.ExitFullscreen" to DesktopIcons.Window.ExitFullscreen,
            "Window.ExpandLess" to DesktopIcons.Window.ExpandLess,
            "Window.ExpandMore" to DesktopIcons.Window.ExpandMore,
            // Settings
            "Settings.General" to DesktopIcons.Settings.General,
            "Settings.Tune" to DesktopIcons.Settings.Tune,
            "Settings.Account" to DesktopIcons.Settings.Account,
            "Settings.ManageAccounts" to DesktopIcons.Settings.ManageAccounts,
            "Settings.Folder" to DesktopIcons.Settings.Folder,
            "Settings.Storage" to DesktopIcons.Settings.Storage,
            "Settings.Cached" to DesktopIcons.Settings.Cached,
            "Settings.Language" to DesktopIcons.Settings.Language,
            "Settings.Translate" to DesktopIcons.Settings.Translate,
            "Settings.Extension" to DesktopIcons.Settings.Extension,
            "Settings.Lock" to DesktopIcons.Settings.Lock,
            "Settings.LockOpen" to DesktopIcons.Settings.LockOpen,
            "Settings.VisibilityOff" to DesktopIcons.Settings.VisibilityOff,
            "Settings.Performance" to DesktopIcons.Settings.Performance,
            "Settings.FrameRate" to DesktopIcons.Settings.FrameRate,
            "Settings.Wifi" to DesktopIcons.Settings.Wifi,
            "Settings.Sliders" to DesktopIcons.Settings.Sliders,
            "Settings.Font" to DesktopIcons.Settings.Font,
            "Settings.BlurOn" to DesktopIcons.Settings.BlurOn,
            "Settings.BarChart" to DesktopIcons.Settings.BarChart,
            "Settings.History" to DesktopIcons.Settings.History,
            "Settings.Brightness" to DesktopIcons.Settings.Brightness,
            "Settings.Animation" to DesktopIcons.Settings.Animation,
            "Settings.Miscellaneous" to DesktopIcons.Settings.Miscellaneous,
            // Status
            "Status.Info" to DesktopIcons.Status.Info,
            "Status.Error" to DesktopIcons.Status.Error,
            "Status.Groups" to DesktopIcons.Status.Groups,
            "Status.LibraryMusic" to DesktopIcons.Status.LibraryMusic,
            "Status.Schedule" to DesktopIcons.Status.Schedule,
            "Status.Sparkles" to DesktopIcons.Status.Sparkles,
            "Status.Instrumental" to DesktopIcons.Status.Instrumental,
        )

        entries.forEach { (name, vector) -> assertValidVector(name, vector) }
    }

    @Test
    fun suppliedPlayerActionVectorsKeepTheirOriginalViewports() {
        assertSame(SfStyleIcons.Star, DesktopIcons.Player.Like)
        assertSame(SfStyleIcons.StarFilled, DesktopIcons.Player.LikeFilled)
        for (star in listOf(SfStyleIcons.Star, SfStyleIcons.StarFilled)) {
            assertValidVector(star.name, star)
            assertEquals(31f, star.viewportWidth)
            assertEquals(31f, star.viewportHeight)
        }

        val more = SfStyleIcons.MoreHorizontalRounded
        assertValidVector("Rounded player more", more)
        assertEquals(24f, more.viewportWidth)
        assertEquals(24f, more.viewportHeight)
        assertEquals(1, more.root.size)
        assertNotSame(SfStyleIcons.MoreHorizontal, more)
    }

    @Test
    fun navigationAndStateVariantsAreConsistent() {
        assertSame(BitChordIcons.Home, DesktopIcons.Navigation.Home)
        assertSame(BitChordIcons.TabExplore, DesktopIcons.Navigation.New)
        assertSame(BitChordIcons.TabSearch, DesktopIcons.Navigation.Search)
        assertSame(BitChordIcons.TabRadio, DesktopIcons.Navigation.Radio)
        assertSame(BitChordIcons.TabLibrary, DesktopIcons.Navigation.Library)

        assertNotSame(DesktopIcons.Player.Repeat, DesktopIcons.Player.RepeatOne)
        assertEquals(24f, DesktopIcons.Player.Repeat.viewportWidth)
        assertEquals(24f, DesktopIcons.Player.RepeatOne.viewportWidth)

        assertNotSame(DesktopIcons.Player.Favorite, DesktopIcons.Player.FavoriteFilled)
        assertNotSame(DesktopIcons.Player.Like, DesktopIcons.Player.LikeFilled)
        assertNotSame(DesktopIcons.Player.Dislike, DesktopIcons.Player.DislikeFilled)
    }
}
