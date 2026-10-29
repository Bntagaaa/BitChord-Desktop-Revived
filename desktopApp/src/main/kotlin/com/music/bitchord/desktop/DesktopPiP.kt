package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.ui.player.ArtworkMeshBackdrop
import com.music.bitchord.ui.player.FullArtworkBlurBackdrop
import com.music.bitchord.ui.player.rememberArtworkMesh
import com.music.bitchord.ui.player.rememberFullArtworkBlurImage
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.player_transport_lyrics
import org.jetbrains.compose.resources.painterResource
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
    /** The existing Now Playing lookup; PiP must never run a second lyrics request. */
    val lyrics: List<LyricLine> = emptyList(),
    val lyricsLoading: Boolean = false,
    val syncedLyricsEnabled: Boolean = true,
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

    private val _showLyrics = MutableStateFlow(storage.boolean("pip_show_lyrics", true))
    val showLyrics: StateFlow<Boolean> = _showLyrics

    fun setShowLyrics(enabled: Boolean) {
        _showLyrics.value = enabled
        storage.saveBoolean("pip_show_lyrics", enabled)
    }

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
 * The window is continuously resizable; the lyrics move as space becomes available, but
 * all artwork and typography sizes interpolate with actual bounds rather than size presets.
 */
internal enum class DesktopPiPLyricsMode { HIDDEN, INLINE, BELOW, BESIDE }

internal fun desktopPiPLyricsMode(width: Dp, height: Dp, enabled: Boolean): DesktopPiPLyricsMode = when {
    !enabled -> DesktopPiPLyricsMode.HIDDEN
    width < 350.dp || height < 260.dp -> DesktopPiPLyricsMode.INLINE
    width >= 640.dp && height >= 300.dp -> DesktopPiPLyricsMode.BESIDE
    else -> DesktopPiPLyricsMode.BELOW
}

internal data class DesktopPiPLyricPreview(
    val previous: String?,
    val current: String,
    val next: String?,
    val synced: Boolean,
)

/**
 * Match the shared player's active-line rule and honour its signed lyrics offset.
 * Gaps/introductions are instrumental; missing or unsynced lyrics are not mislabeled as synced.
 */
internal fun desktopPiPActiveLyrics(
    lines: List<LyricLine>,
    playbackMs: Long,
    offsetMs: Int,
): DesktopPiPLyricPreview? {
    if (lines.isEmpty()) return null
    val sung = lines.filter { !it.isGap && it.text.isNotBlank() }
    if (sung.isEmpty()) return DesktopPiPLyricPreview(null, "♪ Instrumental", null, synced = true)
    if (lines.none { it.timeMs > 0L }) {
        return DesktopPiPLyricPreview(null, sung.first().text, sung.getOrNull(1)?.text, synced = false)
    }
    val position = (playbackMs - offsetMs.toLong()).coerceAtLeast(0L)
    val index = lines.indexOfLast { it.timeMs <= position }
    val current = lines.getOrNull(index)
    val previous = lines.take(index.coerceAtLeast(0)).lastOrNull { !it.isGap }?.text
    val next = lines.drop((index + 1).coerceAtLeast(0)).firstOrNull { !it.isGap }?.text
    return DesktopPiPLyricPreview(
        previous = previous,
        current = if (current == null || current.isGap) "♪ Instrumental" else current.text,
        next = next,
        synced = true,
    )
}

internal fun desktopPiPBlurAllowed(reduceBlur: Boolean, hasBackdrop: Boolean): Boolean =
    !reduceBlur && hasBackdrop

/**
 * Exactly the fullscreen player's prepared artwork backdrop and album-art mesh are reused here.
 * No independent Canvas decoder, live video texture, second audio engine or pink accent.
 * Reduce Blur disables the prepared blur immediately; the existing mesh remains unblurred.
 */
