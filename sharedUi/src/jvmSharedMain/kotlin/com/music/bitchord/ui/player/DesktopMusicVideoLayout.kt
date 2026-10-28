package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.up_next
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun desktopVideoSidebarWidth(contentWidth: Dp): Dp = (contentWidth * .25f).coerceIn(300.dp, 450.dp)

/** Queue-only watch view. Controls share one horizontal strip, followed by the timeline. */
@Composable
fun DesktopMusicVideoLayout(
    caption: String,
    video: @Composable BoxScope.() -> Unit,
    credits: @Composable () -> Unit,
    trackActions: @Composable () -> Unit,
    scrubber: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    volume: @Composable () -> Unit,
    panel: @Composable () -> Unit,
    panelActions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onOpenCaption: () -> Unit = {},
) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 24.dp)) {
            Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
                Text(caption, color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onOpenCaption))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val sidebarWidth = desktopVideoSidebarWidth(maxWidth)
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().testTag("desktop-video-main")) {
                        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                            .background(Color.Black).border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(20.dp))
                            .testTag("desktop-video-stage"), content = video)
                        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            if (maxWidth >= 900.dp) {
                                Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Box(Modifier.weight(1f)) { credits() }
                                    Box(Modifier.width(240.dp)) { transport() }
                                    Box(Modifier.width(180.dp)) { volume() }
                                    trackActions()
                                }
                            } else {
                                Column {
                                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Box(Modifier.weight(1f)) { credits() }
                                        trackActions()
                                    }
                                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Box(Modifier.width(240.dp)) { transport() }
                                        Box(Modifier.width(180.dp)) { volume() }
                                    }
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().testTag("desktop-video-timeline")) { scrubber() }
                    }
                    Column(Modifier.width(sidebarWidth).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = .28f))
                        .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(20.dp))
                        .testTag("desktop-video-sidebar")) {
                        Text(stringResource(Res.string.up_next), fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp))
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("desktop-video-panel")) { panel() }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { panelActions() }
                    }
                }
            }
        }
    }
}
