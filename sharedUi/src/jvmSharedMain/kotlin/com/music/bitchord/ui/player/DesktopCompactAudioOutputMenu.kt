package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.audio_output
import com.music.bitchord.sharedui.resources.audio_output_playing
import com.music.bitchord.sharedui.resources.audio_pipeline
import com.music.bitchord.sharedui.resources.cast
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

internal object AudioOutputMenuAnchor {
    var bounds: Rect? = null
}

/**
 * Desktop-only output menu. It matches the compact account picker rather than
 * opening a phone-sized bottom drawer. The list scrolls independently and the
 * popup is positioned relative to the output control actually clicked.
 */
@Composable
internal fun DesktopCompactAudioOutputMenu(
    outputs: List<AudioOutputDevice>,
    cast: CastUi,
    accountName: String?,
    anchorInWindow: Rect?,
    onDismiss: () -> Unit,
    onOpenPipeline: () -> Unit,
    onOpenCast: () -> Unit,
) {
    val density = LocalDensity.current
    val edgePx = with(density) { 12.dp.roundToPx() }
    val gapPx = with(density) { 8.dp.roundToPx() }
    val position = remember(anchorInWindow, edgePx, gapPx) {
        CompactAudioOutputPositionProvider(anchorInWindow, edgePx, gapPx)
    }
    val listState = rememberLazyListState()
    val shape = RoundedCornerShape(16.dp)

    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(316.dp)
                .heightIn(max = 390.dp)
                .shadow(16.dp, shape)
                .clip(shape)
                .background(Color(0xF21A1A1C))
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                .padding(vertical = 6.dp),
        ) {
            Text(
                stringResource(Res.string.audio_output),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 232.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(outputs.size) { index ->
                    val output = outputs[index]
                    CompactAudioMenuRow(
                        name = outputLabel(output, accountName),
                        icon = compactOutputIcon(output.kind),
                        selected = output.isActive,
                        subtitle = if (output.isActive) stringResource(Res.string.audio_output_playing) else null,
                        onClick = {
                            PlayerPlatform.host.selectAudioOutput(output.id)
                            onDismiss()
                        },
                    )
                }
                if (cast.supported) {
                    item {
                        CompactAudioMenuRow(
                            name = cast.connectedName ?: stringResource(Res.string.cast),
                            icon = if (cast.connectedName != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                            selected = cast.connectedName != null,
                            arrow = true,
                            onClick = { onDismiss(); onOpenCast() },
                        )
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                thickness = 0.5.dp,
                color = Color.White.copy(alpha = 0.13f),
            )
            CompactVolumeControl(routeKey = outputs)
            CompactAudioMenuRow(
                name = stringResource(Res.string.audio_pipeline),
                icon = Icons.Rounded.GraphicEq,
                arrow = true,
                onClick = { onDismiss(); onOpenPipeline() },
            )
        }
    }
}

@Composable
private fun CompactAudioMenuRow(
    name: String,
    icon: ImageVector,
    onClick: () -> Unit,
    selected: Boolean = false,
    subtitle: String? = null,
    arrow: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color.White.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !selected || arrow,
                onClick = onClick,
            )
            .heightIn(min = 44.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = if (selected) 0.15f else 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = Color.White.copy(alpha = if (selected) 1f else 0.88f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        } else if (arrow) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun CompactVolumeControl(routeKey: Any) {
    val volume = PlayerPlatform.host.volume
    val systemLevel by volume.level.collectAsStateWithLifecycle()
    var level by remember(volume) { mutableFloatStateOf(systemLevel) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(systemLevel) { if (!dragging) level = systemLevel }
    LaunchedEffect(routeKey) {
        repeat(4) {
            if (!dragging) volume.refresh()
            delay(250)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (level > 0f) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        ThinSlider(
            value = level,
            onValueChange = {
                dragging = true
                level = it
                volume.set(it)
            },
            onValueChangeFinished = { dragging = false },
            idleHeight = 4.dp,
            activeHeight = 7.dp,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun compactOutputIcon(kind: AudioOutputKind): ImageVector = when (kind) {
    AudioOutputKind.PHONE -> Icons.Rounded.PhoneAndroid
    AudioOutputKind.WIRED -> Icons.Rounded.Headphones
    AudioOutputKind.USB -> Icons.Rounded.Usb
    AudioOutputKind.BLUETOOTH -> Icons.Rounded.Bluetooth
    AudioOutputKind.HDMI -> Icons.Rounded.Tv
    AudioOutputKind.OTHER -> Icons.Rounded.Speaker
}

/** Clamp around screen edges, preferring above the anchor if there is room. */
internal class CompactAudioOutputPositionProvider(
    private val trigger: Rect?,
    private val edgePx: Int,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val left = trigger?.left?.roundToInt() ?: anchorBounds.left
        val right = trigger?.right?.roundToInt() ?: anchorBounds.right
        val top = trigger?.top?.roundToInt() ?: anchorBounds.top
        val bottom = trigger?.bottom?.roundToInt() ?: anchorBounds.bottom
        val maxX = (windowSize.width - popupContentSize.width - edgePx).coerceAtLeast(edgePx)
        val maxY = (windowSize.height - popupContentSize.height - edgePx).coerceAtLeast(edgePx)
        val x = ((left + right) / 2 - popupContentSize.width / 2).coerceIn(edgePx, maxX)
        val above = top - popupContentSize.height - gapPx
        val below = bottom + gapPx
        val y = if (above >= edgePx) above else below
        return IntOffset(x, y.coerceIn(edgePx, maxY))
    }
}
