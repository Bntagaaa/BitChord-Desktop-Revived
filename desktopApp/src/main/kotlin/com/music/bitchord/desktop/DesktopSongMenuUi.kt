package com.music.bitchord.desktop

import com.music.bitchord.desktop.icons.DesktopIcons

import com.music.bitchord.ui.screens.desktopTopScrollFade

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.icons.BitChordIcons

/** Carries the origin of a row menu without changing the player or other page menus. */
internal data class DesktopPageSongMenuTarget(val song: Song, val fromHome: Boolean = false)

/** The verbs the song menu needs that the player does not otherwise know about. */
internal data class DesktopSongActions(
    val signedIn: Boolean,
    val disliked: Boolean,
    val downloaded: Boolean,
    val downloadInProgress: Boolean,
    val sleepTimerMinutes: Int?,
    val sleepAfterTrack: Boolean,
    val onToggleDislike: (Song) -> Unit,
    val onAddToPlaylist: (Song) -> Unit,
    val onDownload: (Song) -> Unit,
    val onRemoveDownload: (Song) -> Unit,
    val onStartRadio: (Song) -> Unit,
    val onPlayNext: (Song) -> Unit,
    val onAddToQueue: (Song) -> Unit,
    val onOpenAlbum: (String) -> Unit,
    val onOpenArtist: (String) -> Unit,
    val onSleepTimer: (Int?) -> Unit,
    val onSleepAfterTrack: () -> Unit,
    val onShare: (Song) -> Unit,
    val onRemoveHistory: ((Song) -> Unit)? = null,
    val onSwitchMediaVersion: (() -> Unit)? = null,
)

/** The "…" beside the player's heart, and the menu it opens. */
@Composable
internal fun DesktopSongMenuAnchor(
    song: Song,
    liked: Boolean,
    actions: DesktopSongActions,
    onToggleLike: () -> Unit,
    onRevertToOriginal: (() -> Unit)?,
    onUpgradeQuality: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var open by remember(song.videoId) { mutableStateOf(false) }
    Box(modifier) {
        DesktopMoreButton(onClick = { open = true })
        if (open) {
            DesktopSongMenuFor(
                song = song,
                liked = liked,
                actions = actions,
                onToggleLike = onToggleLike,
                onRevertToOriginal = onRevertToOriginal,
                onUpgradeQuality = onUpgradeQuality,
                onDismiss = { open = false },
            )
        }
    }
}

/** The menu [DesktopSongMenuAnchor] opens, for a caller that has its own button. */
@Composable
internal fun DesktopSongMenuFor(
    song: Song,
    liked: Boolean,
    actions: DesktopSongActions,
    onToggleLike: () -> Unit,
    onRevertToOriginal: (() -> Unit)?,
    onUpgradeQuality: (() -> Unit)?,
    onDismiss: () -> Unit,
    miniPlayerActions: DesktopMiniPlayerMenuActions? = null,
    onAutomixOverview: (() -> Unit)? = null,
    showSleepTimer: Boolean = true,
) {
    val close = onDismiss
    DesktopSongMenu(
        song = song,
        signedIn = actions.signedIn,
        likeStatus = when {
            liked -> LikeStatus.LIKE
            actions.disliked -> LikeStatus.DISLIKE
            else -> LikeStatus.INDIFFERENT
        },
        downloaded = actions.downloaded,
        downloadInProgress = actions.downloadInProgress,
        sleepTimerMinutes = actions.sleepTimerMinutes,
        sleepAfterTrack = actions.sleepAfterTrack,
        onRevertToOriginal = onRevertToOriginal?.let { { it(); close() } },
        onUpgradeQuality = onUpgradeQuality?.let { { it(); close() } },
        // Rating stays open: the menu says what the rating now is, and closing it is the
        // one way to not see that it worked.
        onToggleLike = onToggleLike,
        onToggleDislike = { actions.onToggleDislike(song) },
        onAddToPlaylist = { actions.onAddToPlaylist(song); close() },
        onDownload = { actions.onDownload(song); close() },
        onRemoveDownload = { actions.onRemoveDownload(song); close() },
        onStartRadio = { actions.onStartRadio(song); close() },
        onPlayNext = { actions.onPlayNext(song); close() },
        onAddToQueue = { actions.onAddToQueue(song); close() },
        onOpenAlbum = { id -> actions.onOpenAlbum(id); close() },
        onOpenArtist = { id -> actions.onOpenArtist(id); close() },
        onSleepTimer = { actions.onSleepTimer(it); close() },
        onSleepAfterTrack = { actions.onSleepAfterTrack(); close() },
        onShare = { actions.onShare(song); close() },
        onSwitchMediaVersion = actions.onSwitchMediaVersion?.let { { it(); close() } },
        onDismiss = close,
        onRemoveHistory = actions.onRemoveHistory?.let { remove -> { remove(song); close() } },
        miniPlayerActions = miniPlayerActions,
        onAutomixOverview = onAutomixOverview?.let { { it(); close() } },
        showSleepTimer = showSleepTimer,
    )
}

