package com.music.bitchord.desktop

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.prefs.Preferences

/**
 * A playlist or album asked for as a whole — the record that makes a collection,
 * not just its songs, exist in Downloads. Its [songs] are the page's listing at
 * the moment of the ask, in the order the page listed them (the order to play
 * them in); as files land, each song's copy in the snapshot gains its
 * `localUri`/`localPath`, so the record can play offline by itself.
 */
data class DesktopPlaylistDownload(
    /** The browse id the download was asked for — a `VL…` playlist, an `MPREb…`/`OLAK5uy…` album, a `spotify:playlist:…`. */
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    /** Billing the page carries — playlists and albums label their rows differently in Downloads. */
    val playlist: Boolean,
    val songs: List<Song>,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * The store behind playlist-level Downloads, over the same Java-Preferences
 * ground every other desktop store stands on — no database, chunked lines,
 * the songs encoded exactly as the library's own download records are.
 *
 * The per-song files stay owned by the download queue and the library's
 * record list; this store only *claims* them, so a song downloaded by two
 * playlists is one file that two records hold, and the rules about when a
 * file may be deleted can stay pure (see [songsUnclaimedBy]).
 */
object DesktopPlaylistDownloads {

    private val preferences = Preferences.userRoot().node("com.music.bitchord.desktop")
    private val lock = Any()

    /** Everything the store keeps, in the order it was asked for. */
    private const val KEY_RECORDS = "playlist_downloads"

    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<DesktopPlaylistDownload>> = _records.asStateFlow()

    private fun load(): List<DesktopPlaylistDownload> = synchronized(lock) { loadRecords() }

    private fun loadRecords(): List<DesktopPlaylistDownload> =
        DesktopPreferenceChunks.read(preferences, KEY_RECORDS).orEmpty()
            .split('\n')
            .filter(String::isNotBlank)
            .mapNotNull(::decodeRecord)

    private fun save(records: List<DesktopPlaylistDownload>) {
        synchronized(lock) {
            DesktopPreferenceChunks.write(
                preferences,
                KEY_RECORDS,
                records.joinToString("\n") { it.toPreferenceLine() },
            )
        }
        _records.value = records
    }

    /** The record for a page's browse id, when the page has been asked for as a whole. */
    fun recordOf(browseId: String): DesktopPlaylistDownload? =
        _records.value.firstOrNull { it.id == browseId }

    /**
     * The page was asked for as a whole: remember it (merging into the record
     * it may already have, the fresh listing leading and the old snapshot's
     * stragglers kept — a re-download refreshes the order without abandoning
     * what it once held), and answer the record it landed in.
     *
     * [downloaded] is the library's current download records, used to hydrate
     * the snapshot's copies that are already on disk — a collection whose songs
     * were saved one by one is still, in its own way, already partly offline.
     */
    fun remember(collection: DesktopCollection, downloaded: List<Song>): DesktopPlaylistDownload {
        val now = System.currentTimeMillis()
        val record: DesktopPlaylistDownload = synchronized(lock) {
            val existing = _records.value.firstOrNull { it.id == collection.browseId }
            val snapshot = mergedSnapshot(existing?.songs.orEmpty(), collection.songs)
                .map { song -> downloaded.firstOrNull { it.videoId == song.videoId } ?: song }
            val merged = DesktopPlaylistDownload(
                id = collection.browseId,
                title = collection.title,
                subtitle = collection.subtitle,
                thumbnailUrl = collection.thumbnailUrl,
                playlist = collection.type == BrowseType.PLAYLIST,
                songs = snapshot,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            save(_records.value.filterNot { it.id == merged.id } + merged)
            merged
        }
        return record
    }

    /**
     * A file landed for [saved]'s id: every record holding that song takes the
     * saved copy, so its snapshot can play offline without asking the library.
     */
    fun hydrate(saved: Song) {
        synchronized(lock) {
            val records = _records.value
            var touched = false
            val updated = records.map { record ->
                if (record.songs.none { it.videoId == saved.videoId }) {
                    record
                } else {
                    touched = true
                    record.copy(
                        songs = record.songs.map { song ->
                            if (song.videoId == saved.videoId) saved else song
                        },
                    )
                }
            }
            if (touched) save(updated)
        }
    }

    /**
     * The record is thrown away. The answer is the snapshot's songs no other
     * record claims — the caller deletes their files and library records; a
     * song two playlists share, or one saved on its own, stays.
     */
    fun forget(id: String): List<Song> {
        synchronized(lock) {
            val record = _records.value.firstOrNull { it.id == id }
                ?: return emptyList()
            val rest = _records.value.filterNot { it.id == id }
            save(rest)
            return songsUnclaimedBy(record.songs, rest)
        }
    }
}

/**
 * The refreshed listing leads and the old snapshot's stragglers follow: a
 * re-download re-orders the record to how the page lists them now, and keeps
 * the songs it once held that the page has let go — they were downloaded and
 * deserve their place in the record until it is thrown away.
 */
internal fun mergedSnapshot(existing: List<Song>, fresh: List<Song>): List<Song> {
    val freshIds = fresh.mapTo(HashSet(), Song::videoId)
    return fresh + existing.filter { it.videoId !in freshIds }
}

/**
 * The songs of [snapshot] not yet on disk and not already asked for. Rows the
 * page carried that cannot be downloaded (an unresolved Spotify track) are the
 * page's own filtering to keep out of the ask — this only reads what it is
 * given, and never asks twice for a song the queue is already carrying.
 */
internal fun tracksMissingOffline(
    snapshot: List<Song>,
    downloadedIds: Set<String>,
    activeIds: Set<String>,
): List<Song> = snapshot.filter { song ->
    song.localUri == null && song.localPath == null &&
        song.videoId !in downloadedIds && song.videoId !in activeIds
}

/** The snapshot's songs no other record claims — theirs to delete when the record goes. */
internal fun songsUnclaimedBy(
    snapshot: List<Song>,
    otherRecords: List<DesktopPlaylistDownload>,
): List<Song> {
    val claimed = otherRecords.flatMapTo(HashSet()) { record -> record.songs.map(Song::videoId) }
    return snapshot.filter { it.videoId !in claimed }
}

/** The record back as a page — the offline copy of the collection it was asked for. */
internal fun DesktopPlaylistDownload.toCollection(): DesktopCollection = DesktopCollection(
    browseId = id,
    title = title,
    subtitle = subtitle,
    thumbnailUrl = thumbnailUrl,
    type = if (playlist) BrowseType.PLAYLIST else BrowseType.ALBUM,
    songs = songs,
)

/** The record as a loading placeholder for [DesktopApp.openCollection]. */
internal fun DesktopPlaylistDownload.toBrowseItem(): BrowseItem = BrowseItem(
    browseId = id,
    title = title,
    subtitle = subtitle,
    thumbnailUrl = thumbnailUrl,
    type = if (playlist) BrowseType.PLAYLIST else BrowseType.ALBUM,
)

/** How far a record has come — the arithmetic the Downloads row and the page chip agree on. */
data class DesktopPlaylistDownloadProgress(
    val total: Int,
    val done: Int,
    val active: Int,
) {
    /** Nothing left to ask for: every song the record holds is on disk. */
    val complete: Boolean get() = done >= total
    /** Asked for and not finished — the row shows its progress, not a still state. */
    val downloading: Boolean get() = active > 0 && !complete
}

internal fun offlineProgress(
    snapshot: List<Song>,
    downloadedIds: Set<String>,
    activeIds: Set<String>,
): DesktopPlaylistDownloadProgress {
    val done = snapshot.count { song ->
        song.localUri != null || song.localPath != null || song.videoId in downloadedIds
    }
    val active = snapshot.count { it.videoId in activeIds && it.localUri == null && it.localPath == null }
    return DesktopPlaylistDownloadProgress(
        total = snapshot.size,
        done = done,
        active = active,
    )
}

internal fun DesktopPlaylistDownload.toPreferenceLine(): String = listOf(
    encode(id),
    encode(title),
    encode(subtitle),
    encode(thumbnailUrl.orEmpty()),
    encode(playlist.toString()),
    encode(createdAt.toString()),
    encode(updatedAt.toString()),
    songs.joinToString(";") { song -> encodeSongLine(song) },
).joinToString("#")

internal fun decodeRecord(value: String): DesktopPlaylistDownload? {
    // Only the seven header fields are base64; the eighth is the songs string itself —
    // its song lines are base64 joined by ';' and '|', and '#' never appears in either.
    val parts = value.split("#")
    if (parts.size < 8) return null
    val header = parts.take(7).map(::decode)
    if (header[0].isBlank()) return null
    val songs = parts[7].split(";").filter(String::isNotBlank).mapNotNull(::decodeSongLine)
    return DesktopPlaylistDownload(
        id = header[0],
        title = header[1],
        subtitle = header[2],
        thumbnailUrl = header[3].ifBlank { null },
        playlist = header[4].toBooleanStrictOrNull() ?: true,
        createdAt = header[5].toLongOrNull() ?: 0L,
        updatedAt = header[6].toLongOrNull() ?: 0L,
        songs = songs,
    )
}

private fun encodeSongLine(song: Song): String = listOf(
    song.videoId,
    song.title,
    song.artist,
    song.thumbnailUrl.orEmpty(),
    song.durationText.orEmpty(),
    song.artistId.orEmpty(),
    song.albumId.orEmpty(),
    song.albumName.orEmpty(),
    song.localUri.orEmpty(),
    song.localPath.orEmpty(),
    song.isVideo.toString(),
    song.isVideoOrigin.toString(),
    song.setVideoId.orEmpty(),
    song.fromAutoplay.toString(),
    song.localDateAddedSeconds?.toString().orEmpty(),
    song.localDateModifiedSeconds?.toString().orEmpty(),
    song.sourceQuality.orEmpty(),
    song.isExplicit?.toString().orEmpty(),
).joinToString("|", transform = ::encode)

private fun decodeSongLine(value: String): Song? {
    val fields = value.split("|").map(::decode)
    if (fields.size < 10 || fields[0].isBlank()) return null
    val isVideo = fields.getOrNull(10)?.toBooleanStrictOrNull() ?: false
    return Song(
        videoId = fields[0],
        title = fields[1],
        artist = fields[2],
        thumbnailUrl = fields[3].ifBlank { null },
        durationText = fields[4].ifBlank { null },
        artistId = fields[5].ifBlank { null },
        albumId = fields[6].ifBlank { null },
        albumName = fields[7].ifBlank { null },
        localUri = fields[8].ifBlank { null },
        localPath = fields[9].ifBlank { null },
        isVideo = isVideo,
        isVideoOrigin = fields.getOrNull(11)?.toBooleanStrictOrNull() ?: isVideo,
        setVideoId = fields.getOrNull(12)?.ifBlank { null },
        fromAutoplay = fields.getOrNull(13)?.toBooleanStrictOrNull() ?: false,
        localDateAddedSeconds = fields.getOrNull(14)?.toLongOrNull(),
        localDateModifiedSeconds = fields.getOrNull(15)?.toLongOrNull(),
        sourceQuality = fields.getOrNull(16)?.ifBlank { null },
        isExplicit = fields.getOrNull(17)?.toBooleanStrictOrNull(),
    )
}

private fun encode(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decode(value: String): String =
    runCatching { String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8) }.getOrDefault("")
