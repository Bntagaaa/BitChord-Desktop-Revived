package com.music.bitchord.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.FilterChip
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

/** Unified Graphics Profile, Reduce Dynamic Blur, and Per-Area Blur Customization component. */
@Composable
internal fun DesktopUnifiedGraphicsAndBlurSettings() {
    val performanceProfile by DesktopAppearanceSettings.performanceProfile.collectAsState()
    val reduceDynamicBlur by DesktopAppearanceSettings.reduceDynamicBlur.collectAsState()
    var expandedCustomize by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(shape)
            .background(DesktopCardInsetFill)
            .border(0.5.dp, DesktopCardEdge, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Section 1: Graphics Profile
        Column(Modifier.fillMaxWidth()) {
            Text("Graphics profile", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Adaptive profile for rendering performance and dynamic blur effects",
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopPerformanceProfile.entries.forEach { option ->
                    FilterChip(
                        colors = desktopChipColors(),
                        selected = performanceProfile == option,
                        onClick = { DesktopAppearanceSettings.setPerformanceProfile(option) },
                        label = { Text(option.label) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (performanceProfile == DesktopPerformanceProfile.AUTO) {
                    "${performanceProfile.description} (Active: ${performanceProfile.resolved().label})"
                } else {
                    performanceProfile.description
                },
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider(color = DesktopCardEdge, thickness = 0.5.dp)

        // Section 2: Master Reduce Dynamic Blur
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Reduce dynamic blur", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "Swaps frosted glass for solid fills across all UI surfaces",
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = reduceDynamicBlur,
                onCheckedChange = DesktopAppearanceSettings::setReduceDynamicBlur,
                colors = desktopSwitchColors(),
            )
        }

        HorizontalDivider(color = DesktopCardEdge, thickness = 0.5.dp)

        // Section 3: Expandable Per-Area Blur Customization
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .desktopRowClickable { expandedCustomize = !expandedCustomize }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Customize blur per area", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "Choose exactly which UI components use frosted glass",
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                if (expandedCustomize) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expandedCustomize) "Collapse per-area options" else "Expand per-area options",
                tint = DesktopSecondary,
            )
        }

        AnimatedVisibility(visible = expandedCustomize) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (reduceDynamicBlur) {
                    Text(
                        "All dynamic blur is currently overridden by 'Reduce dynamic blur'. Your choices below are saved and will apply when master override is turned off.",
                        color = DesktopForeground,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 6.dp),
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
                            .padding(vertical = 8.dp),
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

/** Backward compatibility wrapper */
@Composable
internal fun DesktopBlurCustomization(reduceDynamicBlur: Boolean) {
    DesktopUnifiedGraphicsAndBlurSettings()
}
