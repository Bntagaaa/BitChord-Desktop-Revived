package com.music.bitchord.reworked

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadTarget
import com.music.bitchord.download.DownloadedCollection
import com.music.bitchord.download.SavedCollection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The pure collection rules are taken from the original mobile download/PlaylistSync.kt. */
internal object MobilePlaylistRules {
    const val ASK_ABOVE = 50
    fun syncableBrowseId(record: SavedCollection): String? = when {
        !record.playlist || ':' in record.id || record.id.startsWith("MP") -> null
        record.id.startsWith("VL") -> record.id
        else -> "VL${record.id}"
    }
    fun newTracks(listing: List<Song>, record: SavedCollection): List<Song> {
        val known = record.videoIds.toHashSet()
        return listing.filter { it.videoId !in known }.distinctBy { it.videoId }
    }
    fun listingToRecord(listing: List<Song>, record: SavedCollection, handled: Set<String>): List<String> {
        val known = record.videoIds.toHashSet()
        return listing.map { it.videoId }.distinct().filter { it in known || it in handled }
    }
    data class Listing(val songs: List<Song>, val complete: Boolean, val cover: String? = null)
    suspend fun readWholePlaylist(
        first: suspend () -> Result<YtMusicRepository.SongPage>,
        more: suspend (String) -> Result<YtMusicRepository.SongPage>,
        pause: suspend () -> Unit = { delay(400) },
    ): Listing {
        currentCoroutineContext().ensureActive()
        val opening = first().getOrNull() ?: return Listing(emptyList(), false)
        val songs = LinkedHashMap<String, Song>()
        opening.songs.forEach { songs.putIfAbsent(it.videoId, it) }
        var next = opening.continuation
        val seen = mutableSetOf<String>()
        while (next != null && seen.add(next)) {
            currentCoroutineContext().ensureActive()
            pause()
            val page = more(next).getOrNull() ?: return Listing(songs.values.toList(), false, opening.header?.thumbnailUrl)
            val before = songs.size
            page.songs.forEach { songs.putIfAbsent(it.videoId, it) }
            if (songs.size == before) break
            next = page.continuation
        }
        return Listing(songs.values.toList(), true, opening.header?.thumbnailUrl)
    }
}

internal data class PlaylistDownloadApproval(val title: String, val count: Int)

/**
 * Desktop lifecycle for the mobile additive playlist sync. The original 12-hour
 * check / 6-hour launch catch-up runs only while this application's process lives.
 * Records name successfully handled IDs even after a listener deletes their files.
 */
internal object MobileDownloadedCollections {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkLock = Mutex()
    private val mutable = MutableStateFlow(runCatching {
        json.decodeFromString<List<SavedCollection>>(DesktopStore.readText("download-collections.json") ?: "[]")
            .distinctBy { it.id }
    }.getOrDefault(emptyList()))
    val records: StateFlow<List<SavedCollection>> = mutable.asStateFlow()
    private val pending = MutableStateFlow<Map<String, PlaylistDownloadApproval>>(emptyMap())
    val approval: StateFlow<Map<String, PlaylistDownloadApproval>> = pending.asStateFlow()
    private val working = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = working.asStateFlow()
    private val status = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = status.asStateFlow()
    private val collectionJobs = mutableMapOf<String, Job>()
    @Volatile private var started = false

    fun start() = synchronized(this) {
        if (started) return@synchronized
        started = true
        scope.launch {
            var firstLook = true
            MobileFileOptions.state.map { it.syncPlaylists }.distinctUntilChanged().collectLatest { enabled ->
                val changedSinceLaunch = !firstLook
                firstLook = false
                if (enabled) {
                    val last = DesktopStore.preferences.getLong("playlist_last_complete_check", 0)
                    if (changedSinceLaunch || System.currentTimeMillis() - last >= 6L * 60 * 60 * 1000) check(emptySet())
                    while (isActive) { delay(12L * 60 * 60 * 1000); check(emptySet()) }
                }
            }
        }
    }

