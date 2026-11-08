package com.music.bitchord.reworked

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.Http
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.smb.SmbAuth
import com.music.bitchord.data.smb.SmbClient
import com.music.bitchord.data.smb.SmbConfig
import com.music.bitchord.data.smb.SmbConnection
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

@Serializable
internal data class MobileRemoteConfig(
    val davUrl: String = "", val davUsername: String = "", val davPassword: String = "",
    val smbHost: String = "", val smbShare: String = "", val smbFolder: String = "",
    val smbUsername: String = "", val smbPassword: String = "",
)

internal enum class RemoteProtocol(val label: String) { WEBDAV("WebDAV"), SMB("SMB") }
internal data class MobileRemoteEntry(val name: String, val path: String, val directory: Boolean, val song: Song? = null)

/** Platform persistence and directory navigation around the original mobile remote clients. */
internal object MobileRemote {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configState = MutableStateFlow(runCatching {
        json.decodeFromString<MobileRemoteConfig>(DesktopStore.readText("remote-servers.json") ?: "{}")
    }.getOrDefault(MobileRemoteConfig()))
    val config = configState.asStateFlow()
    val notice = MutableStateFlow<String?>(null)
    private val authenticatedHttp = Http.client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    init { publishSmb(configState.value) }

    fun close() { scope.cancel(); MobileRemoteBridge.close(); SmbConnection.invalidate() }

    suspend fun save(value: MobileRemoteConfig) = withContext(Dispatchers.IO) {
        val normalized = value.copy(davUrl = WebDavConfig.normalizeUrl(value.davUrl), smbFolder = safeSmbPath(value.smbFolder))
        require(normalized.davUrl.isBlank() || WebDavConfig.isConfigured(normalized.davUrl)) { "Enter a valid WebDAV HTTP or HTTPS address" }
        require(normalized.smbHost.isBlank() && normalized.smbShare.isBlank() || SmbConfig.isConfigured(normalized.smbHost, normalized.smbShare)) {
            "SMB needs both a server address and share name"
        }
        DesktopStore.writeText("remote-servers.json", json.encodeToString(normalized))
        MobileRemoteBridge.invalidate()
        publishSmb(normalized)
        configState.value = normalized
    }

    fun isRemote(song: Song): Boolean = WebDavConfig.isWebDavId(song.videoId) || SmbConfig.isSmbId(song.videoId)

    suspend fun resolve(song: Song): PlayableStream = withContext(Dispatchers.IO) {
        val settings = configState.value
        if (WebDavConfig.isWebDavId(song.videoId)) {
            val url = WebDavConfig.fileUrlOf(song.videoId) ?: error("Invalid WebDAV track")
            require(davWithinRoot(url, settings.davUrl)) { "This track belongs to a different WebDAV server or folder" }
            PlayableStream(MobileRemoteBridge.davUrlFor(url, settings.davUsername, settings.davPassword), format = "WebDAV")
        } else {
            val prefix = "smb:${SmbConfig.normalizeHost(settings.smbHost)}/${settings.smbShare.trim().trim('/')}/"
            require(song.videoId.startsWith(prefix)) { "This track belongs to a different SMB server or share" }
            val path = safeSmbPath(song.videoId.removePrefix(prefix))
            require(smbWithinRoot(path, settings.smbFolder)) { "This track is outside the configured SMB folder" }
            PlayableStream(MobileRemoteBridge.smbUrlFor(path), format = "SMB")
        }
    }

    fun root(protocol: RemoteProtocol): String = when (protocol) {
        RemoteProtocol.WEBDAV -> configState.value.davUrl
        RemoteProtocol.SMB -> configState.value.smbFolder
    }

    fun parent(protocol: RemoteProtocol, path: String): String? {
        val root = root(protocol).trimEnd('/')
        if (path.trimEnd('/') == root) return null
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        return when (protocol) {
            RemoteProtocol.WEBDAV -> parent.takeIf { davWithinRoot(it, root) } ?: root
            RemoteProtocol.SMB -> parent.takeIf { smbWithinRoot(it, root) } ?: root
        }
    }

