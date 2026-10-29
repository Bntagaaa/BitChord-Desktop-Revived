package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow

/** Window visibility gates picture work, independently of audio playback and retained sessions. */
internal object DesktopRenderVisibility {
    val visible = MutableStateFlow(true)
}