    fun download(target: DownloadTarget, songs: List<Song>) {
        val listing = songs.distinctBy { it.videoId }
        if (listing.isEmpty()) return
        synchronized(this) {
            if (collectionJobs[target.id]?.isActive == true) return
            val current = mutable.value.firstOrNull { it.id == target.id }
            val initial = SavedCollection(target.id, target.title, target.subtitle, target.thumbnailUrl, target.playlist, current?.videoIds.orEmpty())
            replace(initial)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val handled = MobileFiles.downloadBatch(listing)
                    synchronized(this@MobileDownloadedCollections) {
                        val live = mutable.value.firstOrNull { it.id == target.id } ?: return@synchronized
                        val order = (listing.map { it.videoId }.filter { it in handled || it in live.videoIds } + live.videoIds).distinct()
                        replace(live.copy(videoIds = order))
                    }
                } finally { synchronized(this@MobileDownloadedCollections) { collectionJobs.remove(target.id) } }
            }
            collectionJobs[target.id] = job
            job.start()
        }
    }

    fun checkNow() { start(); scope.launch { check(emptySet()) } }
    fun approve(id: String) { scope.launch { check(setOf(id)) } }

    private suspend fun check(approved: Set<String>) = checkLock.withLock {
        if (!MobileFileOptions.state.value.syncPlaylists) return@withLock
        working.value = true
        var complete = true
        var arrived = 0
        try {
            for (record in mutable.value) {
                currentCoroutineContext().ensureActive()
                if (!MobileFileOptions.state.value.syncPlaylists) return@withLock
                val browseId = MobilePlaylistRules.syncableBrowseId(record) ?: continue
                if (synchronized(this) { collectionJobs[record.id]?.isActive == true }) continue
                val listing = MobilePlaylistRules.readWholePlaylist(
                    first = { YtMusicRepository.browseSongs(browseId) }, more = YtMusicRepository::moreSongs,
                )
                if (!listing.complete) complete = false
                val fresh = MobilePlaylistRules.newTracks(listing.songs, record)
                if (fresh.size > MobilePlaylistRules.ASK_ABOVE && record.id !in approved) {
                    pending.update { it + (record.id to PlaylistDownloadApproval(record.title, fresh.size)) }
                    continue
                }
                pending.update { it - record.id }
                if (fresh.isEmpty()) continue
                val before = MobileFiles.downloads.value.mapTo(HashSet()) { it.videoId }
                val cover = listing.cover ?: record.thumbnailUrl
                val handled = MobileFiles.downloadBatch(fresh.map { if (it.thumbnailUrl.isNullOrBlank()) it.copy(thumbnailUrl = cover) else it })
                arrived += handled.count { it !in before }
                synchronized(this) {
                    // A removed collection cannot be recreated by a completed network request.
                    val current = mutable.value.firstOrNull { it.id == record.id } ?: return@synchronized
                    replace(current.copy(videoIds = MobilePlaylistRules.listingToRecord(listing.songs, current, handled)))
                }
                delay(400)
            }
            if (complete) DesktopStore.preferences.putLong("playlist_last_complete_check", System.currentTimeMillis())
            status.value = when {
                arrived > 0 -> "$arrived new playlist tracks downloaded"
                !complete -> "Some playlists could not be read completely. They will be checked again."
                pending.value.isNotEmpty() -> "Review the larger playlist updates below"
                else -> "Downloaded playlists are up to date"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { status.value = error.message?.take(220) ?: "Could not check downloaded playlists" }
        finally { working.value = false }
    }

    fun collectionsAmong(songs: List<Song>): List<DownloadedCollection> {
        val byId = songs.associateBy { it.videoId }
        return mutable.value.mapNotNull { record ->
            val surviving = record.videoIds.mapNotNull(byId::get)
            if (surviving.isEmpty()) null else DownloadedCollection(record.id, record.title, record.subtitle, record.thumbnailUrl, record.playlist, surviving)
        }
    }
    fun forget(id: String) = synchronized(this) {
        collectionJobs.remove(id)?.cancel()
        val next = mutable.value.filterNot { it.id == id }
        DesktopStore.writeText("download-collections.json", json.encodeToString(next))
        mutable.value = next
        pending.update { it - id }
    }
    private fun replace(record: SavedCollection) {
        val next = mutable.value.filterNot { it.id == record.id } + record
        DesktopStore.writeText("download-collections.json", json.encodeToString(next))
        mutable.value = next
    }
    fun close() { scope.cancel() }
}
