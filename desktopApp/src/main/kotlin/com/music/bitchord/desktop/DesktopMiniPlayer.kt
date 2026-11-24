package com.music.bitchord.desktop

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.bitchord_no_music
import bitchord.desktopapp.generated.resources.player_transport_lyrics
import bitchord.desktopapp.generated.resources.player_transport_next
import bitchord.desktopapp.generated.resources.player_transport_pause
import bitchord.desktopapp.generated.resources.player_transport_play
import bitchord.desktopapp.generated.resources.player_transport_previous
import bitchord.desktopapp.generated.resources.player_transport_repeat
import bitchord.desktopapp.generated.resources.player_transport_repeat_one
import bitchord.desktopapp.generated.resources.player_transport_shuffle
import bitchord.desktopapp.generated.resources.player_transport_up_next
import com.music.bitchord.ui.components.trackArtSize
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

/** Extra desktop tools share the track's existing overflow, including at narrow widths. */
internal data class DesktopMiniPlayerMenuActions(
    val enabled: Boolean,
    val transportInMenu: Boolean,
    val panelsInMenu: Boolean,
    val shuffle: Boolean,
    val repeatMode: DesktopRepeatMode,
    val sidePanel: DesktopSidePanel?,
    val onShuffleChange: (Boolean) -> Unit,
    val onRepeatModeChange: (DesktopRepeatMode) -> Unit,
    val onOpenLyrics: () -> Unit,
    val onOpenQueue: () -> Unit,
    val onOpenPip: () -> Unit,
    val onOpenDetails: () -> Unit,
    val onOpenAudioOutput: () -> Unit,
)

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
) {
    val shape = RoundedCornerShape(percent = 50)
    val dockInteraction = remember { MutableInteractionSource() }
    val dockHovered by dockInteraction.collectIsHoveredAsState()
    val foreground = DesktopForeground
    val dockSurface = if (DesktopAppearanceSettings.blurEnabled(DesktopBlurArea.FLOATING_PLAYER)) {
        Modifier.desktopBarGlass(shape, DesktopBlurArea.FLOATING_PLAYER)
    } else {
        Modifier.background(DesktopTheme.palette.floatingPlayerSurface, shape)
    }
    Surface(
        modifier = Modifier
            .widthIn(max = 736.dp)
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
            .border(1.dp, foreground.copy(alpha = if (DesktopTheme.isDark) 0.20f else 0.14f), shape)
            .hoverable(dockInteraction)
            .testTag("mini-player"),
        shape = shape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
            val compact = maxWidth < 640.dp
            val panelsInMenu = maxWidth < 440.dp
            val showArtwork = maxWidth >= 380.dp
            val buttonSize = if (compact) 30.dp else 36.dp
            var outputAnchor by remember { mutableStateOf<Rect?>(null) }
            val menuActions = DesktopMiniPlayerMenuActions(
                enabled = song != null,
                transportInMenu = compact,
                panelsInMenu = panelsInMenu,
                shuffle = shuffle,
                repeatMode = repeatMode,
                sidePanel = sidePanel,
                onShuffleChange = onShuffleChange,
                onRepeatModeChange = onRepeatModeChange,
                onOpenLyrics = onOpenLyrics,
                onOpenQueue = onOpenQueue,
                onOpenPip = onOpenPip,
                onOpenDetails = onOpenDetails,
                onOpenAudioOutput = { outputAnchor?.let(onOpenAudioOutput) },
            )
            Row(
                Modifier.fillMaxSize().padding(horizontal = if (compact) 12.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 5.dp),
                ) {
                    if (!compact) DesktopToolbarButton(onClick = { onShuffleChange(!shuffle) }, size = buttonSize) {
                        Icon(painterResource(Res.drawable.player_transport_shuffle), "Shuffle",
                            tint = if (shuffle) DesktopAccent else DesktopSecondary, modifier = Modifier.size(30.dp))
                    }
                    DesktopToolbarButton(onClick = onPrevious, enabled = previousEnabled, size = buttonSize) {
                        Icon(painterResource(Res.drawable.player_transport_previous), "Previous",
                            tint = if (previousEnabled) foreground else DesktopSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(28.dp))
                    }
                    DesktopToolbarButton(onClick = onPlayPause, enabled = song != null, size = buttonSize) {
                        if (isLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = foreground, strokeWidth = 2.dp)
                        } else {
                            Icon(painterResource(if (isPlaying) Res.drawable.player_transport_pause else Res.drawable.player_transport_play),
                                if (isPlaying) "Pause" else "Play", tint = foreground, modifier = Modifier.size(32.dp))
                        }
                    }
                    DesktopToolbarButton(onClick = onNext, enabled = song != null, size = buttonSize) {
                        Icon(painterResource(Res.drawable.player_transport_next), "Next", tint = foreground, modifier = Modifier.size(28.dp))
                    }
                    if (!compact) DesktopToolbarButton(onClick = { onRepeatModeChange(repeatMode.next()) }, size = buttonSize) {
                        Icon(painterResource(if (repeatMode == DesktopRepeatMode.ONE) Res.drawable.player_transport_repeat_one
                            else Res.drawable.player_transport_repeat), "Repeat ${repeatMode.label()}",
                            tint = if (repeatMode == DesktopRepeatMode.OFF) DesktopSecondary else DesktopAccent,
                            modifier = Modifier.size(32.dp))
                    }
                }
                Spacer(Modifier.width(if (compact) 8.dp else 22.dp))
                if (song == null) {
                    Spacer(Modifier.weight(1f))
                } else {
                    DesktopMiniPlayerTrack(song, progress, showArtwork, dockHovered, onExpand,
                        Modifier.weight(1f))
                }
                Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
                DesktopMiniPlayerTools(
                    song = song, liked = liked, onToggleLike = onToggleLike, songMenu = songMenu,
                    actions = menuActions, buttonSize = buttonSize, volume = volume,
                    onVolumeChange = onVolumeChange,
                    onOutputAnchor = { outputAnchor = it },
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
                    DesktopArtwork(song.thumbnailUrl, Modifier.fillMaxSize(), px = ROW_ART_PX)
                    if (dockHovered || focused) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.OpenInFull, null, tint = Color.White, modifier = Modifier.size(17.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
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
    onOutputAnchor: (Rect) -> Unit,
) {
    val toolsInteraction = remember { MutableInteractionSource() }
    val toolsHovered by toolsInteraction.collectIsHoveredAsState()
    val speakerInteraction = remember { MutableInteractionSource() }
    val speakerHovered by speakerInteraction.collectIsHoveredAsState()
    val sliderInteraction = remember { MutableInteractionSource() }
    val dragging by sliderInteraction.collectIsDraggedAsState()
    val sliderFocused by sliderInteraction.collectIsFocusedAsState()
    var speakerFocused by remember { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(false) }
    val duration = if (DesktopAppearanceSettings.reduceAnimation.value) 0 else 120
    LaunchedEffect(toolsHovered, speakerHovered, speakerFocused, sliderFocused, dragging) {
        if (speakerHovered || speakerFocused || sliderFocused || dragging) {
            showVolume = true
        } else if (!toolsHovered) {
            // A short grace period lets the pointer cross from the speaker onto the slider.
            delay(160)
            showVolume = false
        }
    }
    val foreground = DesktopForeground
    val actionSlots = if (actions.panelsInMenu) 2 else 4
    Row(
        Modifier.width(buttonSize * (actionSlots + 1)).height(40.dp)
            .hoverable(toolsInteraction).testTag("mini-player-actions"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(buttonSize * actionSlots).height(40.dp), contentAlignment = Alignment.CenterEnd) {
            androidx.compose.animation.AnimatedVisibility(!showVolume, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(buttonSize), contentAlignment = Alignment.Center) {
                        if (song != null && onToggleLike != null) {
                            Box(Modifier.fillMaxSize().clip(CircleShape).desktopHoverWash()
                                .toggleable(liked, role = Role.Checkbox, onValueChange = { onToggleLike() }),
                                contentAlignment = Alignment.Center) {
                                Icon(if (liked) BitChordIcons.StarFilled else BitChordIcons.Star,
                                    if (liked) DesktopStrings["remove_from_liked", "Remove from Liked Music"] else DesktopStrings["like", "Like"],
                                    tint = if (liked) DesktopAccent else foreground.copy(alpha = 0.82f), modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                    Box(Modifier.size(buttonSize), contentAlignment = Alignment.Center) {
                        if (song != null && songMenu != null) {
                            songMenu(actions)
                        } else {
                            DesktopMiniPlayerFallbackMenu(song != null, actions, buttonSize)
                        }
                    }
                    if (!actions.panelsInMenu) {
                        DesktopToolbarButton(onClick = actions.onOpenLyrics, size = buttonSize) {
                            Icon(painterResource(Res.drawable.player_transport_lyrics), "Lyrics",
                                tint = if (actions.sidePanel == DesktopSidePanel.LYRICS) DesktopAccent else foreground.copy(alpha = 0.82f),
                                modifier = Modifier.size(19.dp))
                        }
                        DesktopToolbarButton(onClick = actions.onOpenQueue, size = buttonSize) {
                            Icon(painterResource(Res.drawable.player_transport_up_next), "Queue",
                                tint = if (actions.sidePanel == DesktopSidePanel.QUEUE) DesktopAccent else foreground.copy(alpha = 0.82f),
                                modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(showVolume, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration))) {
                Slider(
                    value = volume.coerceIn(0f, 1f), onValueChange = onVolumeChange,
                    interactionSource = sliderInteraction,
                    modifier = Modifier.widthIn(max = 116.dp).fillMaxWidth().height(36.dp)
                        .padding(horizontal = 8.dp).testTag("mini-player-volume")
                        .semantics { contentDescription = "Volume" },
                    thumb = { Canvas(Modifier.size(12.dp)) { drawCircle(foreground) } },
                    track = { slider ->
                        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                            val radius = CornerRadius(size.height / 2)
                            drawRoundRect(foreground.copy(alpha = 0.22f), cornerRadius = radius)
                            drawRoundRect(foreground,
                                size = Size(size.width * slider.value.coerceIn(0f, 1f), size.height), cornerRadius = radius)
                        }
                    },
                )
            }
        }
        // Click retains Revived's output picker; hover/focus exposes the persisted volume slider.
        DesktopToolbarButton(
            onClick = actions.onOpenAudioOutput, size = buttonSize,
            modifier = Modifier.hoverable(speakerInteraction)
                .onFocusChanged { speakerFocused = it.isFocused }
                .onGloballyPositioned { onOutputAnchor(it.boundsInRoot()) }
                .testTag("mini-player-volume-button"),
        ) {
            Icon(if (volume <= 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                DesktopStrings["audio_output", "Audio output"], tint = foreground.copy(alpha = 0.88f), modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun DesktopMiniPlayerFallbackMenu(hasSong: Boolean, actions: DesktopMiniPlayerMenuActions, buttonSize: Dp) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (hasSong) {
            DesktopMoreButton(onClick = { open = true }, modifier = Modifier.size(buttonSize))
        } else {
            DesktopToolbarButton(onClick = { open = true }, size = buttonSize) {
                Icon(Icons.Rounded.Tune, "Player options", tint = DesktopSecondary, modifier = Modifier.size(18.dp))
            }
        }
        if (open) DesktopMenuSurface(onDismiss = { open = false }) {
            DesktopMiniPlayerMenuItems(actions, onDismiss = { open = false })
        }
    }
}
