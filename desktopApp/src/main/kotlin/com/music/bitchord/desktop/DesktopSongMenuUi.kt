package com.music.bitchord.desktop

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
) {
    var pickingSleepTimer by remember { mutableStateOf(false) }
    var customTimer by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf("30") }
    if (customTimer) androidx.compose.material3.AlertDialog(
        onDismissRequest = { customTimer = false },
        title = { androidx.compose.material3.Text("Custom sleep timer") },
        text = { androidx.compose.material3.OutlinedTextField(
            value = customMinutes, onValueChange = { customMinutes = it },
            label = { androidx.compose.material3.Text("Minutes (5–720)") }, singleLine = true,
            isError = DesktopSleepTimer.customMinutes(customMinutes) == null,
        ) },
        confirmButton = { androidx.compose.material3.TextButton(
            enabled = DesktopSleepTimer.customMinutes(customMinutes) != null,
            onClick = { DesktopSleepTimer.customMinutes(customMinutes)?.let { onSleepTimer(it) }; customTimer = false },
        ) { androidx.compose.material3.Text("Start") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { customTimer = false }) { androidx.compose.material3.Text("Cancel") } },
    )
    val liked = likeStatus == LikeStatus.LIKE
    val disliked = likeStatus == LikeStatus.DISLIKE
    var showingSongInfo by remember(song.videoId) { mutableStateOf(false) }
    if (showingSongInfo) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showingSongInfo = false },
        title = { Text("Song Info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(song.title, fontWeight = FontWeight.Bold)
                Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                song.albumName?.takeIf(String::isNotBlank)?.let {
                    Text("Album: $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (song.isVideo) "Music video" else "Song", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { showingSongInfo = false }) {
                Text("Done")
            }
        },
    )
    // A local file or a finished download has no YouTube identity behind it to rate, queue into a
    // playlist, fetch again, or share a link for.
    val isOffline = song.localUri != null

    DesktopMenuSurface(onDismiss = onDismiss) {
        if (pickingSleepTimer) {
            DesktopMenuHeader("Sleep timer") { pickingSleepTimer = false }
            DesktopMenuDivider()
            DesktopSleepTimer.presets.forEach { minutes ->
                DesktopMenuRow(
                    icon = Icons.Rounded.Bedtime,
                    label = "$minutes minutes",
                    selected = sleepTimerMinutes == minutes && !sleepAfterTrack,
                ) { onSleepTimer(minutes) }
            }
            DesktopMenuRow(Icons.Rounded.Bedtime, "Custom duration…") { customTimer = true }
            DesktopMenuRow(
                icon = Icons.Rounded.Bedtime,
                label = DesktopStrings["d_end_of_this_track", "End of this track"],
                selected = sleepAfterTrack,
                onClick = onSleepAfterTrack,
            )
            if (sleepTimerMinutes != null || sleepAfterTrack) {
                DesktopMenuDivider()
                DesktopMenuRow(Icons.Rounded.Bedtime, DesktopStrings["d_turn_off", "Turn off"]) { onSleepTimer(null) }
            }
            return@DesktopMenuSurface
        }

        onRemoveHistory?.let { remove ->
            DesktopMenuRow(Icons.Rounded.Bedtime, "Remove from history", onClick = remove)
            DesktopMenuDivider()
        }
        onSwitchMediaVersion?.let { switch ->
            DesktopMenuRow(Icons.Rounded.HighQuality,
                if (song.isVideo) "Switch to audio" else "Watch music video", onClick = switch)
            DesktopMenuDivider()
        }
        // The two are never both offered.
        (onRevertToOriginal ?: onUpgradeQuality)?.let { action ->
            DesktopMenuRow(
                icon = if (onRevertToOriginal != null) Icons.AutoMirrored.Rounded.Undo else Icons.Rounded.HighQuality,
                label = if (onRevertToOriginal != null) "Revert to original" else "Upgrade quality",
                onClick = action,
            )
            DesktopMenuDivider()
        }

        // Mobile's three horizontal actions, using the native BitChord star glyphs.
        // Ratings stay open so a change can be reverted in the same menu.
        if (!isOffline) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (signedIn) {
                    DesktopMenuQuickAction(
                        if (liked) BitChordIcons.StarFilled else BitChordIcons.Star,
                        "Like", liked, Modifier.weight(1f), onToggleLike)
                    DesktopMenuQuickAction(
                        if (disliked) DesktopPhoneMenuIcons.BrokenHeartFilled else DesktopPhoneMenuIcons.BrokenHeart,
                        "Dislike", disliked, Modifier.weight(1f), onToggleDislike)
                }
                DesktopMenuQuickAction(
                    DesktopPhoneMenuIcons.Share, "Share", false, Modifier.weight(1f), onShare)
            }
            DesktopMenuDivider()
        }
        run {
            DesktopMenuRow(Icons.AutoMirrored.Rounded.PlaylistAdd, DesktopStrings["add_to_playlist", "Add to playlist"], onClick = onAddToPlaylist)
        }
        if (signedIn || !isOffline) DesktopMenuDivider()

        DesktopMenuRow(Icons.Rounded.Radio, DesktopStrings["start_radio", "Start radio"], onClick = onStartRadio)
        DesktopMenuRow(Icons.AutoMirrored.Rounded.PlaylistPlay, DesktopStrings["play_next", "Play next"], onClick = onPlayNext)
        DesktopMenuRow(Icons.AutoMirrored.Rounded.QueueMusic, DesktopStrings["add_to_queue", "Add to queue"], onClick = onAddToQueue)

        // A saved copy is offered its own way out whatever the track is playing from — a download
        // being played *is* a local file, and hiding the row there left no way to remove it.
        // Only starting a new download needs a YouTube identity behind it.
        when {
            downloadInProgress -> DesktopMenuRow(
                icon = BitChordIcons.Download,
                label = DesktopStrings["cancel_download", "Cancel download"],
                busy = true,
                onClick = onDownload,
            )
            downloaded -> DesktopMenuRow(
                icon = Icons.Rounded.DownloadDone,
                label = DesktopStrings["saved_to_downloads", "Saved to Downloads"],
                tint = DesktopAccent,
                value = DesktopStrings["delete", "Delete"],
                onClick = onRemoveDownload,
            )
            !isOffline -> DesktopMenuRow(
                icon = BitChordIcons.Download,
                label = DesktopStrings["download", "Download"],
                onClick = onDownload,
            )
        }
        val albumId = song.albumId
        val artistId = song.artistId
        if ((albumId != null && onOpenAlbum != null) || (artistId != null && onOpenArtist != null)) {
            DesktopMenuDivider()
            if (albumId != null && onOpenAlbum != null) {
                DesktopMenuRow(Icons.Rounded.Album, DesktopStrings["open_album", "Open album"], description = song.albumName) { onOpenAlbum(albumId) }
            }
            if (artistId != null && onOpenArtist != null) {
                DesktopMenuRow(Icons.Rounded.Person, DesktopStrings["open_artist", "Open artist"], description = song.artist) { onOpenArtist(artistId) }
            }
        }

        DesktopMenuDivider()
        DesktopMenuRow(
            icon = DesktopPhoneMenuIcons.Info,
            label = "Song Info",
            onClick = { showingSongInfo = true },
        )
        DesktopMenuDivider()
        DesktopMenuRow(
            icon = Icons.Rounded.Bedtime,
            label = DesktopStrings["sleep_timer", "Sleep timer"],
            value = sleepTimerLabel(sleepTimerMinutes, sleepAfterTrack),
        ) { pickingSleepTimer = true }
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
            DesktopMenuRow(Icons.Rounded.Radio, "Start mix") { onDismiss(); mix() }
        }
        DesktopMenuDivider()
        actions.onPlayNext?.let { next ->
            DesktopMenuRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next") { onDismiss(); next() }
        }
        actions.onAddToQueue?.let { queue ->
            DesktopMenuRow(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { onDismiss(); queue() }
        }
        actions.onSaveToLibrary?.let { save ->
            DesktopMenuRow(
                icon = if (actions.savedToLibrary) BitChordIcons.Check else Icons.AutoMirrored.Rounded.PlaylistAdd,
                label = if (actions.savedToLibrary) "Remove from library" else "Save to library",
            ) { onDismiss(); save() }
        }
        actions.onPin?.let { pin ->
            DesktopMenuRow(
                icon = Icons.Rounded.PushPin,
                label = if (actions.pinned) "Unpin playlist" else "Pin playlist",
                selected = actions.pinned,
            ) { onDismiss(); pin() }
        }
        DesktopMenuDivider()
        actions.onDownloadAll?.let { download ->
            DesktopMenuRow(BitChordIcons.Download, "Download all") { onDismiss(); download() }
        }
        actions.onShare?.let { share ->
            DesktopMenuRow(Icons.Rounded.Share, "Share") { onDismiss(); share() }
        }
        if (actions.onRename != null || actions.onDelete != null) {
            DesktopMenuDivider()
            actions.onRename?.let { rename ->
                DesktopMenuRow(Icons.Rounded.Edit, "Rename") { onDismiss(); rename() }
            }
            actions.onDelete?.let { delete ->
                DesktopMenuRow(Icons.Rounded.Delete, "Delete playlist") { onDismiss(); delete() }
            }
        }
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
                DesktopMenuRow(Icons.Rounded.PlayArrow, "Find & play on YouTube Music") {
                    open = false
                    onResolveAndPlay()
                }
                DesktopMenuDivider()
                DesktopMenuRow(
                    Icons.AutoMirrored.Rounded.QueueMusic,
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
internal fun DesktopMenuSurface(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val menuScrollState = rememberScrollState()
    val portal = LocalDesktopMenuPortal.current
    val owner = remember { Any() }
    val pressAnchor = remember(owner) { portal?.recentPress() }
    val latestContent = rememberUpdatedState(content)
    val latestDismiss = rememberUpdatedState(onDismiss)
    val menuContent: @Composable () -> Unit = remember(owner) { { MenuCard(menuScrollState, modifier) { latestContent.value() } } }
    if (portal != null) {
        Spacer(Modifier.size(1.dp).onGloballyPositioned {
            val bounds = pressAnchor ?: (it.parentLayoutCoordinates?.boundsInRoot() ?: it.boundsInRoot())
            if (portal.entry.value?.owner !== owner || portal.entry.value?.anchor != bounds) {
                portal.entry.value = DesktopMenuPortalEntry(owner, bounds, { latestDismiss.value() }, menuContent)
            }
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
private fun MenuCard(menuScrollState: androidx.compose.foundation.ScrollState, modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.widthIn(min = 292.dp, max = 330.dp).desktopMenuGlass(shape, tintAlpha = 0.82f)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                // A menu with every optional row on it is taller than a small window; capped and
                // scrolled rather than run off the screen.
                .heightIn(max = 580.dp)
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
            .heightIn(min = 32.dp)
            .desktopHoverFill(alpha = 0.055f)
            .clickable(onClick = onBack)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ArrowBack, DesktopStrings["back", "Back"], tint = DesktopSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(10.dp))
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
        Modifier.padding(vertical = 5.dp),
        thickness = 0.5.dp,
        color = DesktopCardEdge,
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
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (hovered) DesktopRowHover else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .heightIn(min = 66.dp)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, label, tint = if (selected) DesktopAccent else DesktopForeground,
            modifier = Modifier.size(26.dp))
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
        targetValue = if (hovered && enabled) DesktopRowHover else Color.Transparent,
        animationSpec = tween(durationMillis = if (DesktopAppearanceSettings.reduceAnimation.value) 0 else 140),
        label = "desktop-context-menu-hover",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(hoverColor)
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(15.dp), color = DesktopAccent, strokeWidth = 2.dp)
        } else {
            Icon(
                icon,
                null,
                tint = tint ?: if (enabled) DesktopForeground.copy(alpha = 0.82f) else DesktopSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
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
            Icon(BitChordIcons.Check, null, tint = DesktopAccent, modifier = Modifier.size(15.dp))
        }
    }
}

/** The "…" the menu hangs off, sized and tinted to sit beside the player's heart. */
@Composable
internal fun DesktopMoreButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
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
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Three dots drawn rather than taken from the icon set.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .size(3.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(DesktopForeground.copy(alpha = 0.75f)),
                )
            }
        }
    }
}