/** Everything the player can do to the track it is playing, in one menu. */
@Composable
internal fun DesktopSongMenu(
    song: Song,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    downloaded: Boolean,
    downloadInProgress: Boolean,
    onRemoveDownload: () -> Unit,
    sleepTimerMinutes: Int?,
    sleepAfterTrack: Boolean,
    /** Sends the playing track back to YouTube's own upload and keeps it there. */
    onRevertToOriginal: (() -> Unit)?,
    /** The way back from a revert, for a track pinned to YouTube's own upload. */
    onUpgradeQuality: (() -> Unit)?,
    onToggleLike: () -> Unit,
    onToggleDislike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onStartRadio: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onOpenAlbum: ((String) -> Unit)?,
    onOpenArtist: ((String) -> Unit)?,
    onSleepTimer: (Int?) -> Unit,
    onSleepAfterTrack: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    onRemoveHistory: (() -> Unit)? = null,
    onSwitchMediaVersion: (() -> Unit)? = null,
    miniPlayerActions: DesktopMiniPlayerMenuActions? = null,
    onAutomixOverview: (() -> Unit)? = null,
    showSleepTimer: Boolean = true,
) {
    var pickingSleepTimer by remember(song.videoId, showSleepTimer) { mutableStateOf(false) }
    var customTimer by remember(song.videoId, showSleepTimer) { mutableStateOf(false) }
    var customMinutes by remember(song.videoId, showSleepTimer) { mutableStateOf("30") }
    if (showSleepTimer && customTimer) Dialog(
        onDismissRequest = { customTimer = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        val parsed = DesktopSleepTimer.customMinutes(customMinutes)
        val submit = {
            parsed?.let {
                onSleepTimer(it)
                customTimer = false
            }
        }
        DesktopDialogFrame(onDismiss = { customTimer = false }, maxWidth = 380.dp) {
            DesktopDialogHeading("Custom sleep timer", "Minutes (5–720)")
            Spacer(Modifier.height(16.dp))
            DesktopDialogField(
                value = customMinutes,
                onValueChange = { customMinutes = it },
                placeholder = "Minutes (5–720)",
                onSubmit = { submit() },
                modifier = Modifier.focusRequester(focusRequester),
            )
            if (customMinutes.isNotBlank() && parsed == null) {
                DesktopDialogError("Enter a number between 5 and 720.")
            }
            Spacer(Modifier.height(6.dp))
            DesktopDialogButtons(
                confirmLabel = "Start",
                confirmEnabled = parsed != null,
                onConfirm = { submit() },
                onDismiss = { customTimer = false },
            )
        }
    }
    val liked = likeStatus == LikeStatus.LIKE
    val disliked = likeStatus == LikeStatus.DISLIKE
    var showingSongInfo by remember(song.videoId) { mutableStateOf(false) }
    if (showingSongInfo) Dialog(
        onDismissRequest = { showingSongInfo = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DesktopDialogFrame(onDismiss = { showingSongInfo = false }, maxWidth = 420.dp) {
            DesktopDialogHeading("Song Info")
            Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 14.dp)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = DesktopForeground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = DesktopSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = DesktopCardEdge)
            song.albumName?.takeIf(String::isNotBlank)?.let { album ->
                SongInfoRow(label = "Album", value = album)
                HorizontalDivider(thickness = 0.5.dp, color = DesktopCardEdge)
            }
            SongInfoRow(label = "Type", value = if (song.isVideo) "Music video" else "Song")
            HorizontalDivider(thickness = 0.5.dp, color = DesktopCardEdge)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showingSongInfo = false }) {
                    Text(DesktopStrings["done", "Done"], color = DesktopAccent)
                }
            }
        }
    }
    // A local file or a finished download has no YouTube identity behind it to rate, queue into a
    // playlist, fetch again, or share a link for.
    val isOffline = song.localUri != null

    DesktopMenuSurface(onDismiss = onDismiss) {
        if (showSleepTimer && pickingSleepTimer) {
            DesktopMenuHeader("Sleep timer") { pickingSleepTimer = false }
            DesktopMenuDivider()
            DesktopSleepTimer.presets.forEach { minutes ->
                DesktopMenuRow(
                    icon = DesktopIcons.Player.SleepTimer,
                    label = "$minutes minutes",
                    selected = sleepTimerMinutes == minutes && !sleepAfterTrack,
                ) { onSleepTimer(minutes) }
            }
            DesktopMenuRow(DesktopIcons.Player.SleepTimer, "Custom duration…") { customTimer = true }
            DesktopMenuRow(
                icon = DesktopIcons.Player.SleepTimer,
                label = DesktopStrings["d_end_of_this_track", "End of this track"],
                selected = sleepAfterTrack,
                onClick = onSleepAfterTrack,
            )
            if (sleepTimerMinutes != null || sleepAfterTrack) {
                DesktopMenuDivider()
                DesktopMenuRow(DesktopIcons.Player.SleepTimer, DesktopStrings["d_turn_off", "Turn off"]) { onSleepTimer(null) }
            }
            return@DesktopMenuSurface
        }

        miniPlayerActions?.takeIf { it.hasOverflow }?.let { actions ->
            DesktopMiniPlayerMenuItems(actions, onDismiss)
            DesktopMenuDivider()
        }

        onRemoveHistory?.let { remove ->
            DesktopMenuRow(DesktopIcons.Player.SleepTimer, "Remove from history", onClick = remove)
            DesktopMenuDivider()
        }
        onSwitchMediaVersion?.let { switch ->
            DesktopMenuRow(DesktopIcons.Audio.HighQuality,
                if (song.isVideo) "Switch to audio" else "Watch music video", onClick = switch)
            DesktopMenuDivider()
        }
        // The two are never both offered.
        (onRevertToOriginal ?: onUpgradeQuality)?.let { action ->
            DesktopMenuRow(
                icon = if (onRevertToOriginal != null) DesktopIcons.Actions.Undo else DesktopIcons.Audio.HighQuality,
                label = if (onRevertToOriginal != null) "Revert to original" else "Upgrade quality",
                onClick = action,
            )
            DesktopMenuDivider()
        }

        // Mobile's three horizontal actions, using the native BitChord star glyphs.
        // Ratings stay open so a change can be reverted in the same menu.
        if (!isOffline) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (signedIn) {
                    DesktopMenuQuickAction(
                        if (liked) DesktopIcons.Player.LikeFilled else DesktopIcons.Player.Like,
                        "Like", liked, Modifier.weight(1f), onToggleLike)
                    DesktopMenuQuickAction(
                        if (disliked) DesktopIcons.Player.DislikeFilled else DesktopIcons.Player.Dislike,
                        "Dislike", disliked, Modifier.weight(1f), onToggleDislike)
                }
                DesktopMenuQuickAction(
                    DesktopIcons.Actions.Share, "Share", false, Modifier.weight(1f), onShare)
            }
            DesktopMenuDivider()
        }
        run {
            DesktopMenuRow(DesktopIcons.Actions.AddToPlaylist, DesktopStrings["add_to_playlist", "Add to playlist"], onClick = onAddToPlaylist)
        }
        if (signedIn || !isOffline) DesktopMenuDivider()

        DesktopMenuRow(DesktopIcons.Player.StartRadio, DesktopStrings["start_radio", "Start radio"], onClick = onStartRadio)
        DesktopMenuRow(DesktopIcons.Actions.PlayNext, DesktopStrings["play_next", "Play next"], onClick = onPlayNext)
        DesktopMenuRow(DesktopIcons.Actions.PlayLast, DesktopStrings["add_to_queue", "Add to queue"], onClick = onAddToQueue)

        // A saved copy is offered its own way out whatever the track is playing from — a download
        // being played *is* a local file, and hiding the row there left no way to remove it.
        // Only starting a new download needs a YouTube identity behind it.
        when {
            downloadInProgress -> DesktopMenuRow(
                icon = DesktopIcons.Actions.Download,
                label = DesktopStrings["cancel_download", "Cancel download"],
                busy = true,
                onClick = onDownload,
            )
            downloaded -> DesktopMenuRow(
                icon = DesktopIcons.Actions.Downloaded,
                label = DesktopStrings["saved_to_downloads", "Saved to Downloads"],
                tint = DesktopAccent,
                value = DesktopStrings["delete", "Delete"],
                onClick = onRemoveDownload,
            )
            !isOffline -> DesktopMenuRow(
                icon = DesktopIcons.Actions.Download,
                label = DesktopStrings["download", "Download"],
                onClick = onDownload,
            )
        }
        val albumId = song.albumId
        val artistId = song.artistId
        if ((albumId != null && onOpenAlbum != null) || (artistId != null && onOpenArtist != null)) {
            DesktopMenuDivider()
            if (albumId != null && onOpenAlbum != null) {
                DesktopMenuRow(DesktopIcons.Actions.ViewAlbum, DesktopStrings["open_album", "Open album"], description = song.albumName) { onOpenAlbum(albumId) }
            }
            if (artistId != null && onOpenArtist != null) {
                DesktopMenuRow(DesktopIcons.Actions.ViewArtist, DesktopStrings["open_artist", "Open artist"], description = song.artist) { onOpenArtist(artistId) }
            }
        }

        DesktopMenuDivider()
        DesktopMenuRow(
            icon = DesktopIcons.Status.Info,
            label = "Song Info",
            onClick = { showingSongInfo = true },
        )
        if (showSleepTimer || onAutomixOverview != null) {
            DesktopMenuDivider()
        }
        if (showSleepTimer) {
            DesktopMenuRow(
                icon = DesktopIcons.Player.SleepTimer,
                label = DesktopStrings["sleep_timer", "Sleep timer"],
                value = sleepTimerLabel(sleepTimerMinutes, sleepAfterTrack),
            ) { pickingSleepTimer = true }
        }
        if (onAutomixOverview != null) {
            DesktopMenuRow(
                icon = DesktopIcons.Audio.GraphicEq,
                label = DesktopStrings["automix_overview", "Automix overview"],
                onClick = onAutomixOverview,
            )
        }
    }
}

