package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow

/** Picture playback is opt-in; video identity and widescreen thumbnails are independent. */
internal object DesktopVideoBeta {
    private const val KEY = "music_video_beta"
    val enabled = MutableStateFlow(DesktopPersistence().boolean(KEY, false))
    fun setEnabled(value: Boolean) {
        DesktopPersistence().saveBoolean(KEY, value)
        enabled.value = value
        if (!value && DesktopMusicVideo.clock.value.song?.isVideo == true) DesktopWindowMode.exit()
    }
}
