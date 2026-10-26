package com.music.bitchord.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** An inline Appearance page section, not another modal obstructing Settings. */
@Composable
internal fun DesktopBlurCustomization(reduceDynamicBlur: Boolean) {
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .desktopRowClickable { expanded = !expanded }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Customize blur", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "Choose exactly where BitChord uses frosted glass",
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse blur options" else "Expand blur options",
                tint = DesktopSecondary,
            )
        }

        AnimatedVisibility(visible = expanded) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(bottom = 14.dp)
                    .clip(shape)
                    .background(DesktopCardInsetFill)
                    .border(0.5.dp, DesktopCardEdge, shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "Each choice is saved separately. The global Reduce dynamic blur switch always takes priority.",
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                if (reduceDynamicBlur) {
                    Text(
                        "All blur is currently off. Your choices below are saved and will apply when Reduce dynamic blur is turned off.",
                        color = DesktopForeground,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                DesktopBlurArea.entries.forEachIndexed { index, area ->
                    if (index > 0) {
                        HorizontalDivider(color = DesktopCardEdge, thickness = 0.5.dp)
                    }
                    val enabled by DesktopAppearanceSettings.blurPreference(area).collectAsState()
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .desktopRowClickable {
                                DesktopAppearanceSettings.setBlurPreference(area, !enabled)
                            }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(area.title, color = DesktopForeground, style = MaterialTheme.typography.bodyMedium)
                            Text(area.description, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = enabled,
                            onCheckedChange = { DesktopAppearanceSettings.setBlurPreference(area, it) },
                            colors = desktopSwitchColors(),
                        )
                    }
                }
            }
        }
    }
}