/** Keeps the floating player's desktop tools available without crowding its compact action row. */
@Composable
internal fun DesktopMiniPlayerMenuItems(
    actions: DesktopMiniPlayerMenuActions,
    onDismiss: () -> Unit,
) {
    fun dispatch(action: () -> Unit) {
        onDismiss()
        action()
    }

    if (actions.transportInMenu) {
        DesktopMenuRow(
            icon = DesktopIcons.Player.Shuffle,
            label = "Shuffle",
            selected = actions.shuffle,
        ) { dispatch { actions.onShuffleChange(!actions.shuffle) } }
        DesktopMenuRow(
            icon = if (actions.repeatMode == DesktopRepeatMode.ONE) DesktopIcons.Player.RepeatOne else DesktopIcons.Player.Repeat,
            label = "Repeat ${actions.repeatMode.label()}",
            selected = actions.repeatMode != DesktopRepeatMode.OFF,
        ) { dispatch { actions.onRepeatModeChange(actions.repeatMode.next()) } }
        if (actions.panelsInMenu || actions.secondaryInMenu) DesktopMenuDivider()
    }
    if (actions.panelsInMenu) {
        DesktopMenuRow(
            icon = DesktopIcons.Player.Lyrics,
            label = "Lyrics",
            selected = actions.sidePanel == DesktopSidePanel.LYRICS,
        ) { dispatch(actions.onOpenLyrics) }
        DesktopMenuRow(
            icon = DesktopIcons.Player.QueueMusic,
            label = "Queue",
            selected = actions.sidePanel == DesktopSidePanel.QUEUE,
        ) { dispatch(actions.onOpenQueue) }
        if (actions.secondaryInMenu) DesktopMenuDivider()
    }
    if (actions.secondaryInMenu) {
        DesktopMenuRow(
            icon = DesktopIcons.Window.PictureInPicture,
            label = "Picture in picture",
            enabled = actions.enabled,
        ) { dispatch(actions.onOpenPip) }
        DesktopMenuRow(
            icon = DesktopIcons.Status.Info,
            label = "Now playing details",
            selected = actions.sidePanel == DesktopSidePanel.DETAILS,
        ) { dispatch(actions.onOpenDetails) }
    }
}


