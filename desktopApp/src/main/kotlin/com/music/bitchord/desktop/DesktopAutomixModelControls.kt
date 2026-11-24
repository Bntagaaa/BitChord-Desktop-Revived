package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopAutomixModelControls(onDelete: () -> Unit) {
    val manager = DesktopAutomixModels.manager
    val state by manager.state.collectAsState()
    LaunchedEffect(manager) { manager.inspect() }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("AutoMix models", fontWeight = FontWeight.Medium)
        val current = state
        Text(
            when (current) {
                DesktopAutomixModelState.NotInstalled -> "Not installed"
                is DesktopAutomixModelState.Downloading -> "Downloading ${100 * current.completed / current.total}%"
                DesktopAutomixModelState.Ready -> "Ready"
                is DesktopAutomixModelState.Failed -> "Download or verification failed. ${current.reason}"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        when (current) {
            is DesktopAutomixModelState.Downloading -> LinearProgressIndicator(
                progress = { current.completed.toFloat() / current.total },
                modifier = Modifier.fillMaxWidth(),
            )
            DesktopAutomixModelState.Ready -> TextButton(onClick = {
                onDelete()
                manager.delete()
            }) { Text("Delete models") }
            DesktopAutomixModelState.NotInstalled, is DesktopAutomixModelState.Failed ->
                TextButton(onClick = manager::download) {
                    Text(if (current is DesktopAutomixModelState.Failed) "Retry" else "Download models")
                }
        }
        if (current is DesktopAutomixModelState.Failed) {
            TextButton(onClick = {
                onDelete()
                manager.delete()
            }) { Text("Delete model files") }
        }
    }
}
