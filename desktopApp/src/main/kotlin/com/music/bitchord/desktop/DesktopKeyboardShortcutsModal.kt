package com.music.bitchord.desktop

import com.music.bitchord.desktop.icons.DesktopIcons

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopKeyboardShortcutsModal(onDismiss: () -> Unit) {
    DesktopRegisterFlyout(DesktopFlyoutLayer.SHORTCUTS, onDismiss = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val availableHeight = maxHeight
        DesktopFlyoutBackdrop(onDismiss = onDismiss)
        DesktopFlyoutCard(
            Modifier.padding(16.dp)
                .widthIn(max = 540.dp)
                .fillMaxWidth()
                .heightIn(max = (availableHeight - 32.dp).coerceAtLeast(0.dp)),
            glass = true,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Keyboard Shortcuts",
                        modifier = Modifier.weight(1f),
                        color = DesktopForeground,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(DesktopIcons.Actions.Close, contentDescription = "Close keyboard shortcuts", tint = DesktopSecondary)
                    }
                }
                Row(
                    Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Press", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                    ShortcutKeycap("Ctrl")
                    ShortcutKeycap("/")
                    Text("to toggle this modal.", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(thickness = 0.5.dp, color = DesktopCardEdge)
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = (availableHeight - 160.dp).coerceAtLeast(0.dp)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 22.dp, end = 22.dp, top = 10.dp, bottom = 20.dp,
                    ),
                ) {
                    DesktopShortcutCategory.entries.forEach { category ->
                        item(key = "category:${category.name}") {
                            Text(
                                category.label.uppercase(),
                                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                                color = DesktopSecondary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        items(
                            DesktopShortcut.entries.filter { it.category == category },
                            key = { it.name },
                        ) { shortcut ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 40.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    shortcut.label,
                                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    color = DesktopForeground,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    shortcut.keycaps.forEach { ShortcutKeycap(it) }
                                }
                            }
                        }
                        item(key = "space:${category.name}") { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutKeycap(label: String) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, DesktopForeground.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        color = DesktopForeground,
        style = MaterialTheme.typography.labelMedium,
    )
}
