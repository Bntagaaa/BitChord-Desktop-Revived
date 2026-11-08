package com.music.bitchord.reworked

import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song

/** Original Android PlaybackService ShelfItem conversion, with the mobile video flag retained. */
internal fun ShelfItem.toSong(): Song? {
    val id = videoId ?: return null
    return Song(videoId = id, title = title, artist = InnertubeParser.artistFromSubtitle(subtitle),
        thumbnailUrl = thumbnailUrl, isVideo = isVideo)
}
