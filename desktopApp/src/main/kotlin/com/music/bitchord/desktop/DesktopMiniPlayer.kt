package com.music.bitchord.desktop

import com.music.bitchord.desktop.icons.DesktopIcons

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.bitchord_no_music
import bitchord.desktopapp.generated.resources.player_transport_next
import bitchord.desktopapp.generated.resources.player_transport_pause
import bitchord.desktopapp.generated.resources.player_transport_play
import bitchord.desktopapp.generated.resources.player_transport_previous
import bitchord.desktopapp.generated.resources.bin_volume_off
import com.music.bitchord.ui.components.trackArtSize
import com.music.bitchord.ui.icons.BitChordIcons
import org.jetbrains.compose.resources.painterResource

/** Extra desktop tools share the track's existing overflow, including at narrow widths. */
internal data class DesktopMiniPlayerMenuActions(
    val enabled: Boolean,
    val transportInMenu: Boolean,
    val panelsInMenu: Boolean,
    val secondaryInMenu: Boolean,
    val shuffle: Boolean,
    val repeatMode: DesktopRepeatMode,
    val sidePanel: DesktopSidePanel?,
    val onShuffleChange: (Boolean) -> Unit,
    val onRepeatModeChange: (DesktopRepeatMode) -> Unit,
    val onOpenLyrics: () -> Unit,
    val onOpenQueue: () -> Unit,
    val onOpenPip: () -> Unit,
    val onOpenDetails: () -> Unit,
) {
    // Audio output is always a dedicated toolbar control, never overflow-only.
    val hasOverflow: Boolean get() = transportInMenu || panelsInMenu || secondaryInMenu
}

