package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow

/** Opt-in source ordering; the existing resolver and audio engine remain the only playback path. */
internal object DesktopDirectSource {
    private const val KEY = "direct_source_stream"
    val enabled = MutableStateFlow(DesktopPersistence().boolean(KEY, false))
    fun setEnabled(value: Boolean) { DesktopPersistence().saveBoolean(KEY, value); enabled.value = value }
    fun active(song: Song): Boolean = enabled.value && !DesktopListenTogether.state.value.inParty &&
        DesktopSearchClient.isVideoId(song.videoId) &&
        DesktopPersistence().sourceConfigs().any { it.kind == DesktopSourceKind.YOUTUBE && it.enabled } &&
        DesktopMusicSources.mayServeFromAddon(song)
}
