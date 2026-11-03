package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.awt.EventQueue
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class DesktopMacMediaPublication(
    val song: Song?, val playing: Boolean, val positionMs: Long, val durationMs: Long,
    val rate: Double, val atNanos: Long,
)

/** Let MediaPlayer interpolate ordinary progress; republish transport changes and seeks. */
internal fun needsMacMediaPublication(previous: DesktopMacMediaPublication?, state: DesktopPlaybackState,
    rate: Double, nowNanos: Long): Boolean {
    if (previous == null || previous.song != state.song || previous.playing != state.isPlaying ||
        previous.durationMs != state.durationMs || previous.rate != rate) return true
    val elapsed = if (previous.playing) (nowNanos - previous.atNanos) / 1_000_000.0 * previous.rate else 0.0
    return abs(state.positionMs - (previous.positionMs + elapsed)) > 1_000
}

/** MediaPlayer remote commands; ordinary playback remains available if this bridge fails. */
internal object DesktopMacMedia {
    private val available by lazy {
        DesktopPlatform.isMac && runCatching { DesktopAnalysisRuntime.loadNative("bitchord_macos") }
            .onFailure { DesktopTrackLog.log("macOS system media integration unavailable: ${it.message}") }.isSuccess
    }
    @Volatile private var controller: DesktopWindowsMedia.Controller? = null
    @Volatile private var toggle: (() -> Unit)? = null
    private var started = false
    private var updates: CoroutineScope? = null
    private var playback: StateFlow<DesktopPlaybackState>? = null
    private var playbackRate = 1.0
    private var published: DesktopMacMediaPublication? = null

    @Synchronized fun start(value: DesktopWindowsMedia.Controller, state: StateFlow<DesktopPlaybackState>,
        onToggle: () -> Unit) {
        if (!DesktopPlatform.isMac) return
        controller = value
        toggle = onToggle
        if (available && !started) started = nativeStart()
        if (!started) return
        updates?.cancel()
        playback = state
        published = null
        // Independent of the visibility-paused main-window composition. One subscription, no polling.
        updates = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope ->
            scope.launch { state.collect { publish(it) } }
        }
    }
    @Synchronized fun stop() {
        updates?.cancel()
        updates = null
        playback = null
        published = null
        if (started) nativeStop()
        started = false
        controller = null
        toggle = null
    }
    @Synchronized fun setRate(value: Float) {
        if (!DesktopPlatform.isMac) return
        playbackRate = value.toDouble()
        playback?.value?.let(::publish)
    }
    @Synchronized private fun publish(state: DesktopPlaybackState) {
        if (!started) return
        val now = System.nanoTime()
        val rate = if (state.isPlaying && !state.awaitingAudio) playbackRate else 0.0
        if (!needsMacMediaPublication(published, state, rate, now)) return
        nativeUpdate(state.song?.title.orEmpty(), state.song?.artist.orEmpty(), state.isPlaying,
            state.positionMs, state.durationMs, rate)
        published = DesktopMacMediaPublication(state.song, state.isPlaying, state.positionMs, state.durationMs, rate, now)
    }
    @JvmStatic fun onButton(button: Int) {
        if (button == 5 || button == 6) { DesktopSystemTheme.updateMacDark(button == 5); return }
        if (button == 4) { DesktopAudioDevices.systemChanged(); return }
        EventQueue.invokeLater {
            controller?.let { target ->
                when (button) {
                    0 -> target.onPlay()
                    1 -> target.onPause()
                    2 -> target.onNext()
                    3 -> target.onPrevious()
                    7 -> toggle?.invoke()
                }
            }
        }
    }
    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeUpdate(title: String, artist: String, playing: Boolean, position: Long, duration: Long, rate: Double)
}
