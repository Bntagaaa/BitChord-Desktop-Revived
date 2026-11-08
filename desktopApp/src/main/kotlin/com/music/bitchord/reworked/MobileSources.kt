package com.music.bitchord.reworked

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.SourceTrackKeys
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.sources.addon.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class MobileSourceConfig(
    val id: String,
    val baseUrl: String,
    val name: String,
    val kind: String = "addon",
    val enabled: Boolean = true,
    val pickedQuality: String? = null,
    val qualityOptions: List<AddonQualityOption> = emptyList(),
    val downloadsAllowed: Boolean = true,
    val needsAndroidVerification: Boolean = false,
    val needsLosslessOutput: Boolean = false,
) {
    val unavailableReason: String? get() = when {
        needsAndroidVerification -> AddonClient.DESKTOP_ATTESTATION_REQUIRED
        needsLosslessOutput -> "This addon requires a verified lossless output. The desktop audio device cannot currently be verified."
        else -> null
    }
}

/**
 * A JVM host for the original mobile addon protocol and TrackMatcher. The mobile
 * manifest, quality settings, encrypted-stream refusal and source IDs are retained.
 * Android hardware attestation is never replaced with a fabricated desktop token.
 */
internal object MobileSources {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sourceState = MutableStateFlow(runCatching {
        DesktopStore.readText("sources.json")?.let { json.decodeFromString<List<MobileSourceConfig>>(it) }
            ?.filter { it.baseUrl.toHttpUrlOrNull() != null }?.distinctBy { it.id }
            ?: listOf(MobileJioSaavn.config)
    }.getOrDefault(emptyList()))
    val sources: StateFlow<List<MobileSourceConfig>> = sourceState.asStateFlow()
    private val errorState = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = errorState.asStateFlow()
    val quality: StateFlow<String> = MobilePlayerSettings.audioQualityWifi.map { it.addonTier() }
        .stateIn(scope, SharingStarted.Eagerly, MobilePlayerSettings.audioQualityWifi.value.addonTier())
    private val currentCeiling get() = MobilePlayerSettings.streamCeilingOverride.value ?: MobilePlayerSettings.audioQualityWifi.value
    private val currentQuality get() = currentCeiling.addonTier()
    private val clients = ConcurrentHashMap<String, AddonClient>()
    private val rows = ConcurrentHashMap<String, AddonTrack>()

    init {
        // Original mobile AndroidStreamHooks installs this same shared resolver callback.
        StreamResolver.maxKbps = { currentCeiling.maxKbps }
    }