@Composable
internal fun DesktopPiPWindow() {
    val snapshot by DesktopPiP.playback.collectAsState()
    val pinned by DesktopPiP.alwaysOnTop.collectAsState()
    val showLyrics by DesktopPiP.showLyrics.collectAsState()
    val reduceBlur by DesktopAppearanceSettings.reduceDynamicBlur.collectAsState()
    val lyricsOffset by DesktopPlayerSettings.lyricsOffsetMs.collectAsState()
    val imageUrl = snapshot.song?.thumbnailUrl
    // Match the artwork size used by NowPlayingScreen, so Coil can reuse its cached result.
    val mesh = rememberArtworkMesh(imageUrl, artPx = PLAYER_ART_PX)
    val preparedBlur = rememberFullArtworkBlurImage(
        imageUrl = imageUrl,
        artPx = PLAYER_ART_PX,
        prepare = !reduceBlur,
    )
    val lyricPreview = remember(snapshot.lyrics, snapshot.positionMs, lyricsOffset) {
        desktopPiPActiveLyrics(snapshot.lyrics, snapshot.positionMs, lyricsOffset)
    }

    val foreground = Color.White
    val secondary = Color.White.copy(alpha = 0.67f)
    val active = Color.White

    MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography()) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF121212))) {
            // Both renderers below are the same ones used by the fullscreen player.
            ArtworkMeshBackdrop(
                mesh = mesh,
                blurRadius = if (reduceBlur) 0.dp else 14.dp,
                flat = false,
                modifier = Modifier.fillMaxSize(),
            )
            if (desktopPiPBlurAllowed(reduceBlur, preparedBlur != null)) {
                FullArtworkBlurBackdrop(
                    image = preparedBlur,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Fullscreen-style legibility scrim. This is just pigment, not a blur effect.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.17f),
                            Color.Black.copy(alpha = 0.38f),
                            Color.Black.copy(alpha = 0.53f),
                        ),
                    ),
                ),
            )

            val compact = maxHeight < 210.dp || maxWidth < 320.dp
            val lyricsMode = desktopPiPLyricsMode(maxWidth, maxHeight, showLyrics)
            val horizontalPad = if (compact) 9.dp else 14.dp
            val verticalPad = if (compact) 7.dp else 12.dp
            val between = if (compact) 3.dp else 7.dp
            val artHeightCap = when (lyricsMode) {
                DesktopPiPLyricsMode.BELOW -> maxHeight * 0.31f
                DesktopPiPLyricsMode.BESIDE -> maxHeight * 0.48f
                else -> maxHeight * 0.50f
            }
            val artworkSize = minOf(
                maxWidth * if (lyricsMode == DesktopPiPLyricsMode.BESIDE) 0.22f else 0.35f,
                artHeightCap,
                if (lyricsMode == DesktopPiPLyricsMode.BESIDE) 168.dp else 148.dp,
            ).coerceAtLeast(58.dp)

            Column(
                Modifier.fillMaxSize().padding(
                    start = horizontalPad,
                    end = horizontalPad,
                    top = verticalPad,
                    bottom = verticalPad,
                ),
                verticalArrangement = Arrangement.spacedBy(between),
            ) {
                PiPHeader(
                    pinned = pinned,
                    showLyrics = showLyrics,
                    compact = compact,
                    foreground = foreground,
                    secondary = secondary,
                )

                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 14.dp),
                ) {
                    PiPArtwork(snapshot.song, artworkSize)
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 7.dp),
                    ) {
                        PiPTrackText(snapshot, foreground, secondary)
                        if (lyricsMode == DesktopPiPLyricsMode.INLINE) {
                            PiPInlineLyric(
                                preview = lyricPreview,
                                snapshot = snapshot,
                                secondary = secondary,
                            )
                        }
                        PiPTransport(snapshot, foreground, active, compact = compact)
                    }
                    if (lyricsMode == DesktopPiPLyricsMode.BESIDE) {
                        PiPLyricsPanel(
                            preview = lyricPreview,
                            snapshot = snapshot,
                            modifier = Modifier.weight(0.95f).fillMaxSize(),
                        )
                    }
                }
                if (lyricsMode == DesktopPiPLyricsMode.BELOW) {
                    PiPLyricsPanel(
                        preview = lyricPreview,
                        snapshot = snapshot,
                        modifier = Modifier.fillMaxWidth().weight(0.80f),
                    )
                }
                PiPProgress(snapshot, active, secondary, compact)
            }
        }
    }
}