@Composable
internal fun DesktopMiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    previousEnabled: Boolean,
    progress: Float,
    volume: Float,
    shuffle: Boolean,
    repeatMode: DesktopRepeatMode,
    sidePanel: DesktopSidePanel?,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (DesktopRepeatMode) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onOpenAudioOutput: (Rect) -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenPip: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenDetails: () -> Unit,
    liked: Boolean = false,
    onToggleLike: (() -> Unit)? = null,
    songMenu: (@Composable (DesktopMiniPlayerMenuActions) -> Unit)? = null,
    muted: Boolean = false,
    onToggleMute: () -> Unit = {},
    pipOpen: Boolean = false,
    audioOutputOpen: Boolean = false,
) {
    val shape = RoundedCornerShape(percent = 50)
    val dockInteraction = remember { MutableInteractionSource() }
    val dockHovered by dockInteraction.collectIsHoveredAsState()
    val foreground = DesktopForeground
    val dockSurface = if (DesktopAppearanceSettings.blurEnabled(DesktopBlurArea.FLOATING_PLAYER)) {
        Modifier.desktopBarGlass(shape, DesktopBlurArea.FLOATING_PLAYER)
    } else {
        Modifier
            .background(DesktopTheme.palette.floatingPlayerSurface, shape)
            .border(0.5.dp, DesktopBarEdge, shape)
    }
    Surface(
        modifier = Modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            // The shadow precedes clipping so it remains outside the capsule.
            .shadow(16.dp, shape, clip = false,
                ambientColor = Color.Black.copy(alpha = 0.22f),
                spotColor = Color.Black.copy(alpha = 0.30f))
            .clip(shape)
            .then(dockSurface)
            .background(Brush.verticalGradient(listOf(
                Color.White.copy(alpha = if (DesktopTheme.isDark) 0.045f else 0.32f),
                Color.Transparent,
                Color.Black.copy(alpha = if (DesktopTheme.isDark) 0.055f else 0.025f),
            )))
            .hoverable(dockInteraction)
            .testTag("mini-player"),
        shape = shape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(58.dp)) {
            val compact = maxWidth < 640.dp
            val panelsInMenu = maxWidth < 440.dp
            val secondaryInMenu = maxWidth < 450.dp
            val showArtwork = maxWidth >= 380.dp && (song?.isVideo != true || maxWidth >= 480.dp)
            val transportSize = if (compact) 28.dp else 34.dp
            val toolSize = if (compact) 28.dp else 32.dp
            val menuActions = DesktopMiniPlayerMenuActions(
                enabled = song != null,
                transportInMenu = compact,
                panelsInMenu = panelsInMenu,
                secondaryInMenu = secondaryInMenu,
                shuffle = shuffle,
                repeatMode = repeatMode,
                sidePanel = sidePanel,
                onShuffleChange = onShuffleChange,
                onRepeatModeChange = onRepeatModeChange,
                onOpenLyrics = onOpenLyrics,
                onOpenQueue = onOpenQueue,
                onOpenPip = onOpenPip,
                onOpenDetails = onOpenDetails,
            )
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
                ) {
                    if (!compact) DesktopToolbarButton(onClick = { onShuffleChange(!shuffle) }, size = transportSize) {
                        Icon(DesktopIcons.Player.Shuffle, "Shuffle",
                            tint = miniPlayerActionTint(shuffle), modifier = Modifier.size(19.dp))
                    }
                    DesktopToolbarButton(onClick = onPrevious, enabled = previousEnabled, size = transportSize) {
                        Icon(painterResource(Res.drawable.player_transport_previous), "Previous",
                            tint = if (previousEnabled) foreground else foreground.copy(alpha = 0.4f),
                            modifier = Modifier.size(28.dp))
                    }
                    DesktopToolbarButton(onClick = onPlayPause, enabled = song != null, size = transportSize) {
                        if (isLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = foreground, strokeWidth = 2.dp)
                        } else {
                            Icon(painterResource(if (isPlaying) Res.drawable.player_transport_pause else Res.drawable.player_transport_play),
                                if (isPlaying) "Pause" else "Play", tint = foreground, modifier = Modifier.size(32.dp))
                        }
                    }
                    DesktopToolbarButton(onClick = onNext, enabled = song != null, size = transportSize) {
                        Icon(painterResource(Res.drawable.player_transport_next), "Next", tint = foreground, modifier = Modifier.size(28.dp))
                    }
                    if (!compact) DesktopToolbarButton(onClick = { onRepeatModeChange(repeatMode.next()) }, size = transportSize) {
                        Icon(if (repeatMode == DesktopRepeatMode.ONE) DesktopIcons.Player.RepeatOne
                            else DesktopIcons.Player.Repeat, "Repeat ${repeatMode.label()}",
                            tint = miniPlayerActionTint(repeatMode != DesktopRepeatMode.OFF),
                            modifier = Modifier.size(19.dp))
                    }
                }
                Spacer(Modifier.width(if (compact) 8.dp else 14.dp))
                if (song == null) {
                    Spacer(Modifier.weight(1f))
                } else {
                    DesktopMiniPlayerTrack(song, progress, showArtwork, dockHovered, onExpand,
                        Modifier.weight(1f))
                }
                Spacer(Modifier.width(8.dp))
                DesktopMiniPlayerTools(
                    song = song, liked = liked, onToggleLike = onToggleLike, songMenu = songMenu,
                    actions = menuActions, buttonSize = toolSize, volume = volume,
                    onVolumeChange = onVolumeChange,
                    onOpenAudioOutput = onOpenAudioOutput,
                    muted = muted, onToggleMute = onToggleMute,
                    pipOpen = pipOpen, audioOutputOpen = audioOutputOpen,
                )
            }
            if (song == null) {
                // Keep the empty-state brand geometrically centered, independent of tool widths.
                Image(painterResource(Res.drawable.bitchord_no_music), "BitChord",
                    Modifier.align(Alignment.Center).size(48.dp))
            }
        }
    }
}

@Composable
private fun DesktopMiniPlayerTrack(
    song: Song,
    progress: Float,
    showArtwork: Boolean,
    dockHovered: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val foreground = DesktopForeground
    Column(
        modifier.clip(RoundedCornerShape(6.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onExpand)
            .semantics { contentDescription = "Expand player" }
            .testTag("mini-player-track"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showArtwork) {
                Box(Modifier.trackArtSize(38.dp, song.isVideo).clip(RoundedCornerShape(6.dp)).testTag("mini-player-artwork")) {
                    DesktopAutoMixArtwork(song, ROW_ART_PX, Modifier.fillMaxSize())
                    if (dockHovered || focused) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
                            Icon(DesktopIcons.Navigation.OpenInFull, null, tint = Color.White, modifier = Modifier.size(17.dp))
                        }
                    }
                }
                Spacer(Modifier.width(9.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(song.title, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp, lineHeight = 17.sp)
                Text(song.artist, color = foreground.copy(alpha = 0.66f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    fontSize = 13.sp, lineHeight = 16.sp)
            }
        }
        // The baseline mini-player progress is read-only; keep seeking in the expanded player.
        Canvas(Modifier.fillMaxWidth().height(2.dp).testTag("mini-player-progress").semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
        }) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(foreground.copy(alpha = 0.13f), cornerRadius = radius)
            drawRoundRect(foreground.copy(alpha = 0.78f),
                size = Size(size.width * progress.coerceIn(0f, 1f), size.height), cornerRadius = radius)
        }
    }
}