    suspend fun add(rawUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(rawUrl.length <= 8192) { "The addon address is too long" }
            val found = SourceFormats.identify(rawUrl).getOrThrow()
            val config = when (found) {
                is DetectedFormat.Addon -> MobileSourceConfig(
                    id = UUID.randomUUID().toString(), baseUrl = found.baseUrl,
                    name = found.manifest.displayName.ifBlank { found.baseUrl.toHttpUrlOrNull()?.host ?: "Addon" },
                ).withManifest(found.manifest)
                is DetectedFormat.ModuleIndex -> MobileSourceConfig(
                    id = UUID.randomUUID().toString(), baseUrl = found.url,
                    name = "${found.url.toHttpUrlOrNull()?.host ?: "Source"} · ${found.moduleCount} modules", kind = "module",
                )
                is DetectedFormat.Unsupported -> error(found.reason)
            }
            synchronized(this@MobileSources) {
                require(sourceState.value.none { it.baseUrl == config.baseUrl }) { "This addon is already in your sources" }
                val current = sourceState.value.toMutableList()
                val fallback = current.indexOfFirst { it.kind == "jiosaavn" }.takeIf { it >= 0 } ?: current.size
                current.add(fallback, config)
                sourceState.value = current
                persist()
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    fun setEnabled(id: String, enabled: Boolean) = change(id) { it.copy(enabled = enabled) }
    fun restoreJioSaavn() { synchronized(this) {
        if (sourceState.value.none { it.kind == "jiosaavn" }) { sourceState.value = sourceState.value + MobileJioSaavn.config; persist() }
    } }
    fun setQuality(id: String, value: String?) = change(id) { config ->
        require(value == null || config.qualityOptions.any { it.value == value }) { "Unknown addon quality" }
        clients.remove(id)?.clear()
        MobileModuleSources.clear(id)
        config.copy(pickedQuality = value)
    }
    fun setQuality(value: String) {
        require(value in listOf(AddonClient.TIER_LOSSLESS, AddonClient.TIER_HIGH, AddonClient.TIER_LOW))
        MobilePlayerSettings.setQuality(AudioQuality.valueOf(value))
    }
    fun remove(id: String) {
        synchronized(this) { sourceState.value = sourceState.value.filterNot { it.id == id }; persist() }
        clients.remove(id)?.clear()
        MobileModuleSources.clear(id)
        rows.keys.removeIf { it.startsWith("src:$id::") }
        errorState.update { it - id }
    }
    fun move(id: String, offset: Int) {
        synchronized(this) {
            val list = sourceState.value.toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index < 0 || index + offset !in list.indices) return
            list.add(index + offset, list.removeAt(index))
            sourceState.value = list
            persist()
        }
    }
    fun refresh(id: String) { scope.launch {
        sourceState.value.firstOrNull { it.id == id }?.let { config ->
            clients.remove(id)?.clear()
            MobileModuleSources.clear(id)
            runCatching {
                if (config.kind == "module") MobileModuleSources.check(config)
                if (config.kind == "jiosaavn") MobileJioSaavn.search("music")
                policy(config).also { check(it.unavailableReason == null) { it.unavailableReason!! } }
            }
                .onSuccess { errorState.update { it - id } }
                .onFailure { recordError(id, it) }
        }
    } }

    suspend fun resolve(song: Song): PlayableStream = resolveInternal(song, null)
    suspend fun resolveForDownload(
        song: Song,
        quality: DownloadQuality = MobileFileOptions.state.value.quality,
        export: Boolean = MobileFileOptions.state.value.export,
    ): PlayableStream = resolveInternal(song, MobileDownloadRequest(quality, export))

    private suspend fun resolveInternal(song: Song, download: MobileDownloadRequest?): PlayableStream = withContext(Dispatchers.IO) {
        val playbackCeiling = currentCeiling
        if (download == null) MobileFiles.downloadedSong(song.videoId)?.let { return@withContext resolveMobileStream(it) }
        if (MobileRemote.isRemote(song)) return@withContext MobileRemote.resolve(song)
        if (!song.localPath.isNullOrBlank() || !song.localUri.isNullOrBlank()) return@withContext resolveMobileStream(song)
        // Original mobile SourceResolver rejects target.isVideo for substitution.
        // The separate picture decoder and this audio must use the same recording.
        if (song.isVideo) return@withContext if (download == null) resolveMobileStream(song) else youtubeDownload(song, download)
        val pinned = SourceTrackKeys.parse(song.videoId)
        if (pinned != null) {
            try {
                val config = sourceState.value.firstOrNull { it.id == pinned.first && it.enabled }
                    ?: error("The source for this track is disabled or has been removed")
                val live = policy(config)
                check(live.unavailableReason == null) { live.unavailableReason!! }
                check(download != null || permitsMobileSourcePlayback(live.kind, playbackCeiling)) {
                    if (live.kind == "jiosaavn") "Choose High or Lossless streaming quality to play a JioSaavn track"
                    else "Choose Lossless streaming quality to play this addon track. Download quality is a separate setting."
                }
                val tier = download?.tier ?: playbackCeiling.addonTier()
                var row = rows[song.videoId]
                if (row == null && live.kind == "addon") {
                    // Search-only addons carry streamURL on their row; recover it after a restart.
                    for (query in TrackMatcher.queries(TrackMatcher.targetOf(song)).take(2)) {
                        row = searchSource(live, query, tier).firstOrNull { it.id == pinned.second }
                        if (row != null) break
                    }
                }
                return@withContext playableAddonStream(streamAnswer(live, pinned.second, row, download != null, tier))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (download == null) throw error
                recordError(pinned.first, error)
            }
        }
        if (download != null) {
            downloadFromSources(song, download)?.let { return@withContext it }
            check(pinned == null) { "Could not download this track from its source. Check that it is connected and permits downloads." }
            return@withContext youtubeDownload(song, download)
        }
        val target = TrackMatcher.targetOf(song)
        for (original in sourceState.value.filter { it.enabled }) {
            currentCoroutineContext().ensureActive()
            if (!permitsMobileSourcePlayback(original.kind, playbackCeiling)) continue
            try {
                val config = policy(original)
                if (config.unavailableReason != null) continue
                matchSource(config, target, playbackCeiling.addonTier(), false)?.let { answer ->
                    errorState.update { it - config.id }
                    return@withContext playableAddonStream(answer)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { recordError(original.id, error) }
        }
        resolveMobileStream(song)
    }

    private suspend fun youtubeDownload(song: Song, request: MobileDownloadRequest): PlayableStream {
        val stream = StreamResolver.resolveForDownload(song.videoId, request.quality.maxKbps, requireM4a = request.export)
        return PlayableStream(stream.url, StreamResolver.mediaHeadersFor(stream.url),
            "${stream.kbps} kbps ${stream.downloadExtension.uppercase()}", boundedRanges = stream.url.toHttpUrlOrNull()?.queryParameter("clen") != null)
    }

    private suspend fun downloadFromSources(song: Song, request: MobileDownloadRequest): PlayableStream? = coroutineScope {
        // Mobile Standard is YouTube's capped ladder. A 320 kbps source is not that request.
        if (!request.considersMatchedSources) return@coroutineScope null
        val target = TrackMatcher.targetOf(song)
        val deadline = System.nanoTime() + 45_000_000_000L
        val candidates = sourceState.value.filter { it.enabled }.map { original ->
            async {
                try {
                    val config = policy(original)
                    if (config.unavailableReason != null || !config.downloadsAllowed) return@async null
                    matchSource(config, target, request.tier, true)?.let { answer ->
                        playableAddonStream(answer) // Validate the transport before it can win a download.
                        MobileDownloadCandidate(answer, addonFormat(answer, request.tier))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { recordError(original.id, error); null }
            }
        }
        var lossy: MobileDownloadCandidate? = null
        var losslessReserve: MobileDownloadCandidate? = null
        try {
            // All requests start together, but lossless answers are read in the listener's source order.
            for (work in candidates) {
                val candidate = if (work.isCompleted) work.await() else withTimeoutOrNull(
                    ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1),
                ) { work.await() }
                candidate ?: continue
                if (candidate.format.isLossless == true) {
                    if (request.quality.keepsLossless) return@coroutineScope playableAddonStream(candidate.answer)
                    if (losslessReserve == null) losslessReserve = candidate
                } else if ((candidate.format.kbps ?: 0) > (lossy?.format?.kbps ?: 0)) lossy = candidate
            }
            // Original mobile SourceResolver compares lossy downloads with YouTube's 256 kbps AAC ceiling.
            val chosen = lossy?.takeIf { (it.format.kbps ?: 0) > 256 } ?: losslessReserve
            chosen?.let { playableAddonStream(it.answer) }
        } finally { candidates.forEach { it.cancel() } }
    }

    private suspend fun matchSource(
        config: MobileSourceConfig, target: TrackMatcher.Target, tier: String, forDownload: Boolean,
    ): AddonStream? {
        val candidates = linkedMapOf<String, AddonTrack>()
        for (query in TrackMatcher.queries(target).take(3)) {
            searchSource(config, query, tier).filter { it.id.isNotBlank() && it.title.isNotBlank() && !it.isDolbyAtmos }
                .forEach { candidates[it.id] = it }
            val ranked = TrackMatcher.ranked(candidates.values.map { it.toSong(config.id) }, target)
            for (matched in ranked.take(3)) {
                try {
                    val id = SourceTrackKeys.parse(matched.videoId)!!.second
                    return streamAnswer(config, id, candidates[id], forDownload, tier)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { recordError(config.id, error) }
            }
            if (ranked.isNotEmpty()) break
        }
        return null
    }

    /** The mobile Addons search tab can use these source-owned IDs directly. */
    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val found = mutableListOf<Song>()
        for (original in sourceState.value.filter { it.enabled }) {
            try {
                val config = policy(original)
                if (config.unavailableReason != null) continue
                searchSource(config, query.trim()).take(100)
                    .filter { it.id.isNotBlank() && it.title.isNotBlank() && !it.isDolbyAtmos }
                    .forEach { track ->
                        val song = track.toSong(config.id)
                        if (rows.size >= 1000) rows.clear()
                        rows[song.videoId] = track
                        found += song
                    }
                errorState.update { it - config.id }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { recordError(original.id, error) }
        }
        found.distinctBy { it.videoId }
    }

    private suspend fun streamAnswer(config: MobileSourceConfig, id: String, row: AddonTrack?, forDownload: Boolean, tier: String): AddonStream {
        check(config.unavailableReason == null) { config.unavailableReason!! }
        check(!forDownload || config.downloadsAllowed) { "This addon does not allow downloads" }
        if (!row?.streamURL.isNullOrBlank()) {
            return AddonStream(url = row!!.streamURL!!, headers = row.headers, format = row.format, quality = row.audioQuality)
        }
        return when (config.kind) {
            "module" -> MobileModuleSources.stream(config, id, tier)
            "jiosaavn" -> MobileJioSaavn.stream(id)
            else -> client(config).stream(id, tier, stereoOnly = true).getOrThrow()
        }
    }

    private fun client(config: MobileSourceConfig): AddonClient = clients.computeIfAbsent(config.id) {
        AddonClient(config.baseUrl, config.pickedQuality)
    }

    private suspend fun policy(config: MobileSourceConfig): MobileSourceConfig {
        if (config.kind != "addon") return config
        val manifest = client(config).manifest().getOrNull() ?: return config
        val updated = config.withManifest(manifest)
        if (config != updated) change(config.id) { live -> live.withManifest(manifest) }
        return updated
    }

    private suspend fun searchSource(config: MobileSourceConfig, query: String, tier: String = currentQuality): List<AddonTrack> = when (config.kind) {
        "module" -> MobileModuleSources.search(config, query, tier)
        "jiosaavn" -> MobileJioSaavn.search(query)
        else -> client(config).search(query, tier).getOrThrow()
    }

    private fun change(id: String, update: (MobileSourceConfig) -> MobileSourceConfig) {
        synchronized(this) {
            sourceState.value = sourceState.value.map { if (it.id == id) update(it) else it }
            persist()
        }
    }
    private fun persist() = DesktopStore.writeText("sources.json", json.encodeToString(sourceState.value))
    private fun recordError(id: String, error: Throwable) {
        val message = error.message.orEmpty().replace(Regex("https?://\\S+"), "[addon address]").take(220)
        errorState.update { it + (id to message.ifBlank { "This addon could not answer" }) }
    }
}

internal data class MobileDownloadRequest(val quality: DownloadQuality, val export: Boolean) {
    val tier: String get() = when (quality) {
        DownloadQuality.STANDARD -> AddonClient.TIER_LOW
        DownloadQuality.HIGH -> AddonClient.TIER_HIGH
        DownloadQuality.LOSSLESS -> AddonClient.TIER_LOSSLESS
    }
    val considersMatchedSources: Boolean get() = quality != DownloadQuality.STANDARD
}

/** Direct mobile AppSettings.AudioQuality.permits mapping, without Android connectivity APIs. */
internal fun permitsMobileSourcePlayback(kind: String, quality: AudioQuality): Boolean = when (quality) {
    AudioQuality.LOSSLESS -> true
    AudioQuality.HIGH -> kind == "jiosaavn" || kind == "youtube"
    AudioQuality.MEDIUM, AudioQuality.LOW -> kind == "youtube"
}
private data class MobileDownloadCandidate(val answer: AddonStream, val format: StreamFormat)

/** Original mobile AddonSource format precedence; unknown codecs stay unknown. */
internal fun addonFormat(answer: AddonStream, tier: String): StreamFormat {
    val codecs = setOf("flac", "alac", "wav", "aiff", "mp3", "aac", "he-aac", "m4a", "mp4", "ogg", "opus", "vorbis", "webm", "eac3-joc", "ec3-joc")
    val codec = answer.statedCodec?.takeIf { it in codecs }
        ?: answer.statedContainer?.takeIf { it in setOf("flac", "wav", "aiff", "mp3") }
        ?: answer.mimeType?.substringAfterLast('/')?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it in codecs }
        ?: answer.format.lowercase().takeIf { it in codecs }
        ?: "flac".takeIf { Regex("lossless|hi[ -]?res|flac|alac", RegexOption.IGNORE_CASE).containsMatchIn(answer.qualityText) }
        ?: answer.url.substringBefore('?').substringAfterLast('.').lowercase().takeIf { it in codecs }
    val inferred = when (tier) { AddonClient.TIER_HIGH -> 320; AddonClient.TIER_LOW -> 128; else -> null }
    return StreamFormat(codec, answer.kbps ?: inferred, answer.sampleRateHz, answer.bits, answer.kbps == null && inferred != null)
}

private fun AudioQuality.addonTier(): String = if (this == AudioQuality.MEDIUM) AddonClient.TIER_HIGH else name

private fun MobileSourceConfig.withManifest(manifest: AddonManifest) = copy(
    name = manifest.displayName.ifBlank { name }, qualityOptions = manifest.qualityOptions,
    downloadsAllowed = manifest.downloadsAllowed, needsAndroidVerification = manifest.requiresBitChordAuth,
    needsLosslessOutput = manifest.requiresLosslessOutput,
)

private fun AddonTrack.toSong(sourceId: String) = Song(
    videoId = "src:$sourceId::$id", title = title, artist = artist, thumbnailUrl = artwork,
    durationText = durationSec?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
    albumName = album.ifBlank { null }, sourceHiRes = isHiRes,
    isExplicit = isExplicit,
    sourceQuality = when {
        Regex("lossless|flac|alac|hi.?res", RegexOption.IGNORE_CASE).containsMatchIn("$audioQuality $format") -> "LOSSLESS"
        Regex("low|96|128", RegexOption.IGNORE_CASE).containsMatchIn(audioQuality) -> "LOW"
        else -> "HIGH"
    },
)

internal fun playableAddonStream(answer: AddonStream): PlayableStream {
    check(!answer.isEncrypted) { "This addon returned encrypted media; no decryption keys were requested" }
    check(!answer.isDolbyAtmos) { "This addon returned an Atmos stream after a stereo request" }
    check(answer.url.isNotBlank()) { answer.error?.take(220) ?: "This addon has no playable stream for the track" }
    require(answer.url.toHttpUrlOrNull() != null) { "The addon returned a media URL that is not HTTP or HTTPS" }
    require(answer.headers.all { (name, value) ->
        name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && '\r' !in value && '\n' !in value
    }) { "The addon returned invalid stream headers" }
    return PlayableStream(answer.url, answer.headers, answer.statedCodec ?: answer.format.ifBlank { answer.quality.ifBlank { null } })
}

@Composable
internal fun ReworkedSourceSettings() {
    var shown by remember { mutableStateOf(false) }
    val sources by MobileSources.sources.collectAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column { Text("Music sources", style = MaterialTheme.typography.titleMedium); Text("${sources.count { it.enabled }} enabled sources") }
        TextButton(onClick = { shown = true }) { Text("Manage sources") }
    }
    if (!shown) return
    var url by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failures by MobileSources.errors.collectAsState()
    val quality by MobileSources.quality.collectAsState()
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!adding) shown = false },
        title = { Text("Music sources") },
        text = {
            Column(Modifier.widthIn(min = 320.dp, max = 620.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("HTTP addons, JavaScript module indexes, and JioSaavn are tried in order using the mobile track matcher. YouTube Music remains the fallback.")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("LOSSLESS" to "Best", "HIGH" to "High", "LOW" to "Low").forEach { (value, label) ->
                        FilterChip(selected = quality == value, onClick = { MobileSources.setQuality(value) }, label = { Text(label) })
                    }
                }
                sources.forEachIndexed { index, source ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(source.name, style = MaterialTheme.typography.titleSmall)
                                Text((when (source.kind) { "module" -> "Module index · "; "jiosaavn" -> "Built-in · "; else -> "HTTP addon · " }) + source.baseUrl.toHttpUrlOrNull()?.host.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = source.enabled, onCheckedChange = { MobileSources.setEnabled(source.id, it) })
                        }
                        (source.unavailableReason ?: failures[source.id])?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (!source.downloadsAllowed) Text("Streaming only · downloads disabled by the addon", style = MaterialTheme.typography.bodySmall)
                        if (source.kind == "jiosaavn") Text("Used at High or Lossless audio quality", style = MaterialTheme.typography.bodySmall)
                        if (source.qualityOptions.isNotEmpty()) {
                            var choices by remember(source.id) { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { choices = true }) {
                                    Text("Quality: " + (source.qualityOptions.firstOrNull { it.value == source.pickedQuality }?.label ?: "Addon default"))
                                }
                                DropdownMenu(expanded = choices, onDismissRequest = { choices = false }) {
                                    DropdownMenuItem(text = { Text("Addon default") }, onClick = { MobileSources.setQuality(source.id, null); choices = false })
                                    source.qualityOptions.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                        MobileSources.setQuality(source.id, option.value); choices = false
                                    }) }
                                }
                            }
                        }
                        Row {
                            TextButton(onClick = { MobileSources.move(source.id, -1) }, enabled = index > 0) { Text("Up") }
                            TextButton(onClick = { MobileSources.move(source.id, 1) }, enabled = index < sources.lastIndex) { Text("Down") }
                            TextButton(onClick = { MobileSources.refresh(source.id) }) { Text("Check") }
                            TextButton(onClick = { MobileSources.remove(source.id) }) { Text("Remove") }
                        }
                        HorizontalDivider()
                    }
                }
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Addon, manifest, or module index URL") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !adding)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (sources.none { it.kind == "jiosaavn" }) TextButton(onClick = MobileSources::restoreJioSaavn) { Text("Restore JioSaavn") }
                Button(enabled = url.isNotBlank() && !adding, onClick = {
                    adding = true; error = null
                    scope.launch {
                        MobileSources.add(url).onSuccess { url = "" }.onFailure { error = it.message ?: "Could not add this source" }
                        adding = false
                    }
                }) { Text(if (adding) "Checking source…" else "Add source") }
            }
        },
        confirmButton = { TextButton(onClick = { shown = false }, enabled = !adding) { Text("Done") } },
    )
}
