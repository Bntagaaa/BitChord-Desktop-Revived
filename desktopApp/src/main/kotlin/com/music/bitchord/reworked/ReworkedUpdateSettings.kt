package com.music.bitchord.reworked

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ReworkedUpdateSettings(onExitForUpdate: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    val state by ReworkedUpdates.status.collectAsState()
    val previews by ReworkedUpdates.previews.collectAsState()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Software updates")
            Text("BitChord Reworked ${ReworkedUpdates.currentVersion}", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { open = true; if (state.phase == ReworkedUpdatePhase.IDLE) ReworkedUpdates.check() }) {
            Text("Check for updates")
        }
    }
    if (open) {
        val busy = state.phase in setOf(ReworkedUpdatePhase.CHECKING, ReworkedUpdatePhase.DOWNLOADING, ReworkedUpdatePhase.INSTALLING)
        AlertDialog(
            onDismissRequest = { if (state.phase != ReworkedUpdatePhase.INSTALLING) open = false },
            title = { Text("BitChord Reworked updates") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Installed: ${ReworkedUpdates.currentVersion}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Include preview releases", modifier = Modifier.weight(1f))
                        Switch(checked = previews, onCheckedChange = { ReworkedUpdates.setPreviews(it); ReworkedUpdates.check() }, enabled = !busy)
                    }
                    when (state.phase) {
                        ReworkedUpdatePhase.CHECKING -> { Text("Checking releases…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        ReworkedUpdatePhase.DOWNLOADING -> {
                            Text("Downloading ${(state.progress * 100).toInt()}%")
                            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        }
                        ReworkedUpdatePhase.INSTALLING -> { Text(state.message); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        else -> {
                            state.release?.let { Text("Available: ${it.version}", style = MaterialTheme.typography.titleMedium) }
                            if (state.message.isNotBlank()) Text(state.message, color = if (state.phase == ReworkedUpdatePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            state.release?.notes?.takeIf(String::isNotBlank)?.let { Text(it.take(4000), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (state.phase == ReworkedUpdatePhase.READY) {
                        if (onExitForUpdate != null && ReworkedUpdates.installAvailable()) {
                            Button(onClick = { ReworkedUpdates.install(onExitForUpdate) }) { Text("Update and restart") }
                        }
                        OutlinedButton(onClick = ReworkedUpdates::revealDownload) { Text("Open download folder") }
                    }
                    if (state.phase == ReworkedUpdatePhase.AVAILABLE && state.release?.asset != null) {
                        Button(onClick = ReworkedUpdates::download) { Text("Download update") }
                    }
                    TextButton(onClick = ReworkedUpdates::openReleases) { Text("Official releases") }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }, enabled = state.phase != ReworkedUpdatePhase.INSTALLING) { Text("Close") }
            },
            dismissButton = {
                if (state.phase == ReworkedUpdatePhase.DOWNLOADING) TextButton(onClick = ReworkedUpdates::cancel) { Text("Cancel download") }
                else if (!busy) TextButton(onClick = ReworkedUpdates::check) { Text("Check again") }
            },
        )
    }
}
