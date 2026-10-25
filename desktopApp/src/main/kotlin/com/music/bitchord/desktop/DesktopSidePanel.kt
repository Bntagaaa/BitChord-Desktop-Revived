package com.music.bitchord.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.music.bitchord.ui.player.LyricsPanelColors
import com.music.bitchord.ui.player.LocalLyricsPanelColors
import com.music.bitchord.ui.player.QueuePanelColors
import com.music.bitchord.ui.player.LocalQueuePanelColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** What the column beside the page is showing. */
internal enum class DesktopSidePanel { LYRICS, QUEUE, DETAILS }

/**
 * Apple Music's right-hand sidebar: the lyrics or the queue in a column beside the page, while the
 * player itself stays closed. Home extends beneath the glass; other pages reserve a solid column.
 */
@Composable
internal fun DesktopSidePanelColumn(
    panel: DesktopSidePanel?,
    onClose: () -> Unit,
    blurBackground: Boolean = false,
    content: @Composable (DesktopSidePanel) -> Unit,
) {
    // Held through the exit, so the column slides out with what it had in it rather than empty.
    var shown by remember { mutableStateOf(panel) }
    if (panel != null) shown = panel
    val haze = LocalDesktopHaze.current
    val glassActive = blurBackground && haze != null &&
        DesktopAppearanceSettings.blurEnabled(DesktopBlurArea.SIDE_PANELS)
    // Lyrics, Queue and Details share the same material even while crossfading.
    val panelFill = DesktopTheme.palette.lyricsSurface
    AnimatedVisibility(
        visible = panel != null,
        enter = expandHorizontally(tween(SLIDE_MS, easing = FastOutSlowInEasing), expandFrom = Alignment.Start) +
            fadeIn(tween(SLIDE_MS)),
        exit = shrinkHorizontally(tween(SLIDE_MS, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Start) +
            fadeOut(tween(SLIDE_MS / 2)),
    ) {
        val glass = if (glassActive) {
            Modifier.hazeEffect(state = requireNotNull(haze)) {
                blurEnabled = true
                blurRadius = 40.dp
                backgroundColor = panelFill
                tints = listOf(HazeTint(panelFill.copy(alpha = 0.73f)))
            }
        } else Modifier.background(panelFill)
        Box(Modifier.width(SIDE_PANEL_WIDTH).fillMaxHeight()) {
            Row(Modifier.matchParentSize().then(glass)) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(DesktopDivider))
                Crossfade(
                    targetState = shown ?: return@Row,
                    animationSpec = tween(CROSSFADE_MS),
                    label = "sidePanel",
                ) { which ->
                    Box(
                        Modifier.fillMaxSize().background(
                            if (glassActive) Color.Transparent else panelFill,
                        ),
                    ) {
                        // Keep the existing panel geometry independent from its background material.
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(HEADER_HEIGHT)
                                    .padding(start = 18.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = when (which) {
                                        DesktopSidePanel.LYRICS -> DesktopStrings["lyrics", "Lyrics"]
                                        DesktopSidePanel.DETAILS -> "Now playing"
                                        DesktopSidePanel.QUEUE -> "Queue"
                                    },
                                    color = DesktopForeground,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                DesktopToolbarButton(onClick = onClose, size = 28.dp) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        DesktopStrings["close", "Close"],
                                        tint = DesktopSecondary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(
                                        start = when (which) {
                                            DesktopSidePanel.LYRICS -> 18.dp
                                            DesktopSidePanel.DETAILS -> 0.dp
                                            DesktopSidePanel.QUEUE -> 14.dp
                                        },
                                        end = when (which) {
                                            DesktopSidePanel.LYRICS -> 18.dp
                                            DesktopSidePanel.DETAILS -> 0.dp
                                            DesktopSidePanel.QUEUE -> 4.dp
                                        },
                                        bottom = if (which == DesktopSidePanel.DETAILS) 0.dp else 14.dp,
                                    ),
                            ) {
                                if (which == DesktopSidePanel.LYRICS && DesktopTheme.isLight) {
                                    CompositionLocalProvider(
                                        LocalLyricsPanelColors provides LyricsPanelColors(
                                            primary = LIGHT_PANEL_PRIMARY,
                                            secondary = LIGHT_PANEL_SECONDARY,
                                            inactive = LIGHT_PANEL_INACTIVE,
                                            iconSurface = Color(0xFF243044),
                                            selection = Color(0xFF1C273B),
                                            isLight = true,
                                        ),
                                    ) { content(which) }
                                } else if (which == DesktopSidePanel.QUEUE && DesktopTheme.isLight) {
                                    CompositionLocalProvider(
                                        LocalQueuePanelColors provides QueuePanelColors(
                                            primary = LIGHT_PANEL_PRIMARY,
                                            secondary = LIGHT_PANEL_SECONDARY,
                                            heading = LIGHT_PANEL_INACTIVE,
                                            muted = LIGHT_PANEL_SECONDARY,
                                        ),
                                    ) { content(which) }
                                } else content(which)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val LIGHT_PANEL_PRIMARY = Color(0xFF1B2230)
private val LIGHT_PANEL_SECONDARY = Color(0xFF596272)
private val LIGHT_PANEL_INACTIVE = Color(0xFF3E485A)

private val SIDE_PANEL_WIDTH = 360.dp
private val HEADER_HEIGHT = 46.dp
private const val SLIDE_MS = 260
private const val CROSSFADE_MS = 180
