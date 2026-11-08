package com.music.bitchord.reworked

import com.music.bitchord.data.Http
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.toEnhancedLrc
import com.music.bitchord.data.lyrics.toLrc
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.download.DownloadTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_ATTACHED_PIC
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/**
 * New filesystem adapter for mobile LocalMediaRepository/Downloads semantics:
 * file tags win over filenames, downloaded source IDs survive a rescan, and a
 * partial transfer never appears in the completed library.
 */
internal object MobileFiles {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanLock = Mutex()
    private val downloadSlots = Semaphore(2)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val filesDirectory get() = DesktopStore.root.resolve("downloads")
    private val downloadRoots = MutableStateFlow(runCatching {
        json.decodeFromString<List<String>>(DesktopStore.readText("download-roots.json") ?: "[]")
    }.getOrDefault(emptyList()))
    private val localState = MutableStateFlow(readSongs("local-songs.json").filter(::localFileExists))
    private val downloadState = MutableStateFlow(readSongs("downloads.json").filter { localFileExists(it) && isOwnedDownload(it.localPath) })
    val localSongs: StateFlow<List<Song>> = localState.asStateFlow()
    val downloads: StateFlow<List<Song>> = downloadState.asStateFlow()
    private val activeState = MutableStateFlow<Map<String, Float>>(emptyMap())
    val active: StateFlow<Map<String, Float>> = activeState.asStateFlow()
    private val errorState = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = errorState.asStateFlow()
    private val messageState = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = messageState.asStateFlow()
    private val scanState = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = scanState.asStateFlow()
    private val folderState = MutableStateFlow(runCatching {
        json.decodeFromString<List<String>>(DesktopStore.readText("music-folders.json") ?: "[]")
    }.getOrDefault(emptyList()))
    val folders: StateFlow<List<String>> = folderState.asStateFlow()

    fun start() { MobileDownloadedCollections.start() }
    fun close() { scope.cancel(); MobileDownloadedCollections.close() }

    fun downloadPlaylist(id: String, title: String, songs: List<Song>) = downloadCollection(id, title, songs, playlist = true)
    fun downloadCollection(id: String, title: String, songs: List<Song>, playlist: Boolean, subtitle: String = "", thumbnailUrl: String? = null) {
        start()
        MobileDownloadedCollections.download(DownloadTarget(id, title, subtitle, thumbnailUrl, playlist), songs)
    }

    internal suspend fun downloadBatch(songs: List<Song>, expectedListenerRevision: Long? = null): Set<String> {
        val unique = songs.distinctBy { it.videoId }
        val queued = unique.mapNotNull { enqueue(it, expectedListenerRevision) }
        queued.joinAll()
        currentCoroutineContext().ensureActive()
        return unique.filter { downloadedSong(it.videoId) != null }.mapTo(HashSet()) { it.videoId }
    }

