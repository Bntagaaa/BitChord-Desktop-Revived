package com.music.bitchord.reworked

import com.music.bitchord.data.jiosaavn.JioSaavnService
import com.music.bitchord.data.jiosaavn.prioritizeExplicit
import com.music.bitchord.data.sources.addon.AddonStream
import com.music.bitchord.data.sources.addon.AddonTrack

/** The original mobile JioSaavnSource mapping, hosted without Android SourceRegistry. */
internal object MobileJioSaavn {
    val config = MobileSourceConfig(id = "builtin-jiosaavn", baseUrl = "https://www.jiosaavn.com", name = "JioSaavn", kind = "jiosaavn")

    suspend fun search(query: String): List<AddonTrack> = prioritizeExplicit(JioSaavnService.searchSongs(query)).map { song ->
        AddonTrack(id = song.id, title = song.title,
            artist = song.moreInfo.artistMap.primaryArtists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" },
            album = song.moreInfo.album, duration = song.moreInfo.duration.toDoubleOrNull(),
            artworkURL = song.image.replace(Regex("150x150|50x50"), "500x500").replace(Regex("^http://"), "https://"),
            format = "aac", audioQuality = "HIGH", isExplicit = song.isExplicit)
    }

    suspend fun stream(id: String): AddonStream {
        val audio = JioSaavnService.getStreamUrl(id) ?: error("JioSaavn does not have a stream for this track")
        check(audio.kbps == null || audio.kbps > 96) { "JioSaavn only offered a low bitrate rendition" }
        return AddonStream(url = audio.url, format = "aac", bitrate = audio.kbps?.times(1000.0), quality = audio.kbps?.let { "$it kbps" }.orEmpty())
    }
}
