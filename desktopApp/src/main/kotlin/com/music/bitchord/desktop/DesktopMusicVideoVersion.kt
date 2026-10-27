package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song

/**
 * Keep the identity and queue metadata of the current song while changing which
 * YouTube recording supplies its media. This mirrors upstream's version swap:
 * audio and video are two renditions of one queue entry, not a Next action.
 */
internal fun Song.withMusicVersion(candidate: Song, toVideo: Boolean): Song? {
    if (candidate.videoId.isBlank() || candidate.videoId == videoId) return null
    return copy(
        videoId = candidate.videoId,
        isVideo = toVideo,
        isVideoOrigin = if (toVideo) false else isVideoOrigin,
        // The video cut can be longer than the catalogue audio recording.
        durationText = candidate.durationText ?: durationText,
        thumbnailUrl = candidate.thumbnailUrl ?: thumbnailUrl,
    )
}