    fun chooseFolder() {
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply {
                dialogTitle = "Add a music folder"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isMultiSelectionEnabled = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                scope.launch { runCatching { scanFolder(chooser.selectedFile.toPath()) }
                    .onFailure { if (it !is CancellationException) messageState.value = it.message ?: "Could not scan the folder" } }
            }
        }
    }

    fun scanAgain() { scope.launch {
        for (folder in folderState.value) {
            try { scanFolder(Path.of(folder)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { messageState.value = error.message ?: "Could not rescan a music folder" }
        }
    } }

    suspend fun scanFolder(path: Path) = withContext(Dispatchers.IO) {
        scanLock.withLock {
            val directory = path.toRealPath()
            require(Files.isDirectory(directory)) { "Select a music folder" }
            scanState.value = true
            try {
                val knownDownloads = downloadState.value.associateBy { it.localUri }
                val songs = mutableListOf<Song>()
                var skipped = 0
                Files.walk(directory).use { paths ->
                    val iterator = paths.iterator()
                    while (iterator.hasNext()) {
                        currentCoroutineContext().ensureActive()
                        val candidate = iterator.next()
                        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) || !isAudioFile(candidate)) continue
                        try {
                            val song = inspect(candidate)
                            val known = knownDownloads[song.localUri]
                            songs += if (known != null) known.copy(
                                localPath = song.localPath, thumbnailUrl = song.thumbnailUrl ?: known.thumbnailUrl,
                                localDateModifiedSeconds = song.localDateModifiedSeconds,
                            ) else song
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { skipped++ }
                    }
                }
                val otherFolders = localState.value.filterNot { song ->
                    song.localPath?.let { runCatching { Path.of(it).startsWith(directory) }.getOrDefault(false) } == true
                }
                val combined = (otherFolders + songs).distinctBy { it.localUri }.sortedWith(
                    compareBy<Song> { it.artist.lowercase(Locale.ROOT) }.thenBy { it.title.lowercase(Locale.ROOT) },
                )
                DesktopStore.writeText("local-songs.json", json.encodeToString(SongListSerializer, combined))
                val folderList = (folderState.value + directory.toString()).distinct()
                DesktopStore.writeText("music-folders.json", json.encodeToString(folderList))
                localState.value = combined
                folderState.value = folderList
                messageState.value = "${songs.size} tracks scanned" + if (skipped > 0) " · $skipped files could not be read" else ""
            } finally { scanState.value = false }
        }
    }

    fun forgetFolder(path: String) { scope.launch { scanLock.withLock {
        val directory = Path.of(path)
        val remaining = folderState.value - path
        val keep = localState.value.filterNot { song ->
            song.localPath?.let { name ->
                val file = Path.of(name)
                file.startsWith(directory) && remaining.none { file.startsWith(Path.of(it)) }
            } == true
        }
        DesktopStore.writeText("local-songs.json", json.encodeToString(SongListSerializer, keep))
        DesktopStore.writeText("music-folders.json", json.encodeToString(remaining))
        localState.value = keep
        folderState.value = remaining
    } } }

    fun downloadedSong(id: String): Song? = downloadState.value.firstOrNull { it.videoId == id && localFileExists(it) }

    suspend fun lyricsFor(song: Song): List<LyricLine>? {
        val fileSong = if (localFileExists(song)) song else downloadedSong(song.videoId) ?: return null
        return fileSong.localPath?.let { EmbeddedLyrics.forPath(Path.of(it)) }
    }

    fun download(song: Song) { enqueue(song) }

    private fun enqueue(song: Song, expectedListenerRevision: Long? = null): Job? {
        if (localFileExists(song) || downloadedSong(song.videoId) != null) {
            messageState.value = "This track is already available offline"
            return null
        }
        synchronized(jobs) {
            jobs[song.videoId]?.let { return it }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var temporary: Path? = null
                var completed: Path? = null
                try {
                    activeState.update { it + (song.videoId to 0f) }
                    errorState.update { it - song.videoId }
                    downloadSlots.withPermit {
                        // Pin this track's settings once, independently of the streaming ceiling.
                        val options = MobileFileOptions.state.value
                        val source = if (expectedListenerRevision == null) MobileSources.resolveForDownload(song, options.quality, options.export)
                            else MobileYouTubeAccounts.withListener(expectedListenerRevision) {
                                MobileSources.resolveForDownload(song, options.quality, options.export)
                            }
                        currentCoroutineContext().ensureActive()
                        val requestedDirectory = if (options.export) Path.of(options.exportDirectory) else filesDirectory
                        Files.createDirectories(requestedDirectory)
                        val directory = requestedDirectory.toRealPath()
                        require(Files.isWritable(directory)) { "The download folder is not writable" }
                        rememberDownloadRoot(directory)
                        val token = UUID.randomUUID().toString()
                        temporary = directory.resolve(".$token.part")
                        val context = currentCoroutineContext()
                        val written = MobileDownloadWriter.write(source, temporary!!, cancelled = { !context.isActive }, progress = { value ->
                            activeState.update { it + (song.videoId to value.coerceIn(0f, 0.99f)) }
                        }, title = song.title, artist = song.artist, album = song.albumName, export = options.export)
                        currentCoroutineContext().ensureActive()
                        val target = directory.resolve("${fileId(song.videoId)}-$token.${written.extension}")
                        val media = inspect(temporary!!)
                        require(Files.size(temporary!!) > 0) { "The source returned an empty download" }
                        val cover = media.thumbnailUrl ?: downloadArtwork(song.thumbnailUrl, fileId(song.videoId)) ?: song.thumbnailUrl
                        atomicMove(temporary!!, target)
                        completed = target
                        val time = System.currentTimeMillis() / 1000
                        val saved = song.copy(
                            localUri = target.toUri().toString(), localPath = target.toString(), thumbnailUrl = cover,
                            durationText = media.durationText ?: song.durationText, downloadFormat = written.codec,
                            localDateAddedSeconds = time, localDateModifiedSeconds = time, isVideo = false,
                        )
                        synchronized(downloadState) {
                            context.ensureActive()
                            val records = (downloadState.value.filterNot { it.videoId == saved.videoId } + saved)
                            DesktopStore.writeText("downloads.json", json.encodeToString(SongListSerializer, records))
                            downloadState.value = records
                        }
                        completed = null // Ownership has transferred to the persisted library.
                        messageState.value = "Downloaded ${song.title}"
                        // Lyrics are optional metadata. Their failure cannot revoke complete audio.
                        runCatching {
                            if (expectedListenerRevision != null && expectedListenerRevision != MobileYouTubeAccounts.state.value.revision) return@runCatching
                            val words = lyricsFor(song) ?: withTimeoutOrNull(20_000) {
                                LyricsRepository.lyrics(song.videoId, song.title, song.artist,
                                    (TrackMatcher.secondsOf(song.durationText)?.toLong() ?: 0L) * 1000,
                                    album = song.albumName, order = MobilePlayerSettings.lyricsSourceOrder.value)?.lines
                            }
                            if (!words.isNullOrEmpty()) {
                                currentCoroutineContext().ensureActive()
                                val lrc = words.toEnhancedLrc().ifBlank { words.toLrc() }
                                val sidecar = target.resolveSibling(target.fileName.toString().substringBeforeLast('.') + ".lrc")
                                val temporaryLyrics = Files.createTempFile(directory, ".lyrics-", ".tmp")
                                try { Files.writeString(temporaryLyrics, lrc); atomicMove(temporaryLyrics, sidecar) }
                                finally { Files.deleteIfExists(temporaryLyrics) }
                            }
                        }.onFailure { if (it is CancellationException) throw it }
                    }
                } catch (cancelled: CancellationException) {
                    if (expectedListenerRevision != null && expectedListenerRevision != MobileYouTubeAccounts.state.value.revision)
                        messageState.value = "A queued collection download stopped because the YouTube profile changed"
                    throw cancelled
                }
                catch (error: Exception) {
                    errorState.update { it + (song.videoId to (error.message?.take(240) ?: "Download failed")) }
                } finally {
                    temporary?.let { runCatching { Files.deleteIfExists(it) } }
                    completed?.let { runCatching { Files.deleteIfExists(it) } }
                    activeState.update { it - song.videoId }
                    jobs.remove(song.videoId)
                }
            }
            jobs[song.videoId] = job
            job.start()
            return job
        }
    }

    fun cancelDownload(id: String) { jobs[id]?.cancel() }

    fun removeDownload(id: String) { scope.launch {
        jobs[id]?.cancelAndJoin()
        try {
            synchronized(downloadState) {
                val song = downloadState.value.firstOrNull { it.videoId == id } ?: return@synchronized
                check(isOwnedDownload(song.localPath)) { "This file is outside the recorded Reworked download folders" }
                Files.deleteIfExists(Path.of(song.localPath!!))
                val path = Path.of(song.localPath)
                Files.deleteIfExists(path.resolveSibling(path.fileName.toString().substringBeforeLast('.') + ".lrc"))
                val remaining = downloadState.value.filterNot { it.videoId == id }
                DesktopStore.writeText("downloads.json", json.encodeToString(SongListSerializer, remaining))
                downloadState.value = remaining
            }
        } catch (error: Exception) { errorState.update { it + (id to (error.message ?: "Could not remove the download")) } }
    } }

    /** Same tag fallbacks as mobile LocalMediaRepository, using FFmpeg instead of MediaStore. */
    internal fun inspect(path: Path): Song {
        val file = path.toRealPath()
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        val id = file.toUri().toString()
        return FFmpegFrameGrabber(file.toFile()).use { media ->
            media.setOption("analyzeduration", "3000000")
            media.setOption("probesize", "5242880")
            media.start()
            require(media.audioChannels > 0) { "The file does not contain an audio track" }
            val tags = (media.metadata.orEmpty() + media.audioMetadata.orEmpty()).mapKeys { it.key.lowercase(Locale.ROOT) }
            fun tag(vararg names: String): String? = names.firstNotNullOfOrNull { cleanTag(tags[it]) }
            val artwork = embeddedArtwork(media, fileId("$id:${attributes.size()}:${attributes.lastModifiedTime()}")) ?: sidecar(file)
            Song(
                videoId = id, title = tag("title") ?: file.fileName.toString().substringBeforeLast('.'),
                artist = tag("artist", "album_artist", "albumartist") ?: "Unknown Artist",
                thumbnailUrl = artwork, albumName = tag("album"), durationText = durationText(media.lengthInTime / 1000),
                localUri = id, localPath = file.toString(),
                localDateAddedSeconds = attributes.creationTime().toMillis() / 1000,
                localDateModifiedSeconds = attributes.lastModifiedTime().toMillis() / 1000,
                trackNumber = tag("track", "tracknumber")?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 },
                downloadFormat = media.audioCodecName?.uppercase(Locale.ROOT),
            )
        }
    }

    private fun embeddedArtwork(media: FFmpegFrameGrabber, key: String): String? = runCatching {
        val context = media.formatContext
        for (index in 0 until context.nb_streams()) {
            val stream = context.streams(index)
            if (stream.disposition() and AV_DISPOSITION_ATTACHED_PIC == 0) continue
            val picture = stream.attached_pic()
            if (picture.size() !in 1..MAX_ARTWORK_BYTES) continue
            val bytes = ByteArray(picture.size())
            picture.data().get(bytes)
            saveArtwork(bytes, key)?.let { return@runCatching it }
        }
        null
    }.getOrNull()

    private fun sidecar(file: Path): String? {
        val stem = file.fileName.toString().substringBeforeLast('.')
        for (name in listOf(stem, "cover", "Cover", "folder", "Folder", "front", "Front")) {
            for (suffix in listOf("jpg", "jpeg", "png", "webp", "JPG", "PNG")) {
                val image = file.parent.resolve("$name.$suffix")
                if (Files.isRegularFile(image) && Files.size(image) in 1..MAX_ARTWORK_BYTES.toLong()) return image.toUri().toString()
            }
        }
        return null
    }

    private suspend fun downloadArtwork(url: String?, key: String): String? {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return null
        return try {
            currentCoroutineContext().ensureActive()
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body.byteStream().use { it.readNBytes(MAX_ARTWORK_BYTES + 1) }
                if (bytes.size > MAX_ARTWORK_BYTES) null else saveArtwork(bytes, key)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    internal fun saveArtwork(bytes: ByteArray, key: String): String? {
        val extension = artworkExtension(bytes) ?: return null
        val directory = DesktopStore.cache.resolve("local-artwork")
        Files.createDirectories(directory)
        val target = directory.resolve("$key.$extension")
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            val temp = Files.createTempFile(directory, ".$key-", ".tmp")
            try { Files.write(temp, bytes); atomicMove(temp, target) }
            finally { Files.deleteIfExists(temp) }
        }
        return target.toUri().toString()
    }

    private fun readSongs(name: String): List<Song> = runCatching {
        json.decodeFromString(SongListSerializer, DesktopStore.readText(name) ?: "[]")
    }.getOrDefault(emptyList())

    private fun rememberDownloadRoot(path: Path) = synchronized(downloadRoots) {
        val roots = (downloadRoots.value + path.toString()).distinct()
        DesktopStore.writeText("download-roots.json", json.encodeToString(roots))
        downloadRoots.value = roots
    }

    private fun isOwnedDownload(path: String?): Boolean = path?.let { runCatching {
        val file = Path.of(it).toAbsolutePath().normalize()
        val ownedName = Regex("[0-9a-f]{64}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(mka|flac|mp3|m4a|opus|ogg|wav|ac3|eac3)")
        val roots = downloadRoots.value + filesDirectory.toAbsolutePath().normalize().toString()
        ownedName.matches(file.fileName.toString()) && !Files.isSymbolicLink(file) && roots.any { root ->
            val directory = Path.of(root).toAbsolutePath().normalize()
            file.parent == directory && directory.toRealPath() == directory
        }
    }.getOrDefault(false) } ?: false

    internal fun localFileExists(song: Song): Boolean = song.localPath?.let {
        runCatching { Files.isRegularFile(Path.of(it)) }.getOrDefault(false)
    } ?: false

    internal fun cleanTag(value: String?): String? = value?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) && !it.equals("unknown", ignoreCase = true)
    }
    internal fun durationText(milliseconds: Long): String? = milliseconds.takeIf { it > 0 }?.let {
        val seconds = it / 1000
        "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
    internal fun isAudioFile(path: Path): Boolean = path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT) in
        setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "aiff", "aif", "alac", "ape", "wv", "wma", "mka", "mp4", "webm", "ac3", "eac3", "ac4", "dsf", "dff")
    internal fun artworkExtension(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "png"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> null
    }
    private fun fileId(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024
}

internal fun atomicMove(source: Path, target: Path) {
    try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
    catch (_: AtomicMoveNotSupportedException) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) }
}