internal fun miniPlayerActionTint(active: Boolean): Color =
    if (active) DesktopForeground else DesktopSecondary

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DesktopMiniPlayerTools(
    song: Song?,
    liked: Boolean,
    onToggleLike: (() -> Unit)?,
    songMenu: (@Composable (DesktopMiniPlayerMenuActions) -> Unit)?,
    actions: DesktopMiniPlayerMenuActions,
    buttonSize: Dp,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onOpenAudioOutput: (Rect) -> Unit,
    muted: Boolean,
    onToggleMute: () -> Unit,
    pipOpen: Boolean,
    audioOutputOpen: Boolean,
) {
    var regionHovered by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var volumeRequested by remember(muted) { mutableStateOf(false) }
    var awaitingSpeakerEnter by remember { mutableStateOf(false) }
    val showVolume = !muted && !awaitingSpeakerEnter && (volumeRequested || dragging)
    val duration = if (DesktopAppearanceSettings.reduceAnimation.value) 0 else 120
    Row(Modifier.height(40.dp).testTag("mini-player-actions"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(buttonSize), contentAlignment = Alignment.Center) {
            if (song != null && songMenu != null) {
                songMenu(actions)
            } else if (actions.hasOverflow) {
                DesktopMiniPlayerFallbackMenu(song != null, actions, buttonSize)
            }
        }
        Box(Modifier.size(buttonSize), contentAlignment = Alignment.Center) {
            if (song != null && onToggleLike != null) {
                Box(Modifier.fillMaxSize().clip(CircleShape).desktopHoverWash()
                    .toggleable(liked, role = Role.Checkbox, onValueChange = { onToggleLike() }),
                    contentAlignment = Alignment.Center) {
                    Icon(if (liked) DesktopIcons.Player.LikeFilled else DesktopIcons.Player.Like,
                        if (liked) DesktopStrings["remove_from_liked", "Remove from Liked Music"] else DesktopStrings["like", "Like"],
                        tint = miniPlayerActionTint(liked), modifier = Modifier.size(19.dp))
                }
            }
        }
        if (!actions.secondaryInMenu) {
            DesktopPipLauncher(actions.onOpenPip, enabled = song != null,
                tint = miniPlayerActionTint(pipOpen).let { if (song == null) it.copy(alpha = 0.4f) else it },
                iconSize = 19.dp)
            DesktopToolbarButton(onClick = actions.onOpenDetails, size = 32.dp) {
                Icon(DesktopIcons.Status.Info, "Now playing details",
                    tint = miniPlayerActionTint(actions.sidePanel == DesktopSidePanel.DETAILS),
                    modifier = Modifier.size(19.dp))
            }
        }

        Row(
            Modifier.height(40.dp)
                .onPointerEvent(PointerEventType.Enter) { regionHovered = true }
                .onPointerEvent(PointerEventType.Exit) {
                    regionHovered = false
                    if (!dragging) volumeRequested = false
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // At the narrowest widths Lyrics and Queue already live in More. Swap only the
            // inline slots, so the track keeps the same width in both layouts.
            val swapWidth = with(LocalDensity.current) {
                val panelWidths = if (actions.panelsInMenu) 0 else 2 * buttonSize.roundToPx()
                (DesktopAudioOutputButtonSize.roundToPx() + panelWidths).toDp()
            }
            Box(Modifier.width(swapWidth).height(40.dp), contentAlignment = Alignment.Center) {
                Crossfade(
                    targetState = showVolume,
                    animationSpec = if (muted || awaitingSpeakerEnter) snap() else tween(duration),
                ) { sliderVisible ->
                    if (sliderVisible) {
                        DesktopThinSlider(
                            value = volume,
                            onValueChange = {
                                dragging = true
                                onVolumeChange(it)
                            },
                            onValueChangeFinished = {
                                dragging = false
                                if (!regionHovered) volumeRequested = false
                            },
                            idleHeight = 4.dp,
                            activeHeight = 7.dp,
                            interactive = showVolume,
                            modifier = Modifier.fillMaxWidth().then(if (showVolume) {
                                Modifier.testTag("mini-player-volume")
                                    .onKeyEvent { adjustMiniPlayerVolume(it, volume, onVolumeChange) }
                                    .focusable()
                                    .semantics {
                                        contentDescription = "Volume"
                                        progressBarRangeInfo = ProgressBarRangeInfo(volume.coerceIn(0f, 1f), 0f..1f)
                                        setProgress { onVolumeChange(it.coerceIn(0f, 1f)); true }
                                    }
                            } else Modifier),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DesktopAudioOutputButton(onOpenAudioOutput, iconSize = 19.dp,
                                tint = miniPlayerActionTint(audioOutputOpen), interactive = !showVolume)
                            if (!actions.panelsInMenu) {
                                MiniPlayerPanelButton(BitChordIcons.LyricsQuote, "Lyrics", DesktopSidePanel.LYRICS,
                                    actions.sidePanel, buttonSize, !showVolume, actions.onOpenLyrics)
                                MiniPlayerPanelButton(DesktopIcons.Player.Queue, "Queue", DesktopSidePanel.QUEUE,
                                    actions.sidePanel, buttonSize, !showVolume, actions.onOpenQueue)
                            }
                        }
                    }
                }
            }

            val volumeAction = if (muted) "Unmute" else "Mute"
            TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(volumeAction) } },
                state = rememberTooltipState(), focusable = false) {
                DesktopToolbarButton(
                    onClick = {
                        awaitingSpeakerEnter = true
                        volumeRequested = false
                        dragging = false
                        onToggleMute()
                    },
                    size = buttonSize,
                    modifier = Modifier
                        .onPointerEvent(PointerEventType.Enter) {
                            if (!muted) {
                                awaitingSpeakerEnter = false
                                volumeRequested = true
                            }
                        }
                        .onKeyEvent { !muted && adjustMiniPlayerVolume(it, volume, onVolumeChange) }
                        .testTag("mini-player-volume-button"),
                ) {
                    if (muted) {
                        Icon(painterResource(Res.drawable.bin_volume_off), volumeAction,
                            tint = Color.White, modifier = Modifier.size(19.dp))
                    } else {
                        Icon(DesktopIcons.Audio.VolumeUp, volumeAction,
                            tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerPanelButton(
    icon: ImageVector,
    label: String,
    panel: DesktopSidePanel,
    activePanel: DesktopSidePanel?,
    size: Dp,
    interactive: Boolean,
    onClick: () -> Unit,
) {
    val glyph: @Composable () -> Unit = {
        Icon(icon, label.takeIf { interactive }, tint = miniPlayerActionTint(activePanel == panel),
            modifier = Modifier.size(19.dp))
    }
    if (interactive) {
        DesktopToolbarButton(onClick = onClick, size = size, content = glyph)
    } else {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) { glyph() }
    }
}

private fun adjustMiniPlayerVolume(
    event: KeyEvent,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val next = when (event.key) {
        Key.DirectionLeft, Key.DirectionDown -> volume - 0.05f
        Key.DirectionRight, Key.DirectionUp -> volume + 0.05f
        Key.MoveHome -> 0f
        Key.MoveEnd -> 1f
        else -> return false
    }
    onVolumeChange(next.coerceIn(0f, 1f))
    return true
}

@Composable
private fun DesktopMiniPlayerFallbackMenu(hasSong: Boolean, actions: DesktopMiniPlayerMenuActions, buttonSize: Dp) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (hasSong) {
            DesktopMoreButton(onClick = { open = true }, modifier = Modifier.size(buttonSize),
                tint = miniPlayerActionTint(open))
        } else {
            DesktopToolbarButton(onClick = { open = true }, size = buttonSize) {
                Icon(DesktopIcons.Settings.Tune, "Player options", tint = miniPlayerActionTint(open), modifier = Modifier.size(18.dp))
            }
        }
        if (open) DesktopMenuSurface(onDismiss = { open = false }) {
            DesktopMiniPlayerMenuItems(actions, onDismiss = { open = false })
        }
    }
}
