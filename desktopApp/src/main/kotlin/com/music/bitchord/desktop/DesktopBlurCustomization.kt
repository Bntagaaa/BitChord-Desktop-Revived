package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The single, global blur control. Reduce Blur always overrides every individual surface. */
@Composable
internal fun DesktopUnifiedGraphicsAndBlurSettings() {
    val reduceBlur by DesktopAppearanceSettings.reduceDynamicBlur.collectAsState()
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(shape)
            .background(DesktopCardInsetFill)
            .border(0.5.dp, DesktopCardEdge, shape)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Reduce Blur",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Turn off blur effects across the interface for a lighter, more responsive experience.",
                    color = DesktopSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = reduceBlur,
                onCheckedChange = DesktopAppearanceSettings::setReduceDynamicBlur,
                colors = desktopSwitchColors(),
            )
        }
    }
}

/** Backward-compatibility wrapper for existing Settings call sites. */
@Composable
internal fun DesktopBlurCustomization(reduceDynamicBlur: Boolean) {
    DesktopUnifiedGraphicsAndBlurSettings()
}