/**
 * Album, YouTube and Spotify playlist overflow. All visible actions are functional.
 * Unmatched Spotify tracks must never enter the YouTube Music queue as Spotify IDs.
 */
internal data class DesktopCollectionMenuActions(
    val onShuffle: () -> Unit,
    val onFind: () -> Unit,
    val onStartMix: (() -> Unit)?,
    val onPlayNext: (() -> Unit)?,
    val onAddToQueue: (() -> Unit)?,
    val onDownloadAll: (() -> Unit)?,
    val onShare: (() -> Unit)?,
    val onSaveToLibrary: (() -> Unit)? = null,
    val savedToLibrary: Boolean = false,
    val onPin: (() -> Unit)? = null,
    val pinned: Boolean = false,
    val onRename: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    /** Whole-collection download state, when the page has been asked for as a whole. */
    val playlistProgress: DesktopPlaylistDownloadProgress? = null,
    /** The snapshot no longer matches the live listing — the download row becomes an update. */
    val playlistStale: Boolean = false,
    /** Throws the whole-collection download away; offered when one exists. */
    val onRemovePlaylistDownload: (() -> Unit)? = null,
)

@Composable
internal fun DesktopCollectionContextMenu(
    actions: DesktopCollectionMenuActions,
    onDismiss: () -> Unit,
) {
    DesktopMenuSurface(onDismiss = onDismiss) {
        DesktopMenuRow(BitChordIcons.Shuffle, "Shuffle") { onDismiss(); actions.onShuffle() }
        DesktopMenuRow(BitChordIcons.Search, "Find in playlist") { onDismiss(); actions.onFind() }
        actions.onStartMix?.let { mix ->
            DesktopMenuRow(DesktopIcons.Player.StartRadio, "Start mix") { onDismiss(); mix() }
        }
        DesktopMenuDivider()
        actions.onPlayNext?.let { next ->
            DesktopMenuRow(DesktopIcons.Actions.PlayNext, "Play next") { onDismiss(); next() }
        }
        actions.onAddToQueue?.let { queue ->
            DesktopMenuRow(DesktopIcons.Actions.PlayLast, "Add to queue") { onDismiss(); queue() }
        }
        actions.onSaveToLibrary?.let { save ->
            DesktopMenuRow(
                icon = if (actions.savedToLibrary) BitChordIcons.Check else DesktopIcons.Actions.PlaylistAdd,
                label = if (actions.savedToLibrary) "Remove from library" else "Save to library",
            ) { onDismiss(); save() }
        }
        actions.onPin?.let { pin ->
            DesktopMenuRow(
                icon = DesktopIcons.Actions.Pin,
                label = if (actions.pinned) "Unpin playlist" else "Pin playlist",
                selected = actions.pinned,
            ) { onDismiss(); pin() }
        }
        DesktopMenuDivider()
        actions.onDownloadAll?.let { download ->
            val progress = actions.playlistProgress
            when {
                progress == null -> DesktopMenuRow(DesktopIcons.Actions.Download, "Download all") { onDismiss(); download() }
                progress.downloading -> DesktopMenuRow(BitChordIcons.Clock, "Downloading · ${progress.done + progress.active} of ${progress.total}") { onDismiss(); download() }
                actions.playlistStale -> DesktopMenuRow(DesktopIcons.Actions.Refresh, "Update download") { onDismiss(); download() }
                else -> DesktopMenuRow(BitChordIcons.Check, "Downloaded") { onDismiss(); download() }
            }
        }
        actions.onRemovePlaylistDownload?.let { remove ->
            DesktopMenuRow(DesktopIcons.Actions.Delete, "Remove playlist download") { onDismiss(); remove() }
        }
        actions.onShare?.let { share ->
            DesktopMenuRow(DesktopIcons.Actions.Share, "Share") { onDismiss(); share() }
        }
        if (actions.onRename != null || actions.onDelete != null) {
            DesktopMenuDivider()
            actions.onRename?.let { rename ->
                DesktopMenuRow(DesktopIcons.Actions.Edit, "Rename") { onDismiss(); rename() }
            }
            actions.onDelete?.let { delete ->
                DesktopMenuRow(DesktopIcons.Actions.Delete, "Delete playlist") { onDismiss(); delete() }
            }
        }
    }
}