    suspend fun list(protocol: RemoteProtocol, path: String): List<MobileRemoteEntry> = withContext(Dispatchers.IO) {
        val settings = configState.value
        when (protocol) {
            RemoteProtocol.WEBDAV -> {
                require(davWithinRoot(path, settings.davUrl)) { "Configure a WebDAV server in Settings" }
                val entries = WebDavClient.propfind(path, settings.davUsername, settings.davPassword).getOrThrow()
                    .filter { davWithinRoot(it.url, settings.davUrl) }
                val image = entries.firstOrNull { !it.isCollection && it.displayName.lowercase() in COVER_NAMES }
                val cover = image?.let { fetchDavCover(it.url, settings) }
                entries.filter { it.isCollection || WebDavConfig.isAudioFile(it.displayName) }.map { entry ->
                    MobileRemoteEntry(entry.displayName, entry.url, entry.isCollection,
                        if (entry.isCollection) null else WebDavConfig.songFor(entry.url, albumName = folderName(path)).copy(thumbnailUrl = cover))
                }
            }
            RemoteProtocol.SMB -> {
                require(SmbConfig.isConfigured(settings.smbHost, settings.smbShare)) { "Configure an SMB server and share in Settings" }
                val safe = safeSmbPath(path)
                require(smbWithinRoot(safe, settings.smbFolder)) { "This path is outside the configured SMB folder" }
                val entries = SmbConnection.withShare { share -> SmbClient.listDir(share, safe) }
                val image = entries.firstOrNull { !it.isDirectory && it.path.lowercase() in COVER_NAMES }
                val imagePath = image?.let { joinSmb(safe, it.path) }
                val cover = imagePath?.let { runCatching {
                    val uri = "smb://${SmbConfig.normalizeHost(settings.smbHost)}/${settings.smbShare}/$it"
                    MobileFiles.saveArtwork(SmbClient.readFully(uri, 8L * 1024 * 1024), digest(uri))
                }.getOrNull() }
                entries.filter { it.isDirectory || SmbConfig.isAudioFile(it.path) }.map { entry ->
                    val child = joinSmb(safe, entry.path)
                    MobileRemoteEntry(entry.path, child, entry.isDirectory,
                        if (entry.isDirectory) null else SmbConfig.songFor(settings.smbHost, settings.smbShare, child, folderName(safe)).copy(thumbnailUrl = cover))
                }
            }
        }.sortedWith(compareByDescending<MobileRemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    fun chooseUpload(directory: String, onCompleted: () -> Unit) {
        val settings = configState.value
        if (!davWithinRoot(directory, settings.davUrl)) return
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply { dialogTitle = "Upload music to WebDAV"; isMultiSelectionEnabled = true }
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@invokeLater
            val chosen = chooser.selectedFiles.map { it.toPath() }.filter(MobileFiles::isAudioFile)
            scope.launch {
                var saved = 0
                var existed = 0
                try {
                    for (file in chosen) {
                        currentCoroutineContext().ensureActive()
                        notice.value = "Uploading ${file.fileName}"
                        val destination = WebDavClient.joinUrl(directory, file.fileName.toString())
                        val result = Files.newInputStream(file).use { input ->
                            WebDavClient.putFile(destination, input, Files.size(file),
                                Files.probeContentType(file) ?: "application/octet-stream", settings.davUsername, settings.davPassword,
                                overwrite = false).getOrThrow()
                        }
                        when (result) { WebDavClient.PutResult.Uploaded -> saved++; WebDavClient.PutResult.AlreadyExists -> existed++ }
                    }
                    notice.value = "$saved files uploaded" + if (existed > 0) " · $existed existing files skipped" else ""
                    withContext(Dispatchers.Main) { onCompleted() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { notice.value = error.message ?: "The upload failed" }
            }
        }
    }

    private fun fetchDavCover(url: String, settings: MobileRemoteConfig): String? = runCatching {
        val request = Request.Builder().url(url)
        WebDavConfig.basicAuthHeader(settings.davUsername, settings.davPassword)?.let { request.header("Authorization", it) }
        authenticatedHttp.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body.byteStream().use { it.readNBytes(8 * 1024 * 1024 + 1) }
            if (bytes.size > 8 * 1024 * 1024) null else MobileFiles.saveArtwork(bytes, digest(url))
        }
    }.getOrNull()

    private fun publishSmb(value: MobileRemoteConfig) = SmbAuth.update(value.smbHost, value.smbShare, value.smbFolder, value.smbUsername, value.smbPassword)
    private fun folderName(path: String): String = runCatching { java.net.URI(path).path?.trimEnd('/')?.substringAfterLast('/') }.getOrNull()
        ?: path.trimEnd('/').substringAfterLast('/')
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun joinSmb(parent: String, child: String) = safeSmbPath(if (parent.isBlank()) child else "$parent/$child")
    private val COVER_NAMES = setOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "front.png", "cover.webp")
}

internal fun davWithinRoot(url: String, root: String): Boolean {
    val candidate = url.toHttpUrlOrNull() ?: return false
    val base = root.toHttpUrlOrNull() ?: return false
    if (candidate.scheme != base.scheme || candidate.host != base.host || candidate.port != base.port) return false
    val wanted = base.pathSegments.filter { it.isNotEmpty() }
    val parts = candidate.pathSegments.filter { it.isNotEmpty() }
    if (parts.any { it == "." || it == ".." || '/' in it || '\\' in it || '\u0000' in it }) return false
    return parts.take(wanted.size) == wanted
}

internal fun safeSmbPath(path: String): String {
    val normalized = path.replace('\\', '/').trim('/')
    require(normalized.split('/').none { it == "." || it == ".." || '\u0000' in it || ':' in it }) { "Invalid SMB path" }
    return normalized
}

internal fun smbWithinRoot(path: String, root: String): Boolean = runCatching {
    val target = safeSmbPath(path)
    val folder = safeSmbPath(root)
    folder.isBlank() || target.equals(folder, true) || target.startsWith("$folder/", true)
}.getOrDefault(false)

@Composable
internal fun MobileRemoteSettings() {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column { Text("Network music", style = MaterialTheme.typography.titleMedium); Text("WebDAV and SMB libraries") }
        TextButton(onClick = { open = true }) { Text("Configure") }
    }
    if (!open) return
    var draft by remember { mutableStateOf(MobileRemote.config.value) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) open = false }, title = { Text("Network music") }, text = {
        Column(Modifier.widthIn(min = 340.dp, max = 580.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WebDAV", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(draft.davUrl, { draft = draft.copy(davUrl = it) }, label = { Text("Server folder URL") }, singleLine = true)
            OutlinedTextField(draft.davUsername, { draft = draft.copy(davUsername = it) }, label = { Text("Username") }, singleLine = true)
            OutlinedTextField(draft.davPassword, { draft = draft.copy(davPassword = it) }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            HorizontalDivider()
            Text("SMB", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(draft.smbHost, { draft = draft.copy(smbHost = it) }, label = { Text("Server or server:port") }, singleLine = true)
            OutlinedTextField(draft.smbShare, { draft = draft.copy(smbShare = it) }, label = { Text("Share name") }, singleLine = true)
            OutlinedTextField(draft.smbFolder, { draft = draft.copy(smbFolder = it) }, label = { Text("Music folder within the share") }, singleLine = true)
            OutlinedTextField(draft.smbUsername, { draft = draft.copy(smbUsername = it) }, label = { Text("Username") }, singleLine = true)
            OutlinedTextField(draft.smbPassword, { draft = draft.copy(smbPassword = it) }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Text("Leave username and password empty for guest access. Open Network music to browse after saving.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !saving, onClick = { saving = true; scope.launch {
            runCatching { MobileRemote.save(draft) }.onSuccess { open = false }.onFailure { error = it.message }
            saving = false
        } }) { Text(if (saving) "Saving…" else "Save") }
    }, dismissButton = { TextButton(onClick = { open = false }, enabled = !saving) { Text("Cancel") } })
}

@Composable
internal fun MobileRemotePage(session: MobileSession) {
    val configuration by MobileRemote.config.collectAsState()
    val notice by MobileRemote.notice.collectAsState()
    var protocol by remember { mutableStateOf(RemoteProtocol.WEBDAV) }
    var path by remember(configuration, protocol) { mutableStateOf(MobileRemote.root(protocol)) }
    var generation by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<MobileRemoteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(configuration, protocol, path, generation) {
        loading = true; error = null; rows = emptyList()
        try { rows = MobileRemote.list(protocol, path) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Cannot open this network library" }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Network music", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteProtocol.entries.forEach { value -> FilterChip(selected = protocol == value, onClick = { protocol = value }, label = { Text(value.label) }) }
            TextButton(onClick = { generation++ }, enabled = !loading) { Text("Refresh") }
            MobileRemote.parent(protocol, path)?.let { parent -> TextButton(onClick = { path = parent }) { Text("Up") } }
        }
        Text(path.ifBlank { "Configure this library in Settings" }, style = MaterialTheme.typography.bodySmall)
        Row {
            val songs = rows.mapNotNull { it.song }
            Button(enabled = songs.isNotEmpty(), onClick = { session.player.playSongs(songs) }) { Text("Play folder") }
            if (protocol == RemoteProtocol.WEBDAV) TextButton(onClick = { MobileRemote.chooseUpload(path) { generation++ } }, enabled = path.isNotBlank()) { Text("Upload music…") }
        }
        notice?.let { Text(it) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rows, key = { it.path }) { entry ->
                Row(Modifier.fillMaxWidth().clickable {
                    if (entry.directory) path = entry.path else entry.song?.let { song -> session.player.play(song) }
                }.padding(vertical = 12.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.titleSmall)
                        Text(if (entry.directory) "Folder" else entry.song?.artist.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                    entry.song?.let { song ->
                        TextButton(onClick = { session.player.addToQueue(song) }) { Text("Queue") }
                        TextButton(onClick = { MobileFiles.download(song) }) { Text("Download") }
                    }
                }
            }
        }
    }
}
