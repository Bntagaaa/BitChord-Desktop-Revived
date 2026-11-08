package com.music.bitchord.reworked

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import coil3.compose.AsyncImage
import com.music.bitchord.ui.player.FullArtworkBlurBackdrop
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.player.rememberFullArtworkBlurImage
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

/** Host beside the main Window so minimizing main cannot suspend the floating player. */
@Composable
internal fun ReworkedPipHost(
    player: DesktopPlayback,
    onRestore: () -> Unit,
    lyrics: () -> ReworkedPipLyrics = { ReworkedPipLyrics() },
    isLiked: (String) -> Boolean = { false },
    onToggleLike: (Song) -> Unit = {},
) {
    val isOpen by ReworkedPip.isOpen.collectAsState()
    if (!isOpen) return
    ReworkedPipWindow(player, onRestore, lyrics, isLiked, onToggleLike)
}

@Composable
private fun ReworkedPipWindow(
    player: DesktopPlayback,
    onRestore: () -> Unit,
    lyrics: () -> ReworkedPipLyrics,
    isLiked: (String) -> Boolean,
    onToggleLike: (Song) -> Unit,
) {
    fun restore() { ReworkedPip.close(); onRestore() }
    val saved = remember {
        ReworkedPipPreferences.bounds().fit(runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
                val configuration = device.defaultConfiguration
                val bounds = configuration.bounds
                val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
                Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                    bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom)
            }
        }.getOrDefault(emptyList()))
    }
    val state = rememberWindowState(
        position = WindowPosition(saved.x.dp, saved.y.dp),
        size = DpSize(saved.width.dp, saved.height.dp),
    )
    val pinned by ReworkedPipPreferences.pinned.collectAsState()
    val canPin = remember { runCatching { Toolkit.getDefaultToolkit().isAlwaysOnTopSupported }.getOrDefault(false) }
    Window(
        onCloseRequest = ::restore,
        state = state,
        title = "BitChord Reworked · Mini player",
        alwaysOnTop = pinned && canPin,
        resizable = true,
        onPreviewKeyEvent = { event ->
            when {
                event.key == Key.Escape -> {
                    if (event.type == KeyEventType.KeyUp) restore()
                    true
                }
                event.key == Key.Spacebar -> {
                    if (event.type == KeyEventType.KeyUp) player.toggle()
                    true
                }
                else -> false
            }
        },
    ) {
        val nativeWindow = window
        DisposableEffect(nativeWindow) {
            nativeWindow.minimumSize = Dimension(320, 220)
            nativeWindow.background = java.awt.Color(24, 24, 28)
            onDispose { ReworkedPipPreferences.saveBounds(nativeWindow.bounds) }
        }
        ReworkedTheme(darkTheme = true) {
            MaterialTheme(colorScheme = darkColorScheme(), typography = MaterialTheme.typography) {
                ReworkedPipContent(player, lyrics, isLiked, onToggleLike, ::restore, pinned, canPin)
            }
        }
    }
}

@Composable
internal fun ReworkedPipContent(
    player: DesktopPlayback,
    lyrics: () -> ReworkedPipLyrics,
    isLiked: (String) -> Boolean,
    onToggleLike: (Song) -> Unit,
    onRestore: () -> Unit,
    pinned: Boolean,
    canPin: Boolean,
) {
    val playback by player.state.collectAsState()
    val showLyrics by ReworkedPipPreferences.lyrics.collectAsState()
    val reducedBlur by PlayerPlatform.host.settings.reduceDynamicBlur.collectAsState()
    val offset by PlayerPlatform.host.settings.lyricsOffsetMs.collectAsState()
    val song = playback.song
    val words = reworkedPipLyric(lyrics(), song?.videoId, playback.positionMs - offset)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF18181C))) {
        val windowHeight = maxHeight
        if (!reducedBlur) {
            val backdrop = rememberFullArtworkBlurImage(song?.thumbnailUrl)
            FullArtworkBlurBackdrop(backdrop)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onRestore) {
                    Icon(Icons.Rounded.FullscreenExit, "Return to main window", tint = Color.White)
                }
                IconButton(onClick = { ReworkedPipPreferences.setLyrics(!showLyrics) }) {
                    Icon(Icons.Rounded.Lyrics, "Toggle lyrics", tint = if (showLyrics) Color.White else Color.White.copy(alpha = 0.5f))
                }
                IconButton(onClick = { ReworkedPipPreferences.setPinned(!pinned) }, enabled = canPin) {
                    Icon(Icons.Rounded.PushPin, "Keep mini player on top", tint = if (pinned && canPin) Color.White else Color.White.copy(alpha = 0.5f))
                }
            }
            val roomy = windowHeight >= 360.dp
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = song?.thumbnailUrl,
                    contentDescription = "Album artwork",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(if (roomy) 112.dp else 68.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song?.title ?: "Nothing playing", color = Color.White, fontWeight = FontWeight.SemiBold,
                        fontSize = if (roomy) 21.sp else 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song?.artist.orEmpty(), color = Color.White.copy(alpha = 0.75f),
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    playback.error?.let { Text(it, color = Color(0xFFFFAAAA), fontSize = 11.sp, maxLines = 2) }
                }
                if (song != null) {
                    val liked = isLiked(song.videoId)
                    IconButton(onClick = { onToggleLike(song) }) {
                        Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (liked) "Unlike song" else "Like song", tint = Color.White)
                    }
                }
            }
            if (showLyrics && windowHeight >= 290.dp) {
                AnimatedContent(words ?: "Lyrics unavailable", label = "miniPlayerLyrics",
                    modifier = Modifier.fillMaxWidth().height(if (roomy) 76.dp else 46.dp)) { text ->
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                        Text(text, color = Color.White, fontSize = if (roomy) 17.sp else 13.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
            var scrubbing by remember(song?.videoId) { mutableStateOf(false) }
            var scrub by remember(song?.videoId) { mutableFloatStateOf(0f) }
            val duration = playback.durationMs
            Slider(
                value = if (scrubbing) scrub else if (duration > 0) (playback.positionMs.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f,
                onValueChange = { scrubbing = true; scrub = it },
                onValueChangeFinished = {
                    if (duration > 0) player.seek((duration * scrub).toLong())
                    scrubbing = false
                },
                enabled = song != null && duration > 0,
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = player::previous, enabled = song != null) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous track", tint = Color.White)
                }
                IconButton(onClick = player::toggle, enabled = song != null) {
                    Icon(if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (playback.isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = player::next, enabled = song != null) {
                    Icon(Icons.Rounded.SkipNext, "Next track", tint = Color.White)
                }
            }
        }
    }
}

@Composable
internal fun ReworkedPipSettings(canCloseToTray: Boolean = true) {
    val onClose by ReworkedPipPreferences.onClose.collectAsState()
    if (canCloseToTray) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Open mini player when closing")
                Text("Keep playback controls available in a floating window", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = onClose, onCheckedChange = ReworkedPipPreferences::setOnClose)
        }
    }
}