/**
 * The Downloads page's playlist-download menu: open it, update it when stale,
 * or throw the record away.
 */
@Composable
internal fun DesktopPlaylistDownloadRowMenu(
    onOpen: () -> Unit,
    onUpdate: (() -> Unit)?,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    DesktopMenuSurface(onDismiss = onDismiss) {
        DesktopMenuRow(DesktopIcons.Player.Play, "Open") { onDismiss(); onOpen() }
        onUpdate?.let { update ->
            DesktopMenuRow(DesktopIcons.Actions.Refresh, "Update download") { onDismiss(); update() }
        }
        DesktopMenuRow(DesktopIcons.Actions.Delete, "Remove download") { onDismiss(); onRemove() }
    }
}

/** An unmatched Spotify row offers a real resolution action, not broken YouTube verbs. */
@Composable
internal fun DesktopSpotifyPendingSongMenu(onResolveAndPlay: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        DesktopMoreButton(onClick = { open = true })
        if (open) {
            DesktopMenuSurface(onDismiss = { open = false }) {
                DesktopMenuRow(DesktopIcons.Player.Play, "Find & play on YouTube Music") {
                    open = false
                    onResolveAndPlay()
                }
                DesktopMenuDivider()
                DesktopMenuRow(
                    DesktopIcons.Player.QueueMusic,
                    "More actions available after matching",
                    enabled = false,
                ) {}
            }
        }
    }
}

