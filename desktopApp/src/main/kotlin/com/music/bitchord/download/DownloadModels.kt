package com.music.bitchord.download

import com.music.bitchord.data.model.Song

// Original mobile v1.9.1 Downloads.kt collection records, without the Android service.
data class DownloadTarget(
    /**
     * What this release is filed under: its browse id where it has one, so the
     * same album downloaded twice is one entry rather than two.
     */
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    /** Playlists and albums are grouped alike but not billed alike. */
    val playlist: Boolean = false,
)

/**
 * A release the record says was downloaded whole, as it is written down.
 *
 * Only the tracks' ids, not the tracks: a [Song] is a wide row full of things
 * that go stale — like state, autoplay provenance, a resolved local path — and
 * a second copy of one per release is a second copy to keep in step. The songs
 * are looked up out of what the Downloads page already read off disk instead;
 * see [Downloads.collectionsAmong].
 */
@kotlinx.serialization.Serializable
data class SavedCollection(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val playlist: Boolean = false,
    /** In the order the page listed them, which is the order to play them in. */
    val videoIds: List<String> = emptyList(),
)

/** A [SavedCollection] with its surviving tracks attached, ready to draw. */
data class DownloadedCollection(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val playlist: Boolean,
    val songs: List<Song>,
)
