package com.music.bitchord.reworked

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.settings.DownloadQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

internal data class MobileFilePreferences(
    val quality: DownloadQuality = DownloadQuality.LOSSLESS,
    val export: Boolean = false,
    val exportDirectory: String = Path.of(System.getProperty("user.home"), "Music", "BitChordReworked").toString(),
    val syncPlaylists: Boolean = false,
)

/** Mobile download settings are independent of the currently selected streaming ceiling. */
internal object MobileFileOptions {
    private val preferences = DesktopStore.preferences
    private val mutable = MutableStateFlow(MobileFilePreferences(
        quality = runCatching { DownloadQuality.valueOf(preferences.get("download_quality", "LOSSLESS")) }.getOrDefault(DownloadQuality.LOSSLESS),
        export = preferences.getBoolean("export_downloads", false),
        exportDirectory = preferences.get("download_export_directory", MobileFilePreferences().exportDirectory),
        syncPlaylists = preferences.getBoolean("sync_downloaded_playlists", false),
    ))
    val state: StateFlow<MobileFilePreferences> = mutable.asStateFlow()
    fun setQuality(value: DownloadQuality) = change { it.copy(quality = value) }
    fun setExport(value: Boolean) = change { it.copy(export = value) }
    fun setSync(value: Boolean) = change { it.copy(syncPlaylists = value) }
    fun setDirectory(value: Path) {
        val real = value.toRealPath()
        require(Files.isDirectory(real) && Files.isWritable(real)) { "Select a writable download folder" }
        change { it.copy(exportDirectory = real.toString()) }
    }
    fun chooseDirectory(onError: (String) -> Unit) {
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply {
                dialogTitle = "Choose the download export folder"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isMultiSelectionEnabled = false
                runCatching { currentDirectory = Path.of(mutable.value.exportDirectory).toFile() }
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                runCatching { setDirectory(chooser.selectedFile.toPath()) }
                    .onFailure { onError(it.message ?: "The folder could not be selected") }
            }
        }
    }
    private fun change(transform: (MobileFilePreferences) -> MobileFilePreferences) = synchronized(this) {
        val next = transform(mutable.value)
        preferences.put("download_quality", next.quality.name)
        preferences.putBoolean("export_downloads", next.export)
        preferences.put("download_export_directory", next.exportDirectory)
        preferences.putBoolean("sync_downloaded_playlists", next.syncPlaylists)
        mutable.value = next
    }
}

@Composable
internal fun MobileFileSettings() {
    var shown by remember { mutableStateOf(false) }
    val options by MobileFileOptions.state.collectAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("Local music and downloads", style = MaterialTheme.typography.titleMedium)
            Text("${options.quality.label} downloads · ${if (options.export) "Export to music folder" else "App storage"}")
        }
        TextButton(onClick = { shown = true }) { Text("Manage") }
    }
    if (!shown) return
    val folders by MobileFiles.folders.collectAsState()
    val scanning by MobileFiles.scanning.collectAsState()
    val collections by MobileDownloadedCollections.records.collectAsState()
    val pending by MobileDownloadedCollections.approval.collectAsState()
    val syncing by MobileDownloadedCollections.syncing.collectAsState()
    val syncMessage by MobileDownloadedCollections.message.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { shown = false }, title = { Text("Local music and downloads") },
        text = {
            Column(Modifier.widthIn(min = 340.dp, max = 620.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Download quality", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DownloadQuality.entries.forEach { quality ->
                        FilterChip(selected = options.quality == quality, onClick = { MobileFileOptions.setQuality(quality) }, label = { Text(quality.label) })
                    }
                }
                Text(options.quality.detail)
                Text("This choice applies to the next download. Streaming quality stays separate.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text("Export downloads"); Text("Save ordinary audio files for other music players", style = MaterialTheme.typography.bodySmall) }
                    Switch(checked = options.export, onCheckedChange = MobileFileOptions::setExport)
                }
                if (options.export) {
                    Text(options.exportDirectory, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { MobileFileOptions.chooseDirectory { error = it } }) { Text("Choose export folder") }
                    Text("Completed files stay in their existing folder when this setting changes.", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text("Keep downloaded playlists in sync"); Text("Add new tracks while the app is open", style = MaterialTheme.typography.bodySmall) }
                    Switch(checked = options.syncPlaylists, onCheckedChange = MobileFileOptions::setSync)
                }
                Text("A removed file stays removed. Tracks removed online remain on this device. Albums and imported playlists are excluded.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { MobileDownloadedCollections.checkNow() }, enabled = options.syncPlaylists && !syncing) { Text(if (syncing) "Checking playlists…" else "Check now") }
                syncMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                pending.forEach { (id, item) ->
                    Text("${item.title}: ${item.count} new tracks")
                    TextButton(onClick = { MobileDownloadedCollections.approve(id) }, enabled = !syncing) { Text("Download these tracks") }
                }
                collections.filter { MobilePlaylistRules.syncableBrowseId(it) != null }.forEach { collection ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(collection.title, Modifier.weight(1f))
                        TextButton(onClick = { MobileDownloadedCollections.forget(collection.id) }) { Text("Stop tracking") }
                    }
                }
                HorizontalDivider()
                Text("Music folders", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = MobileFiles::chooseFolder, enabled = !scanning) { Text("Add folder") }
                    TextButton(onClick = MobileFiles::scanAgain, enabled = !scanning && folders.isNotEmpty()) { Text(if (scanning) "Scanning…" else "Rescan") }
                }
                if (folders.isEmpty()) Text("Add a folder to find local tracks, album art, and lyrics.")
                folders.forEach { folder ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(folder, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { MobileFiles.forgetFolder(folder) }, enabled = !scanning) { Text("Forget") }
                    }
                }
                Text("Forgetting a folder only removes its tracks from this library.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { shown = false }) { Text("Done") } },
    )
}