@Composable
private fun PiPHeader(
    pinned: Boolean,
    showLyrics: Boolean,
    compact: Boolean,
    foreground: Color,
    secondary: Color,
) {
    Row(
        Modifier.fillMaxWidth().height(if (compact) 27.dp else 31.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            Icons.Rounded.PictureInPictureAlt,
            "Picture in Picture",
            tint = secondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(if (compact) 1.dp else 5.dp))
        Text(
            "NOW PLAYING",
            maxLines = 1,
            color = secondary,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.weight(1f))
        PiPAction(
            description = if (showLyrics) "Hide lyrics" else "Show lyrics",
            onClick = { DesktopPiP.setShowLyrics(!showLyrics) },
            compact = true,
            selected = showLyrics,
        ) {
            Icon(
                painterResource(Res.drawable.player_transport_lyrics),
                if (showLyrics) "Hide lyrics" else "Show lyrics",
                tint = if (showLyrics) foreground else secondary,
                modifier = Modifier.size(17.dp),
            )
        }
        PiPAction("Always on top", { DesktopPiP.setAlwaysOnTop(!pinned) }, compact = true, selected = pinned) {
            Icon(
                Icons.Rounded.PushPin,
                "Always on top",
                tint = if (pinned) foreground else secondary,
                modifier = Modifier.size(16.dp),
            )
        }
        PiPAction("Return to BitChord", DesktopPiP::restoreMainWindow, compact = true) {
            Icon(Icons.Rounded.OpenInFull, "Return to BitChord", tint = secondary, modifier = Modifier.size(16.dp))
        }
        PiPAction("Close PiP", DesktopPiP::close, compact = true) {
            Icon(Icons.Rounded.Close, "Close PiP", tint = secondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PiPInlineLyric(
    preview: DesktopPiPLyricPreview?,
    snapshot: DesktopPiPPlayback,
    secondary: Color,
) {
    val lyric = when {
        !snapshot.syncedLyricsEnabled -> "Synced lyrics disabled"
        snapshot.lyricsLoading -> "Loading lyrics…"
        preview == null -> "Lyrics unavailable"
        else -> preview.current
    }
    Text(
        text = lyric,
        color = if (preview != null && snapshot.syncedLyricsEnabled) Color.White.copy(alpha = 0.91f) else secondary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (preview?.synced == true) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PiPLyricsPanel(
    preview: DesktopPiPLyricPreview?,
    snapshot: DesktopPiPPlayback,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(13.dp)
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.22f))
            .border(0.6.dp, Color.White.copy(alpha = 0.12f), shape),
    ) {
        val showPrevious = maxHeight >= 158.dp
        val showNext = maxHeight >= 95.dp
        Column(
            Modifier.fillMaxSize().padding(
                horizontal = if (maxWidth < 280.dp) 10.dp else 14.dp,
                vertical = if (maxHeight < 115.dp) 7.dp else 12.dp,
            ),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (preview?.synced == false) "LYRICS · UNSYNCED" else "LYRICS",
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            if (showPrevious && !preview?.previous.isNullOrBlank()) {
                Text(
                    preview!!.previous!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val activeLine = when {
                !snapshot.syncedLyricsEnabled -> "Enable synced lyrics in Settings"
                snapshot.lyricsLoading -> "Fetching lyrics…"
                preview == null -> "Lyrics unavailable for this track"
                else -> preview.current
            }
            Text(
                text = activeLine,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = if (maxHeight >= 180.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                maxLines = if (maxHeight >= 140.dp) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showNext && !preview?.next.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = preview!!.next!!,
                    color = Color.White.copy(alpha = 0.54f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (maxHeight >= 165.dp) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
