package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.unit.DpSize
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Lightweight projection of the existing audio engine; PiP never owns a second player. */
internal data class DesktopPiPPlayback(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val previousEnabled: Boolean = false,
    val nextEnabled: Boolean = false,
)

/**
 * Application-scope window and transport controller. The main composable publishes the same
 * playback state and the same user-intent callbacks it supplies to the existing player.
 */
internal object DesktopPiP {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible

    private val _playback = MutableStateFlow(DesktopPiPPlayback())
    val playback: StateFlow<DesktopPiPPlayback> = _playback

    private val storage = DesktopPersistence()
    private val _alwaysOnTop = MutableStateFlow(storage.boolean("pip_always_on_top", true))
    val alwaysOnTop: StateFlow<Boolean> = _alwaysOnTop

    @Volatile private var playPause: () -> Unit = {}
    @Volatile private var previous: () -> Unit = {}
    @Volatile private var next: () -> Unit = {}
    @Volatile private var seek: (Long) -> Unit = {}

    fun publish(
        snapshot: DesktopPiPPlayback,
        onPlayPause: () -> Unit,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
        onSeek: (Long) -> Unit,
    ) {
        _playback.value = snapshot
        playPause = onPlayPause
        previous = onPrevious
        next = onNext
        seek = onSeek
    }

    fun togglePlayback() = playPause()
    fun skipPrevious() = previous()
    fun skipNext() = next()
    fun seekTo(ms: Long) = seek(ms)

    /** Enter PiP from the player button, or from the configured close-to-tray behavior. */
    fun open() {
        DesktopWindowMode.exit()
        _visible.value = true
        DesktopWindowVisibility.hideForPiP()
    }

    /** Called when the main window is raised from the tray/MPRIS. */
    fun hide() {
        _visible.value = false
    }

    /** PiP's X behaves as close-to-tray when available; otherwise restore the main window. */
    fun close() {
        _visible.value = false
        if (!DesktopWindowVisibility.visible.value && !DesktopWindowVisibility.keepRunningWhenClosed) {
            DesktopWindowVisibility.raise()
        }
    }

    /** Explicit restore is always available, including when close-to-tray is enabled. */
    fun restoreMainWindow() {
        _visible.value = false
        DesktopWindowVisibility.raise()
    }

    fun setAlwaysOnTop(value: Boolean) {
        _alwaysOnTop.value = value
        storage.saveBoolean("pip_always_on_top", value)
    }
}

/** A native, continuously resizable window; these values are preferences, not size presets. */
internal object DesktopPiPWindowBounds {
    const val minWidth = 270
    const val minHeight = 160
    private const val maxWidth = 1_280
    private const val maxHeight = 960
    private val storage = DesktopPersistence()

    fun initialSize(): DpSize = DpSize(
        storage.int("pip_width_dp", 440).coerceIn(minWidth, maxWidth).dp,
        storage.int("pip_height_dp", 280).coerceIn(minHeight, maxHeight).dp,
    )

    fun initialPosition(): WindowPosition {
        val x = storage.int("pip_x_dp", Int.MIN_VALUE)
        val y = storage.int("pip_y_dp", Int.MIN_VALUE)
        return if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) {
            WindowPosition.PlatformDefault
        } else {
            WindowPosition.Absolute(x.dp, y.dp)
        }
    }

    fun saveSize(size: DpSize) {
        storage.saveInt("pip_width_dp", size.width.value.roundToInt().coerceIn(minWidth, maxWidth))
        storage.saveInt("pip_height_dp", size.height.value.roundToInt().coerceIn(minHeight, maxHeight))
    }

    fun savePosition(position: WindowPosition) {
        if (position is WindowPosition.Absolute) {
            storage.saveInt("pip_x_dp", position.x.value.roundToInt())
            storage.saveInt("pip_y_dp", position.y.value.roundToInt())
        }
    }
}

/**
 * Two fluid compositions, chosen by available space rather than fixed size modes.
 * The native window can be resized to *any* intermediate width and height.
 * No continuously playing Canvas or expensive blur shader is mounted in this window.
 */