private fun sleepTimerLabel(minutes: Int?, afterTrack: Boolean): String = when {
    afterTrack -> "End of track"
    minutes != null -> "$minutes min"
    else -> ""
}

/** The panel a menu is drawn in: a floating card in the app's own glass. */
@Composable
internal fun DesktopMenuSurface(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    anchor: Rect? = null,
    maxHeight: Dp = 520.dp,
    content: @Composable () -> Unit,
) {
    val menuScrollState = rememberScrollState()
    val portal = LocalDesktopMenuPortal.current
    val owner = remember { Any() }
    val horizontalBias = LocalDesktopMenuHorizontalBias.current
    val density = LocalDensity.current
    val horizontalBiasPx = with(density) { horizontalBias.roundToPx() }
    val pressAnchor = remember(owner) { portal?.recentPress() }
    val latestContent = rememberUpdatedState(content)
    val latestDismiss = rememberUpdatedState(onDismiss)
    val menuContent: @Composable () -> Unit = remember(owner, modifier, maxHeight) {
        { MenuCard(menuScrollState, modifier, maxHeight) { latestContent.value() } }
    }
    if (portal != null) {
        Spacer(Modifier.size(1.dp).onGloballyPositioned {
            val bounds = anchor ?: pressAnchor ?: (it.parentLayoutCoordinates?.boundsInRoot() ?: it.boundsInRoot())
            portal.show(DesktopMenuPortalEntry(
                owner = owner,
                horizontalBiasPx = horizontalBiasPx,
                anchor = bounds,
                onDismiss = { latestDismiss.value() },
                content = menuContent,
                keepAdjacent = anchor != null,
            ))
        })
        DisposableEffect(portal, owner) {
            onDispose {
                if (portal.entry.value?.owner === owner) {
                    portal.entry.value = null
                    portal.onClosed()
                }
            }
        }
    } else Popup(
        popupPositionProvider = remember { DesktopMenuPosition() },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) { menuContent() }
}

