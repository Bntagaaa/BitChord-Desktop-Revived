package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.ShelfItem

internal fun ShelfItem.toSong(): Song {
    val parts = subtitle.split(" • ", " · ", " | ").filter(String::isNotBlank)
    // Android's InnertubeParser uses the first non-type/non-tally segment.
    val artist = parts.firstOrNull {
        !it.matches(SHELF_DURATION) &&
            it.lowercase() !in SHELF_TYPE_WORDS &&
            !it.matches(SHELF_TALLY)
    } ?: "Unknown Artist"
    return Song(
        videoId = videoId.orEmpty(),
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        isVideo = isVideo,
        durationText = parts.firstOrNull { it.matches(SHELF_DURATION) },
    )
}

private val SHELF_DURATION = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")
private val SHELF_TALLY = Regex("[0-9,.]+\\s*(?:views?|likes?|songs?)", RegexOption.IGNORE_CASE)
private val SHELF_TYPE_WORDS = setOf("song", "video", "album", "ep", "single", "playlist", "mix")

