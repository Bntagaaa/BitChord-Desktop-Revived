package com.music.bitchord.reworked

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** The original mobile MusicOnlyPlayback policy before a desktop decoder opens any bytes. */
internal object MobilePlaybackPolicy {
    val preferMusicOnly = MutableStateFlow(DesktopStore.preferences.getBoolean("prefer_music_only", false))
    fun setPreferMusicOnly(value: Boolean) { preferMusicOnly.value = value; DesktopStore.preferences.putBoolean("prefer_music_only", value) }
    suspend fun prepare(song: Song): Song {
        if (!preferMusicOnly.value || !song.isVideo || song.localUri != null || ListenTogether.state.value.inParty) return song
        val audio = try { withTimeoutOrNull(15_000) { YtMusicRepository.resolveAudio(song) } ?: song }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { song }
        return audio.copy(isVideoOrigin = audio.isVideo, queueTier = song.queueTier, queueEntryId = song.queueEntryId,
            radioName = song.radioName, playbackSource = song.playbackSource, playbackSourceType = song.playbackSourceType, playbackSourceId = song.playbackSourceId)
    }
}
