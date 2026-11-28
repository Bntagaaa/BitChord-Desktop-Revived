package com.music.bitchord.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.settings.PlayerLayout
import com.music.bitchord.desktop.icons.DesktopIcons
import kotlinx.coroutines.delay

private enum class PreviewPane { Player, Lyrics, Queue }

@Composable
internal fun DesktopPlayerLayoutPicker(layout: PlayerLayout, description: String, onSelect: (PlayerLayout) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var pane by remember(hovered) { mutableStateOf(PreviewPane.Player) }
    LaunchedEffect(hovered) {
        if (hovered) {
            while (true) {
                delay(2200)
                pane = PreviewPane.entries[(pane.ordinal + 1) % PreviewPane.entries.size]
            }
        }
    }
    Box(Modifier.fillMaxWidth().hoverable(interaction)) {
        DesktopSettingsControl("Player layout", description, icon = DesktopIcons.Settings.Appearance) {
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (option in PlayerLayout.entries) {
                    val selected = option == layout
                    val shape = RoundedCornerShape(11.dp)
                    Column(
                        Modifier.weight(1f).clip(shape)
                            .background(if (selected) Color(0xff383838) else Color(0xff282828))
                            .border(1.dp, if (selected) Color.LightGray else Color.DarkGray, shape)
                            .selectable(selected, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(6.dp),
                    ) {
                        LayoutSkeleton(option, pane)
                        Row(
                            Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(option.name.lowercase().replaceFirstChar(Char::uppercaseChar),
                                color = Color.White, style = MaterialTheme.typography.labelMedium)
                            if (selected) Text("✓", color = Color.LightGray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayoutSkeleton(layout: PlayerLayout, pane: PreviewPane) {
    val open by animateFloatAsState(if (pane == PreviewPane.Player) 0f else 1f, tween(360))
    val lyrics by animateFloatAsState(if (pane == PreviewPane.Lyrics) 1f else 0f, tween(180))
    val queue by animateFloatAsState(if (pane == PreviewPane.Queue) 1f else 0f, tween(180))
    val frame = RoundedCornerShape(7.dp)
    Canvas(Modifier.fillMaxWidth().height(116.dp).clip(frame).background(Color(0xff212121))
        .border(1.dp, Color(0xff454545), frame)) {
        val classic = layout == PlayerLayout.CLASSIC
        val width = size.width
        val height = size.height
        val muted = Color(0xff505050)
        val mid = Color(0xff686868)
        val strong = Color(0xff929292)
        fun block(x: Float, y: Float, w: Float, h: Float, color: Color, radius: Float = h / 2) {
            drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(radius))
        }

        val coverX = width * (if (classic) .26f else .5f - .24f * open)
        val coverSize = minOf(width * .38f, if (classic) 62.dp.toPx() else 49.dp.toPx())
        val coverTop = if (classic) (height - coverSize) / 2 else 10.dp.toPx()
        block(coverX - coverSize / 2, coverTop, coverSize, coverSize, muted, 6.dp.toPx())

        val controlsX = if (classic) width * .73f else coverX
        val controlsWidth = minOf(width * .32f, 112.dp.toPx())
        val top = if (classic) height / 2 - 17.dp.toPx() else coverTop + coverSize + 7.dp.toPx()
        val alpha = if (classic) 1f - open else 1f
        fun controlBar(y: Int, fraction: Float, thickness: Int, color: Color) {
            val length = controlsWidth * fraction
            val left = controlsX - (if (classic) controlsWidth else length) / 2
            block(left, top + y.dp.toPx(), length, thickness.dp.toPx(), color.copy(alpha = alpha))
        }
        controlBar(0, .68f, 5, strong)
        controlBar(9, .44f, 3, mid)
        controlBar(18, 1f, 3, muted)
        for (i in -1..1) {
            drawCircle((if (i == 0) strong else mid).copy(alpha = alpha),
                if (i == 0) 4.5.dp.toPx() else 2.5.dp.toPx(),
                Offset(controlsX + i * 14.dp.toPx(), top + 30.dp.toPx()))
        }

        val left = width * .59f
        val panelWidth = width * .32f
        block(width * .52f, 11.dp.toPx(), 1.dp.toPx(), height - 24.dp.toPx(), mid.copy(alpha = open))
        val lengths = floatArrayOf(1f, .86f, .61f, .9f)
        for ((row, length) in lengths.withIndex()) {
            val current = row == 2
            block(left, height / 2 + (row * 9 - 15).dp.toPx(), panelWidth * length,
                if (current) 5.dp.toPx() else 3.dp.toPx(), (if (current) strong else muted).copy(alpha = lyrics))
        }
        val thumb = minOf(9.dp.toPx(), panelWidth * .22f)
        for (row in -1..1) {
            val y = height / 2 + row * 15.dp.toPx()
            block(left, y - thumb / 2, thumb, thumb, muted.copy(alpha = queue), 2.dp.toPx())
            block(left + thumb * 1.5f, y, panelWidth * .52f, 3.dp.toPx(), mid.copy(alpha = queue))
        }
    }
}