@Composable
private fun MenuCard(
    menuScrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier,
    maxHeight: Dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.widthIn(min = 276.dp, max = 306.dp).desktopMenuGlass(shape)) {
        Column(
            Modifier
                .fillMaxWidth()
                // A menu with every optional row on it is taller than a small window; capped and
                // scrolled rather than run off the screen.
                .heightIn(max = maxHeight)
                .padding(vertical = 4.dp)
                .desktopTopScrollFade(menuScrollState)
                .verticalScroll(menuScrollState),
        ) {
            content()
        }
    }
}

/** Where a menu hangs relative to the button that opened it. */
private class DesktopMenuPosition : PopupPositionProvider {

    private var above: Boolean? = null

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = MENU_MARGIN
        val below = anchorBounds.bottom + margin
        val above = anchorBounds.top - popupContentSize.height - margin
        val fitsBelow = below + popupContentSize.height + margin <= windowSize.height
        val fitsAbove = above >= margin
        val placeAbove = this.above ?: (!fitsBelow && fitsAbove)
        if (this.above == null) this.above = placeAbove
        val y = when {
            placeAbove && fitsAbove -> above
            fitsBelow -> below
            // Neither side has room, so it is as tall as the window allows and sits between the
            // margins.
            else -> margin
        }
        val x = anchorBounds.left.coerceIn(
            margin,
            (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin),
        )
        return IntOffset(x, y.coerceAtMost((windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)))
    }
}

/** Air kept between a menu and the window's edges. */
private const val MENU_MARGIN = 12

/** A submenu's title, and the way back out of it. */
@Composable
private fun DesktopMenuHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .desktopHoverFill(alpha = 0.055f)
            .clickable(onClick = onBack)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DesktopIcons.Navigation.ArrowBack, DesktopStrings["back", "Back"], tint = DesktopSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = DesktopForeground,
        )
    }
}

@Composable
private fun DesktopMenuDivider() {
    HorizontalDivider(
        Modifier.padding(horizontal = 14.dp, vertical = 3.dp),
        thickness = 0.5.dp,
        color = DesktopBarEdge,
    )
}

/** Tap targets mirror BitChord mobile's Like / Dislike / Share quick-action row. */
@Composable
private fun DesktopMenuQuickAction(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hoverFill by animateColorAsState(
        targetValue = if (hovered) DesktopForeground.copy(alpha = 0.09f) else Color.Transparent,
        animationSpec = tween(durationMillis = if (DesktopAppearanceSettings.reduceAnimation.value) 0 else 170),
        label = "desktop-menu-quick-action-hover",
    )
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(hoverFill)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, label, tint = if (selected) DesktopAccent else DesktopForeground,
            modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold, color = DesktopForeground,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One menu row. */
@Composable
private fun DesktopMenuRow(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    value: String = "",
    description: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hoverColor by animateColorAsState(
        targetValue = if (hovered && enabled) DesktopForeground.copy(alpha = 0.09f) else Color.Transparent,
        animationSpec = tween(durationMillis = if (DesktopAppearanceSettings.reduceAnimation.value) 0 else 170),
        label = "desktop-context-menu-hover",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(hoverColor)
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), color = DesktopAccent, strokeWidth = 2.dp)
        } else {
            Icon(
                icon,
                null,
                tint = tint ?: if (enabled) DesktopForeground.copy(alpha = 0.82f) else DesktopSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint ?: if (enabled) DesktopForeground else DesktopSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            description?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = DesktopSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value.isNotBlank()) {
            Text(value, style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(BitChordIcons.Check, null, tint = DesktopAccent, modifier = Modifier.size(14.dp))
        }
    }
}

/** The "…" the menu hangs off, sized and tinted to sit beside the player's heart. */
@Composable
internal fun DesktopMoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = DesktopForeground.copy(alpha = 0.75f),
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hoverColor by animateColorAsState(
        targetValue = if (hovered) DesktopForeground.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = tween(durationMillis = if (DesktopAppearanceSettings.reduceAnimation.value) 0 else 140),
        label = "desktop-more-button-hover",
    )
    Box(
        modifier
            .size(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(hoverColor)
            .hoverable(interaction)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "More options" },
        contentAlignment = Alignment.Center,
    ) {
        // Three dots drawn rather than taken from the icon set.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .size(3.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tint),
                )
            }
        }
    }
}

@Composable
private fun SongInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = DesktopSecondary,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = DesktopForeground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
