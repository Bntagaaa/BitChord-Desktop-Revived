package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song

internal fun mergeVerifiedDownloads(current: List<Song>, snapshot: List<Song>, verified: List<Song>): List<Song> {
    val missing = snapshot.toSet() - verified.toSet()
    return current.filterNot { it in missing }
}
