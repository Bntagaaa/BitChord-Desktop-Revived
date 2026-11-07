package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.logo
import bitchord.desktopapp.generated.resources.bitchord_logo_original
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DesktopUpdateSettings(service: DesktopUpdateService) {
    val state by service.state.collectAsState()
    val auto by service.autoCheck.collectAsState()
    val checked by service.lastCheck.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Current version: ${DesktopUpdateChecker.currentVersion}")
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


/**
 * Update availability has the same centered card, logo, headline and rounded
 * full-width actions as "Welcome to BitChord". The release's Github Markdown
 * remains scrollable independently of the always-visible action buttons.
 */
@Composable
internal fun DesktopUpdateDialog(service: DesktopUpdateService, onExit: suspend () -> Unit) {
    val visible by service.dialog.collectAsState()
    val state by service.state.collectAsState()
    val release = state.release
    if (!visible || release == null) return

    val busy = state.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING)
    val installing = state.phase == UpdatePhase.INSTALLING
    val manual = service.installation.format in setOf(UpdateFormat.DEB, UpdateFormat.RPM, UpdateFormat.ARCH, UpdateFormat.UNKNOWN)
    val dismiss = { if (!installing) service.later() }

    DesktopDialogPanel(onDismiss = dismiss, maxWidth = 480, popup = true, dismissOnEscape = true) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 17.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(if (DesktopTheme.isLight) Res.drawable.logo else Res.drawable.bitchord_logo_original),
                contentDescription = "BitChord",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 64.dp, height = 42.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "A new version of BitChord is available",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = DesktopForeground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (manual) service.installation.format.guidance else "Update now to enjoy the latest improvements.",
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                "Version ${release.version} is available.",
                style = MaterialTheme.typography.labelSmall,
                color = DesktopForeground,
                textAlign = TextAlign.Center,
            )
            if (state.phase != UpdatePhase.AVAILABLE) {
                Spacer(Modifier.height(7.dp))
                Text(
                    state.error ?: updateStatus(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.phase == UpdatePhase.FAILED) DesktopDestructive else DesktopSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            if (busy) {
                Spacer(Modifier.height(12.dp))
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { (state.received.toFloat() / state.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = DesktopAccent,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.received * 100 / state.total}% · ${sizeLabel(state.received)} / ${sizeLabel(state.total)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = DesktopSecondary,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DesktopAccent)
                }
            }
        }
        DesktopCardRule()
        Column(
            Modifier.fillMaxWidth().heightIn(max = 330.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("What's new", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = DesktopForeground)
            DesktopReleaseWhatsNew(release.notes, modifier = Modifier.fillMaxWidth())
        }
        DesktopCardRule()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                state.phase == UpdatePhase.READY -> Button(
                    onClick = {
                        if (service.canAutoInstall) service.install(onExit)
                        else service.showDownload()
                    },
                    modifier = Modifier.fillMaxWidth().height(49.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DesktopForeground, contentColor = DesktopBackground),
                ) {
                    Text(if (!service.canAutoInstall) "Show verified download" else when (service.installation.format) {
                        UpdateFormat.WINDOWS_SETUP, UpdateFormat.WINDOWS_PORTABLE -> "Update and Restart"
                        else -> "Restart to Update"
                    }, style = MaterialTheme.typography.labelLarge)
                }
                release.asset == null -> Button(
                    onClick = { DesktopExternalLinks.open(release.url) },
                    modifier = Modifier.fillMaxWidth().height(49.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DesktopForeground, contentColor = DesktopBackground),
                ) { Text("View release") }
                !busy -> Button(
                    onClick = service::download,
                    modifier = Modifier.fillMaxWidth().height(49.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DesktopForeground, contentColor = DesktopBackground),
                ) { Text(if (state.phase in setOf(UpdatePhase.FAILED, UpdatePhase.CANCELLED)) "Retry download" else "Download update") }
            }
            if (!installing) {
                OutlinedButton(
                    onClick = { if (busy) service.cancel() else service.later() },
                    modifier = Modifier.fillMaxWidth().height(49.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(if (busy) "Cancel" else "Later", color = DesktopForeground) }
            }
        }
    }
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
