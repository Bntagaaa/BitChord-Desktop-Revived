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
        val opening = first().getOrElse { if (it is CancellationException) throw it else return Listing(emptyList(), false) }
        val songs = LinkedHashMap<String, Song>()
        opening.songs.forEach { songs.putIfAbsent(it.videoId, it) }
        var next = opening.continuation
        val seen = mutableSetOf<String>()
        while (next != null && seen.add(next)) {
            currentCoroutineContext().ensureActive()
            pause()
            val page = more(next).getOrElse { if (it is CancellationException) throw it else return Listing(songs.values.toList(), false, opening.header?.thumbnailUrl) }
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
    // Keep host-specific account ownership separate from the unchanged mobile collection DTO.
    private val owners = runCatching {
        json.decodeFromString<Map<String, String>>(DesktopStore.readText("download-collection-owners.json") ?: "{}")
    }.getOrDefault(emptyMap()).toMutableMap()
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
        if (OfflineSmoke.active) return@synchronized
        scope.launch {
            var firstLook = true
            combine(MobileFileOptions.state.map { it.syncPlaylists }.distinctUntilChanged(),
                MobileYouTubeAccounts.state.map { it.revision }.distinctUntilChanged()) { enabled, revision -> enabled to revision }
                .collectLatest { (enabled, revision) ->
                val changedSinceLaunch = !firstLook
                firstLook = false
                pending.value = emptyMap()
                if (enabled) {
                    val listener = MobileYouTubeAccounts.state.value
                    if (listener.revision != revision) return@collectLatest
                    val last = DesktopStore.preferences.getLong(checkpoint(listener), 0)
                    if (changedSinceLaunch || System.currentTimeMillis() - last >= 6L * 60 * 60 * 1000) check(emptySet(), listener)
                    while (isActive) { delay(12L * 60 * 60 * 1000); check(emptySet(), listener) }
                }
            }
        }
    }

    fun download(target: DownloadTarget, songs: List<Song>) {
        if (OfflineSmoke.active) { status.value = "Collection downloads are disabled in the offline smoke test"; return }
        val listener = MobileYouTubeAccounts.state.value
        val listing = songs.distinctBy { it.videoId }
        if (listing.isEmpty()) return
        synchronized(this) {
            if (collectionJobs[target.id]?.isActive == true) return
            val current = mutable.value.firstOrNull { it.id == target.id }
            val initial = SavedCollection(target.id, target.title, target.subtitle, target.thumbnailUrl, target.playlist, current?.videoIds.orEmpty())
            owners[target.id] = listener.listenerKey
            persistOwners()
            replace(initial)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val handled = MobileFiles.downloadBatch(listing, expectedListenerRevision = listener.revision)
                    requireListener(listener)
                    synchronized(this@MobileDownloadedCollections) {
                        val live = mutable.value.firstOrNull { it.id == target.id } ?: return@synchronized
                        val order = (listing.map { it.videoId }.filter { it in handled || it in live.videoIds } + live.videoIds).distinct()
                        replace(live.copy(videoIds = order))
                    }
                } finally {
                    val retiring = currentCoroutineContext()[Job]
                    synchronized(this@MobileDownloadedCollections) {
                        if (collectionJobs[target.id] === retiring) collectionJobs.remove(target.id)
                    }
                }
            }
            collectionJobs[target.id] = job
            job.start()
        }
    }

    fun checkNow() { start(); val listener = MobileYouTubeAccounts.state.value; scope.launch { check(emptySet(), listener) } }
    fun approve(id: String) { val listener = MobileYouTubeAccounts.state.value; scope.launch { check(setOf(id), listener) } }

    private suspend fun check(approved: Set<String>, listener: YouTubeAccountState) = checkLock.withLock {
        if (OfflineSmoke.active || !MobileFileOptions.state.value.syncPlaylists) return@withLock
        requireListener(listener)
        working.value = true
        var complete = true
        var arrived = 0
        var unowned = false
        var anotherProfile = false
        try {
            for (record in mutable.value) {
                currentCoroutineContext().ensureActive()
                requireListener(listener)
                if (!MobileFileOptions.state.value.syncPlaylists) return@withLock
                val browseId = MobilePlaylistRules.syncableBrowseId(record) ?: continue
                val owner = synchronized(this) { owners[record.id] }
                if (owner != listener.listenerKey) {
                    if (owner == null) unowned = true else anotherProfile = true
                    continue
                }
                if (synchronized(this) { collectionJobs[record.id]?.isActive == true }) continue
                val listing = MobilePlaylistRules.readWholePlaylist(
                    first = { MobileYouTubeAccounts.withListener(listener.revision) { YtMusicRepository.browseSongs(browseId) } },
                    more = { token -> MobileYouTubeAccounts.withListener(listener.revision) { YtMusicRepository.moreSongs(token) } },
                )
                requireListener(listener)
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
                val handled = MobileFiles.downloadBatch(fresh.map { if (it.thumbnailUrl.isNullOrBlank()) it.copy(thumbnailUrl = cover) else it },
                    expectedListenerRevision = listener.revision)
                requireListener(listener)
                arrived += handled.count { it !in before }
                synchronized(this) {
                    // A removed collection cannot be recreated by a completed network request.
                    val current = mutable.value.firstOrNull { it.id == record.id } ?: return@synchronized
                    replace(current.copy(videoIds = MobilePlaylistRules.listingToRecord(listing.songs, current, handled)))
                }
                delay(400)
            }
            requireListener(listener)
            if (complete) DesktopStore.preferences.putLong(checkpoint(listener), System.currentTimeMillis())
            status.value = when {
                arrived > 0 -> "$arrived new playlist tracks downloaded"
                unowned -> "Download a saved collection again to associate it with the active YouTube profile before syncing"
                anotherProfile -> "Playlists saved by another YouTube profile are skipped. Switch to their profile to sync them."
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
        owners.remove(id)
        persistOwners()
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
    private fun checkpoint(listener: YouTubeAccountState) = "playlist_last_complete_check.${listener.listenerKey}"
    private fun requireListener(listener: YouTubeAccountState) {
        if (listener.revision != MobileYouTubeAccounts.state.value.revision) throw CancellationException("The YouTube profile changed during the collection request")
    }
    /** All accesses run under this object's monitor. No cookies or raw account IDs are stored here. */
    private fun persistOwners() = DesktopStore.writeText("download-collection-owners.json", json.encodeToString(owners.toMap()))
    fun close() { scope.cancel() }
}
