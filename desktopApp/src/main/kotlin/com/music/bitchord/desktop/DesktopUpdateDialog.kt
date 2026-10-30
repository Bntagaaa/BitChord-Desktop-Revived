package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DesktopUpdateSettings(service: DesktopUpdateService) {
    val state by service.state.collectAsState()
    val channel by service.channel.collectAsState()
    val auto by service.autoCheck.collectAsState()
    val checked by service.lastCheck.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Current version: ${DesktopUpdateChecker.currentVersion}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopUpdateChecker.Channel.entries.forEach { option ->
                FilterChip(selected = channel == option, enabled = state.phase != UpdatePhase.INSTALLING, onClick = { service.setChannel(option) },
                    label = { Text(if (option == DesktopUpdateChecker.Channel.STABLE) "Stable" else "Preview") })
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Automatically check for updates", Modifier.weight(1f))
            Switch(auto, service::setAuto)
        }
        if (checked > 0) Text("Last successful check: " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(checked)), style = MaterialTheme.typography.bodySmall)
        Text(updateStatus(state), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { service.check() }, enabled = state.phase !in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING)) { Text("Check for Updates") }
            if (state.release != null) Button(onClick = { service.dialog.value = true }) { Text("View update") }
        }
        Row {
            TextButton(onClick = service::showLogs) { Text("Open updater logs") }
            TextButton(onClick = { DesktopExternalLinks.open("https://github.com/${DesktopUpdateChecker.REPOSITORY}/releases") }) { Text("Official releases") }
        }
    }
}

@Composable
internal fun DesktopUpdateDialog(service: DesktopUpdateService, onExit: suspend () -> Unit) {
    val visible by service.dialog.collectAsState()
    val state by service.state.collectAsState()
    val icon by DesktopAppIconSettings.selected.collectAsState()
    val release = state.release
    if (!visible || release == null) return
    val busy = state.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING)
    AlertDialog(
        onDismissRequest = { if (state.phase != UpdatePhase.INSTALLING) service.later() },
        icon = { Image(painterResource(icon.resource), null, Modifier.size(48.dp)) },
        title = { Text("A new version of BitChord is available") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${DesktopUpdateChecker.currentVersion} → ${release.version} · ${if (release.preview) "Preview" else "Stable"}")
                Text(service.installation.format.guidance)
                Text(state.error ?: updateStatus(state))
                if (busy) {
                    if (state.total > 0) {
                        LinearProgressIndicator(progress = { (state.received.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${state.received * 100 / state.total}% · ${sizeLabel(state.received)} / ${sizeLabel(state.total)}")
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(release.notes.ifBlank { "No release notes provided." }, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { DesktopExternalLinks.open(release.url) }) { Text("Release notes on GitHub") }
            }
        },
        confirmButton = {
            when {
                state.phase == UpdatePhase.READY -> Button(onClick = {
                    if (service.canAutoInstall) service.install(onExit)
                    else service.showDownload()
                }) { Text(if (service.canAutoInstall) if (service.installation.format == UpdateFormat.WINDOWS_SETUP) "Close and open setup" else "Restart to Update" else "Show verified download") }
                release.asset == null -> TextButton(onClick = { DesktopExternalLinks.open(release.url) }) { Text("View release") }
                !busy -> Button(onClick = service::download) { Text(if (state.phase in setOf(UpdatePhase.FAILED, UpdatePhase.CANCELLED)) "Retry download" else "Download update") }
            }
        },
        dismissButton = { if (state.phase != UpdatePhase.INSTALLING) TextButton(onClick = { if (busy) service.cancel() else service.later() }) { Text(if (busy) "Cancel" else "Later") } },
    )
}

private fun sizeLabel(bytes: Long) = "%.1f MiB".format(bytes / 1048576.0)
internal fun updateStatus(state: DesktopUpdateState): String = when (state.phase) {
    UpdatePhase.IDLE -> "Updates have not been checked."
    UpdatePhase.CHECKING -> "Checking for updates…"
    UpdatePhase.AVAILABLE -> "Version ${state.release?.version} is available."
    UpdatePhase.DOWNLOADING -> "Downloading update…"
    UpdatePhase.VERIFYING -> "Verifying SHA-256…"
    UpdatePhase.READY -> "Download verified and ready."
    UpdatePhase.INSTALLING -> "Preparing update and closing BitChord…"
    UpdatePhase.UP_TO_DATE -> "You are up to date."
    UpdatePhase.CANCELLED -> "Download cancelled. You can retry."
    UpdatePhase.FAILED -> state.error ?: "Update failed. Please retry."
    UpdatePhase.UNSUPPORTED -> "A newer release is available. Use the release instructions for this installation."
}
