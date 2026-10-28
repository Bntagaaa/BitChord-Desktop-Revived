package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun DesktopAppIconPicker() {
    val selected by DesktopAppIconSettings.selected.collectAsState()
    val palette = DesktopTheme.palette
    Row(
        Modifier.fillMaxWidth().selectableGroup().padding(top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DesktopAppIcon.entries.forEach { icon ->
            val chosen = selected == icon
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .testTag("app-icon-${icon.name.lowercase()}")
                    .clip(shape)
                    .background(if (chosen) palette.subtleFill else palette.surface)
                    .border(1.dp, if (chosen) palette.accent else palette.divider, shape)
                    .selectable(chosen, role = Role.RadioButton) { DesktopAppIconSettings.select(icon) }
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(painterResource(icon.resource), null, Modifier.size(64.dp))
                Text(
                    when (icon) {
                        DesktopAppIcon.DARK -> DesktopStrings["d_app_icon_dark", "Dark"]
                        DesktopAppIcon.LIGHT -> DesktopStrings["d_app_icon_light", "Light"]
                    },
                    color = palette.foreground,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