@Composable
internal fun DesktopPiPWindow() {
    val snapshot by DesktopPiP.playback.collectAsState()
    val pinned by DesktopPiP.alwaysOnTop.collectAsState()
    val reduceBlur by DesktopAppearanceSettings.reduceDynamicBlur.collectAsState()
    val palette = DesktopTheme.palette
    val surface = if (DesktopTheme.isDark) Color(0xFF17181D) else Color(0xFFF8F8FB)
    val tint = if (DesktopTheme.isDark) Color.White else Color(0xFF181920)
    val muted = if (DesktopTheme.isDark) Color(0xFFB5B5C3) else Color(0xFF5B5D69)
    val accent = Color(0xFFFF536F)

    MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography()) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(surface),
        ) {
            val compact = maxHeight < 210.dp || maxWidth < 330.dp
            val inset = if (compact) 10.dp else 16.dp
            val artSize = if (compact) {
                (maxHeight - 54.dp).coerceIn(48.dp, 84.dp)
            } else {
                minOf(maxWidth * 0.34f, maxHeight * 0.47f, 156.dp).coerceAtLeast(78.dp)
            }
            // Gradient tint instead of an extra live blur renderer. The Reduce Blur master switch
            // removes it entirely, preserving the same global invariant as the other surfaces.
            if (!reduceBlur) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            listOf(
                                accent.copy(alpha = if (DesktopTheme.isDark) 0.16f else 0.10f),
                                Color(0xFF6960D8).copy(alpha = 0.13f),
                                Color.Transparent,
                            ),
                        ),
                    ),
                )
            }

            Column(
                Modifier.fillMaxSize().padding(inset),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.PictureInPictureAlt,
                        "Picture in Picture",
                        tint = muted,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    PiPAction("Always on top", { DesktopPiP.setAlwaysOnTop(!pinned) }, compact = true) {
                        Icon(
                            Icons.Rounded.PushPin,
                            "Always on top",
                            tint = if (pinned) accent else muted,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    PiPAction("Return to BitChord", DesktopPiP::restoreMainWindow, compact = true) {
                        Icon(Icons.Rounded.OpenInFull, "Return to BitChord", tint = muted, modifier = Modifier.size(17.dp))
                    }
                    PiPAction("Close PiP", DesktopPiP::close, compact = true) {
                        Icon(Icons.Rounded.Close, "Close PiP", tint = muted, modifier = Modifier.size(18.dp))
                    }
                }

                if (compact) {
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PiPArtwork(snapshot.song, artSize)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            PiPTrackText(snapshot, tint, muted)
                            PiPTransport(snapshot, tint, accent, compact = true)
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        PiPArtwork(snapshot.song, artSize)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PiPTrackText(snapshot, tint, muted)
                            PiPTransport(snapshot, tint, accent, compact = false)
                        }
                    }
                }

                PiPProgress(snapshot, accent, muted, compact)
            }
        }
    }
}

@Composable
private fun PiPArtwork(song: Song?, size: Dp) {
    val shape = RoundedCornerShape(10.dp)
    if (song != null) {
        DesktopArtwork(
            url = song.thumbnailUrl,
            px = 320,
            modifier = Modifier.size(size).clip(shape),
        )
    } else {
        Box(
            Modifier.size(size).clip(shape).background(Color(0xFF353540)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PictureInPictureAlt, "BitChord", tint = Color.LightGray)
        }
    }
}

@Composable
private fun PiPTrackText(snapshot: DesktopPiPPlayback, foreground: Color, secondary: Color) {
    Text(
        snapshot.song?.title ?: "Nothing playing",
        color = foreground,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        snapshot.song?.artist ?: "Open BitChord to choose a song",
        color = secondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun PiPTransport(snapshot: DesktopPiPPlayback, foreground: Color, accent: Color, compact: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp),
    ) {
        PiPAction("Previous", DesktopPiP::skipPrevious, enabled = snapshot.previousEnabled, compact = compact) {
            Icon(Icons.Rounded.SkipPrevious, "Previous", tint = foreground.copy(alpha = if (snapshot.previousEnabled) 1f else .35f))
        }
        PiPAction("Play or pause", DesktopPiP::togglePlayback, enabled = snapshot.song != null, compact = compact) {
            if (snapshot.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = accent,
                )
            } else {
                Icon(
                    if (snapshot.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (snapshot.isPlaying) "Pause" else "Play",
                    tint = if (snapshot.song == null) foreground.copy(alpha = .35f) else accent,
                    modifier = Modifier.size(if (compact) 24.dp else 28.dp),
                )
            }
        }
        PiPAction("Next", DesktopPiP::skipNext, enabled = snapshot.nextEnabled, compact = compact) {
            Icon(Icons.Rounded.SkipNext, "Next", tint = foreground.copy(alpha = if (snapshot.nextEnabled) 1f else .35f))
        }
    }
}

@Composable
private fun PiPProgress(snapshot: DesktopPiPPlayback, accent: Color, secondary: Color, compact: Boolean) {
    val fraction = if (snapshot.durationMs > 0L) {
        (snapshot.positionMs.toFloat() / snapshot.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    var scrubbing by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val progress = if (scrubbing) dragFraction else fraction

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (!compact) Text(
            timeLabel(snapshot.positionMs),
            style = MaterialTheme.typography.labelSmall,
            color = secondary,
        )
        Slider(
            value = progress,
            onValueChange = { scrubbing = true; dragFraction = it },
            onValueChangeFinished = {
                if (snapshot.durationMs > 0L) {
                    DesktopPiP.seekTo((dragFraction * snapshot.durationMs).toLong())
                }
                scrubbing = false
            },
            enabled = snapshot.song != null && snapshot.durationMs > 0L,
            modifier = Modifier.weight(1f).height(if (compact) 18.dp else 28.dp),
        )
        if (!compact) Text(
            timeLabel(snapshot.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = secondary,
        )
    }
}

private fun timeLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1_000L
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun PiPAction(
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(if (compact) 27.dp else 36.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
