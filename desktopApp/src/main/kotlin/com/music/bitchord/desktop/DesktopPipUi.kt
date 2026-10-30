package com.music.bitchord.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchord.desktopapp.generated.resources.*
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.graphics.toImageBitmap
import com.music.bitchord.ui.player.FullArtworkBlurBackdrop
import com.music.bitchord.ui.player.rememberFloatingLyricsClock
import com.music.bitchord.ui.player.rememberFullArtworkBlurImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun DesktopPipUi(
    state: DesktopPipState,
    position: PlaybackPosition,
    preferences: DesktopPipPreferences,
    reduceBlur: Boolean,
    reduceAnimation: Boolean,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    onPin: () -> Unit,
    onLyrics: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    pinSupported: Boolean = true,
) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("pip-root")) {
        val layout = DesktopPipLayout.at(maxWidth.value, maxHeight.value)
        val art = rememberPipArtwork(state.song?.thumbnailUrl)
        PipBackground(state.song?.thumbnailUrl, art, reduceBlur, reduceAnimation)
        CompositionLocalProvider(LocalContentColor provides Color.White,
            LocalMinimumInteractiveComponentSize provides 0.dp) {
            Column(Modifier.fillMaxSize().padding(layout.padding.dp), verticalArrangement = Arrangement.spacedBy(layout.gap.dp)) {
                Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("BitChord", fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                        color = Color.White.copy(alpha = .6f), modifier = Modifier.weight(1f), maxLines = 1)
                    PipAction("Return to main window", Icons.AutoMirrored.Rounded.OpenInNew, onRestore)
                    PipAction(if (preferences.pinned) "Disable always on top" else "Always on top", Icons.Rounded.PushPin,
                        onPin, selected = preferences.pinned, enabled = pinSupported)
                    PipAction(if (preferences.lyrics) "Hide lyrics" else "Show lyrics", Icons.Rounded.Lyrics,
                        onLyrics, selected = preferences.lyrics)
                    PipAction("Close picture in picture", Icons.Rounded.Close, onClose)
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val bodyWidth = maxWidth
                    val bodyHeight = maxHeight
                    if (layout.sideBySide) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy((layout.gap * 2).dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            val aspect = if (state.song?.isVideo == true) 16f / 9 else 1f
                            val width = minOf(bodyWidth * .36f, bodyHeight * aspect, 240.dp)
                            PipCover(art, Modifier.width(width).aspectRatio(aspect))
                            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(layout.gap.dp)) {
                                PipMetadata(state, layout)
                                if (preferences.lyrics) PipLyrics(state, position, layout.lyricSp, reduceAnimation,
                                    Modifier.weight(1f).fillMaxWidth())
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(layout.gap.dp)) {
                            val aspect = if (state.song?.isVideo == true) 16f / 9 else 1f
                            val height = minOf(bodyHeight * (if (preferences.lyrics) .48f else .9f), bodyWidth * .32f / aspect, 144.dp)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy((layout.gap * 2).dp)) {
                                PipCover(art, Modifier.height(height).aspectRatio(aspect))
                                Box(Modifier.weight(1f)) { PipMetadata(state, layout) }
                            }
                            if (preferences.lyrics) PipLyrics(state, position, layout.lyricSp, reduceAnimation, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
                PipProgress(state, position, onSeek)
                Row(Modifier.fillMaxWidth().height(layout.transport.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = onPrevious, enabled = state.previous && !state.controlsLocked,
                        modifier = Modifier.size(layout.transport.dp).testTag("pip-previous")) {
                        Icon(painterResource(Res.drawable.player_transport_previous), "Previous", tint = Color.White.copy(alpha = if (state.previous && !state.controlsLocked) 1f else .3f), modifier = Modifier.size(23.dp))
                    }
                    Spacer(Modifier.width(layout.gap.dp * 3))
                    FilledIconButton(onClick = onPlayPause, enabled = state.song != null && !state.controlsLocked,
                        modifier = Modifier.size(layout.transport.dp).testTag("pip-play"),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = .17f), contentColor = Color.White)) {
                        if (state.loading || state.buffering) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(painterResource(if (state.playing) Res.drawable.player_transport_pause else Res.drawable.player_transport_play),
                            if (state.playing) "Pause" else "Play", modifier = Modifier.size(23.dp))
                    }
                    Spacer(Modifier.width(layout.gap.dp * 3))
                    IconButton(onClick = onNext, enabled = state.next && !state.controlsLocked,
                        modifier = Modifier.size(layout.transport.dp).testTag("pip-next")) {
                        Icon(painterResource(Res.drawable.player_transport_next), "Next", tint = Color.White.copy(alpha = if (state.next && !state.controlsLocked) 1f else .3f), modifier = Modifier.size(23.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberPipArtwork(url: String?): ImageBitmap? {
    val context = LocalPlatformContext.current
    return key(url) {
        val image by produceState<ImageBitmap?>(null, url) {
            if (url != null) {
                val request = ImageRequest.Builder(context).data(url.artworkAt(CARD_ART_PX)).size(512).build()
                val loaded = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                value = loaded?.image?.toImageBitmap()
            }
        }
        image
    }
}

@Composable
private fun PipBackground(url: String?, art: ImageBitmap?, reduceBlur: Boolean, reduceAnimation: Boolean) {
    // Do not even request/precompute a blurred image with the global override enabled.
    val prepared = if (!reduceBlur) rememberFullArtworkBlurImage(url, CARD_ART_PX, prepare = art != null, bitmapOverride = art) else art
    var previous by remember { mutableStateOf<ImageBitmap?>(null) }
    // A previous song's blur must never survive toggling Reduce Blur.
    LaunchedEffect(reduceBlur) { previous = null }
    if (prepared != null || url == null) SideEffect { previous = prepared }
    val image = prepared ?: previous.takeUnless { reduceBlur }
    Crossfade(targetState = image, animationSpec = tween(if (reduceAnimation || reduceBlur) 0 else 260),
        modifier = Modifier.fillMaxSize().testTag(if (reduceBlur) "pip-background-sharp" else "pip-background-prepared"), label = "pip artwork") { bitmap ->
        FullArtworkBlurBackdrop(bitmap, Modifier.testTag(if (bitmap != null) "pip-background-image" else "pip-background-empty"))
    }
}

@Composable
private fun PipCover(image: ImageBitmap?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .06f)).testTag("pip-cover")) {
        if (image != null) Image(BitmapPainter(image), "Current artwork", Modifier.fillMaxSize().testTag("pip-artwork"), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun PipMetadata(state: DesktopPipState, layout: DesktopPipLayout) {
    Column {
        Text(state.song?.title ?: "Nothing playing", fontWeight = FontWeight.Bold, fontSize = layout.titleSp.sp,
            lineHeight = (layout.titleSp * 1.22f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("pip-title"))
        Text(state.song?.artist ?: "Choose a song in BitChord", color = Color.White.copy(alpha = .65f),
            fontSize = (layout.titleSp * .76f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PipAction(label: String, icon: ImageVector, onClick: () -> Unit, selected: Boolean = false, enabled: Boolean = true) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(), focusable = false) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.size(28.dp).testTag("pip-action-$label")) {
            Icon(icon, label, tint = Color.White.copy(alpha = if (!enabled) .25f else if (selected) 1f else .55f), modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PipProgress(state: DesktopPipState, position: PlaybackPosition, onSeek: (Long) -> Unit) {
    var drag by remember(state.song?.videoId) { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(0)
    val value = drag ?: if (duration > 0) (position.positionMs.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
    Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(pipTime((value * duration).toLong()), fontSize = 9.sp, color = Color.White.copy(alpha = .6f), modifier = Modifier.width(34.dp))
        val currentTrack by rememberUpdatedState(state.song?.videoId)
        val gestureTrack = state.song?.videoId
        key(gestureTrack) {
            Slider(value, onValueChange = { drag = it }, modifier = Modifier.weight(1f).height(20.dp).testTag("pip-seek"),
                enabled = duration > 0 && !state.controlsLocked,
                onValueChangeFinished = {
                    drag?.takeIf { currentTrack == gestureTrack && !state.controlsLocked }?.let { onSeek((it * duration).toLong()) }
                    drag = null
                },
                thumb = { Canvas(Modifier.size(7.dp)) { drawCircle(Color.White) } },
                track = { slider ->
                    Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                        val radius = CornerRadius(size.height / 2)
                        drawRoundRect(Color.White.copy(alpha = .22f), cornerRadius = radius)
                        drawRoundRect(Color.White.copy(alpha = if (state.controlsLocked) .4f else 1f),
                            size = Size(size.width * slider.value.coerceIn(0f, 1f), size.height), cornerRadius = radius)
                    }
                })
        }
        Text(pipTime(duration), fontSize = 9.sp, color = Color.White.copy(alpha = .6f), modifier = Modifier.width(34.dp).padding(start = 6.dp))
    }
}
private fun pipTime(ms: Long) = "${ms.coerceAtLeast(0)/60000}:${((ms.coerceAtLeast(0)/1000)%60).toString().padStart(2, '0')}"

@Composable
private fun PipLyrics(state: DesktopPipState, position: PlaybackPosition, fontSize: Float, reduceAnimation: Boolean, modifier: Modifier) {
    val lines = state.currentLyrics()
    val label = when {
        state.song == null -> "Your lyrics appear here"
        state.lyricsTrack != state.song.videoId || state.lyricsLoading -> "Finding lyrics…"
        lines.isNullOrEmpty() -> state.lyricsError ?: "Lyrics unavailable"
        else -> null
    }
    BoxWithConstraints(modifier.testTag("pip-lyrics"), contentAlignment = Alignment.CenterStart) {
        val lyricHeight = maxHeight
        if (label != null) {
            Text(label, color = Color.White.copy(alpha = .6f), fontSize = minOf(fontSize, 16f).sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else if (lines != null && lines.none { it.timeMs > 0 }) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lines) { line -> Text(line.text, fontSize = fontSize.sp, color = Color.White.copy(alpha = .85f)) }
            }
        } else if (lines != null) {
            val clock = rememberFloatingLyricsClock(state.song!!.videoId, position, state.playing && !state.buffering, visible = true)
            val selection by remember(lines, clock) { derivedStateOf { desktopPipLyricSelection(lines, clock.longValue) } }
            val activeLineHeight = with(LocalDensity.current) { (fontSize * 1.15f).sp.toDp() }
            val smallLineHeight = with(LocalDensity.current) { (fontSize * .8f).sp.toDp() }
            val context = when {
                maxHeight >= activeLineHeight * 2 + smallLineHeight * 4 + 28.dp -> 2
                maxHeight >= activeLineHeight * 2 + smallLineHeight * 2 + 14.dp -> 1
                else -> 0
            }
            Column(Modifier.fillMaxWidth().animateContentSize(tween(if (reduceAnimation) 0 else 180)), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (context > 0) for (i in (selection.index-context).coerceAtLeast(0) until selection.index.coerceAtLeast(0)) {
                    Text(lines[i].text, fontSize = (fontSize * .66f).sp, lineHeight = (fontSize * .8f).sp,
                        color = Color.White.copy(alpha = .34f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val activeText = if (selection.instrumental) (if (selection.index < 0) "Intro" else "Instrumental") else lines[selection.index].text
                Crossfade(activeText, animationSpec = tween(if (reduceAnimation) 0 else 160), label = "PiP lyric") { text ->
                Text(text,
                    fontWeight = FontWeight.Bold, fontSize = fontSize.sp, lineHeight = (fontSize * 1.15f).sp,
                    maxLines = if (lyricHeight < activeLineHeight * 2) 1 else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("pip-active-lyric"))
                }
                if (context > 0) for (i in (selection.index+1).coerceAtLeast(0)..minOf(lines.lastIndex, selection.index+context)) {
                    Text(lines[i].text, fontSize = (fontSize * .66f).sp, lineHeight = (fontSize * .8f).sp,
                        color = Color.White.copy(alpha = .34f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopPipLauncher(onClick: () -> Unit, enabled: Boolean = true, tint: Color = DesktopSecondary) {
    TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Picture in picture") } }, state = rememberTooltipState(), focusable = false) {
        DesktopToolbarButton(onClick, enabled, size = 32.dp) {
            Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture", tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}
