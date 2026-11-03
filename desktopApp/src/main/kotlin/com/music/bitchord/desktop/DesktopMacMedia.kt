package com.music.bitchord.desktop

import java.awt.EventQueue

/** MediaPlayer remote commands; ordinary playback remains available if this bridge fails. */
internal object DesktopMacMedia {
    private val available by lazy {
        DesktopPlatform.isMac && runCatching { DesktopAnalysisRuntime.loadNative("bitchord_macos") }
            .onFailure { DesktopTrackLog.log("macOS system media integration unavailable: ${it.message}") }.isSuccess
    }
    @Volatile private var controller: DesktopWindowsMedia.Controller? = null
    private var started = false

    fun start(value: DesktopWindowsMedia.Controller) {
        if (!DesktopPlatform.isMac) return
        controller = value
        if (available && !started) started = nativeStart()
    }
    fun stop() {
        if (started) nativeStop()
        started = false
        controller = null
    }
    fun publish(state: DesktopPlaybackState) {
        if (!started) return
        nativeUpdate(state.song?.title.orEmpty(), state.song?.artist.orEmpty(), state.isPlaying,
            state.positionMs, state.durationMs)
    }
    @JvmStatic fun onButton(button: Int) {
        if (button == 4) { DesktopAudioDevices.systemChanged(); return }
        EventQueue.invokeLater {
            controller?.let { target ->
                when (button) {
                    0 -> target.onPlay()
                    1 -> target.onPause()
                    2 -> target.onNext()
                    3 -> target.onPrevious()
                }
            }
        }
    }
    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeUpdate(title: String, artist: String, playing: Boolean, position: Long, duration: Long)
}
