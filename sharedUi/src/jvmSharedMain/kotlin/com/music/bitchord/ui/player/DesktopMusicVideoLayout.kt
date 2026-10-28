package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.up_next
import com.music.bitchord.sharedui.resources.open_lyrics
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Wide desktop watch layout. Playback and panel content remain owned by the existing player. */
@Composable
fun DesktopMusicVideoLayout(
    caption: String,
    lyricsSelected: Boolean,
    onSelectQueue: () -> Unit,
    onSelectLyrics: () -> Unit,
    video: @Composable BoxScope.() -> Unit,
    credits: @Composable () -> Unit,
    scrubber: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    volume: @Composable () -> Unit,
    panel: @Composable () -> Unit,
    panelActions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onOpenCaption: () -> Unit = {},
) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 52.dp, bottom = 24.dp)) {
            Text(caption, color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 16.dp).clickable(onClick = onOpenCaption))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val sidebarWidth = if (maxWidth >= 1300.dp) 360.dp else 300.dp
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().testTag("desktop-video-main")) {
                        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(Color.Black).border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(16.dp))
                            .testTag("desktop-video-stage"), content = video)
                        Spacer(Modifier.height(16.dp))
                        credits()
                        Spacer(Modifier.height(12.dp))
                        scrubber()
                        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Box(Modifier.width(280.dp)) { transport() }
                            Box(Modifier.width(180.dp)) { volume() }
                        }
                    }
                    Column(Modifier.width(sidebarWidth).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = .28f))
                        .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(20.dp))
                        .testTag("desktop-video-sidebar")) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(false to stringResource(Res.string.up_next), true to stringResource(Res.string.open_lyrics)).forEach { (lyrics, title) ->
                                val selected = lyricsSelected == lyrics
                                TextButton(onClick = if (lyrics) onSelectLyrics else onSelectQueue,
                                    modifier = Modifier.weight(1f).semantics { this.selected = selected }.clip(RoundedCornerShape(12.dp))
                                        .background(if (selected) Color.White.copy(alpha = .12f) else Color.Transparent)) {
                                    Text(title, color = Color.White.copy(alpha = if (selected) 1f else .55f),
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                }
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("desktop-video-panel")) { panel() }
                        Column(Modifier.fillMaxWidth().padding(12.dp)) { panelActions() }
                    }
                }
            }
        }
    }
}
