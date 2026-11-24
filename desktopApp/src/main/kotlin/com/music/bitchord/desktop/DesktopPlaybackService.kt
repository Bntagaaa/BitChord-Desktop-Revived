package com.music.bitchord.desktop

import androidx.compose.runtime.mutableStateOf
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import java.awt.EventQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-owned playback session, deliberately independent of the main Compose window.
 *
 * The main Window stays composed but may be hidden in the tray or while PiP is open.
 * This service still owns the audio engine independently of window visibility and
 * supports background transport whenever UI callbacks are temporarily unavailable.
 */
internal class DesktopPlaybackService(
    private val persistence: DesktopPersistence = DesktopPersistence(),
) {
    data class UiCallbacks(
        val onEnded: () -> Unit,
        val onCrossfaded: (Song) -> Unit,
        val onPlay: () -> Unit,
        val onPause: () -> Unit,
        val onPlayPause: () -> Unit,
        val onNext: () -> Unit,
        val onPrevious: () -> Unit,
        val onSeek: (Long) -> Unit,
        val onShuffleChanged: (Boolean) -> Unit,
        val onLoopStatusChanged: (String) -> Unit,
        val onRateChanged: (Double) -> Unit,
        val onVolumeChanged: (Double) -> Unit,
        val onPartyAutoplay: () -> Unit = {},
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val selectedSong = mutableStateOf<Song?>(null)
    val liveQueue = mutableStateOf(DesktopQueue())
    val history = mutableStateOf<List<Song>>(emptyList())
    private val pendingActions = ArrayDeque<() -> Unit>()  // AWT event thread
    private var restored = false
    val position = PlaybackPosition()
    val pipPlayback = mutableStateOf(DesktopPlaybackState())
    val shuffleState = mutableStateOf(persistence.boolean("shuffle", false))
    val repeatState = mutableStateOf(persistence.string("repeat_mode", "OFF"))
    val rateState = mutableStateOf(persistence.string("playback_speed", "1.0").toFloatOrNull() ?: 1f)
    val lyricsSession = DesktopLyricsSession()
    val lyricsSettings = mutableStateOf(
        DesktopLyricsRequest(
            song = null,
            durationMs = 0L,
            enabled = persistence.boolean(DesktopLyricsClient.KEY_SYNCED_LYRICS, true),
            videoBeta = DesktopVideoBeta.enabled.value,
            prioritizeSyllables = persistence.boolean(DesktopLyricsClient.KEY_PRIORITIZE_SYLLABLES, false),
            sources = persistence.lyricsEnabledSources(),
            order = persistence.lyricsSourceOrder(),
            only = null,
        ),
    )

    @Volatile private var activeUi: UiCallbacks? = null
    private val closed = AtomicBoolean(false)

    val engine = DesktopPlaybackEngine(
        onEnded = { activeUi?.onEnded?.invoke() ?: afterEndedHeadless() },
        onCrossfaded = { song ->
            activeUi?.onCrossfaded?.invoke(song) ?: afterCrossfadeHeadless(song)
        },
    )

    private var personalQueueStash: DesktopQueue? = null
    private var personalPositionStash = 0L
    private var personalPlayingStash = false

    /** Listener-group clock/queue reconciliation is background work, not a screen effect. */
    val partySync = DesktopPartySync(
        scope = scope,
        engine = engine,
        playTrack = { shared, at ->
            val songs = DesktopQueue.adopt(liveQueue.value.songs, shared.map { it.toDesktopSong() })
            liveQueue.value = DesktopQueue(songs, at.coerceIn(songs.indices)).trimmed()
            liveQueue.value.current?.let { song ->
                selectedSong.value = song
                engine.load(song, playWhenReady = false)
            }
        },
        localQueue = { liveQueue.value.songs to liveQueue.value.index },
        applyPartyUpcoming = { upcoming ->
            liveQueue.value = liveQueue.value.withPartyUpcoming(upcoming.map { it.toDesktopSong() })
        },
        applyPartyAutoplay = { activeUi?.onPartyAutoplay?.invoke() },
        onEnteredParty = {
            if (personalQueueStash == null) {
                personalQueueStash = liveQueue.value
                personalPositionStash = engine.state.value.positionMs
                personalPlayingStash = engine.state.value.isPlaying
            }
        },
        onLeftParty = {
            personalQueueStash?.let { original ->
                liveQueue.value = original
                selectedSong.value = original.current
                original.current?.let { engine.load(it, personalPlayingStash, personalPositionStash) }
                persistQueue()
            }
            personalQueueStash = null
        },
    )

    private val restoration = scope.async(Dispatchers.Swing, start = CoroutineStart.LAZY) {
        val started = System.nanoTime()
        val restoredSession = try {
            withContext(Dispatchers.IO) {
                DesktopQueue.restored(
                    persistence.queue(),
                    persistence.string("queue_index", "0").toIntOrNull() ?: 0,
                ) to persistence.history()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DesktopTrackLog.log("playback restoration failed: ${failure.javaClass.simpleName}")
            null
        }
        if (closed.get()) return@async
        restoredSession?.let { (queue, savedHistory) ->
            liveQueue.value = queue
            history.value = savedHistory
        }
        restored = true
        partySync.start()
        DesktopLinuxAudioWatcher.ensureStarted()
        DesktopStartupDiagnostics.record(
            DesktopStartupDiagnostics.Milestone.PLAYBACK_RESTORED,
            "Queue entries: ${liveQueue.value.songs.size}, history entries: ${history.value.size}",
            durationNanos = System.nanoTime() - started,
        )
        while (!closed.get() && pendingActions.isNotEmpty()) pendingActions.removeFirst().invoke()
    }

    fun startRestoring() { restoration.start() }

    suspend fun awaitRestored() { restoration.await() }

    /** UI queue mutations must see the real saved timeline before applying or persisting edits. */
    fun deferUntilRestored(action: () -> Unit): Boolean {
        if (closed.get()) return true
        if (restored) return false
        pendingActions.addLast(action)
        startRestoring()
        return true
    }

    /** Native media callbacks can arrive from D-Bus, SMTC or the Swing tray. */
    private fun transport(action: () -> Unit) {
        if (!EventQueue.isDispatchThread()) {
            EventQueue.invokeLater { transport(action) }
        } else if (!deferUntilRestored(action)) {
            action()
        }
    }

    fun attach(callbacks: UiCallbacks) { activeUi = callbacks }
    fun detach(callbacks: UiCallbacks) {
        if (activeUi === callbacks) activeUi = null
    }

    private fun persistQueue() {
        if (DesktopListenTogether.state.value.inParty) return
        persistence.saveQueue(liveQueue.value.songs, liveQueue.value.index)
    }

    private fun adopt(song: Song, playWhenReady: Boolean = true) {
        selectedSong.value = song
        history.value = (listOf(song) + history.value.filterNot { it.videoId == song.videoId }).take(50)
        persistQueue()
        persistence.saveHistory(history.value)
        engine.load(song, playWhenReady)
        scope.launch { DesktopScrobbling.updateNowPlaying(song) }
    }

    private fun afterEndedHeadless() {
        selectedSong.value?.let(DesktopScrobbling::onPlaybackEnded)
        if (DesktopSleepTimer.afterTrack.value) {
            DesktopSleepTimer.cancel()
            return
        }
        if (persistence.string("repeat_mode", "OFF") == "ONE") {
            liveQueue.value.current?.let(::adopt)
        } else {
            nextHeadless()
        }
    }

    private fun afterCrossfadeHeadless(song: Song) {
        liveQueue.value = liveQueue.value.afterHandoffTo(song.videoId)
        selectedSong.value = song
        history.value = (listOf(song) + history.value.filterNot { it.videoId == song.videoId }).take(50)
        persistQueue()
        persistence.saveHistory(history.value)
        scope.launch { DesktopScrobbling.updateNowPlaying(song) }
        partySync.onLocalIntent()
    }

    private fun nextHeadless() {
        if (DesktopListenTogether.state.value.controlsLocked) return
        val current = liveQueue.value
        when {
            current.hasNext -> {
                liveQueue.value = current.next()
                liveQueue.value.current?.let(::adopt)
            }
            persistence.string("repeat_mode", "OFF") == "ALL" && current.songs.isNotEmpty() -> {
                liveQueue.value = current.copy(index = 0)
                liveQueue.value.current?.let(::adopt)
            }
            // AutoPlay is a UI-level network queue-builder; do not run it without UI callbacks.
            // Existing queued songs continue, and the player never spontaneously stops mid-song.
        }
    }

    private fun previousHeadless() {
        if (DesktopListenTogether.state.value.controlsLocked) return
        if (engine.state.value.positionMs > 10_000L) {
            engine.seekTo(0L)
            return
        }
        val current = liveQueue.value
        if (current.hasPrevious) {
            liveQueue.value = current.previous()
            liveQueue.value.current?.let(::adopt)
        }
    }

    fun play() = transport {
        if (DesktopListenTogether.state.value.controlsLocked) return@transport
        activeUi?.onPlay?.invoke() ?: playHeadless(toggle = false)
    }

    fun pause() = transport {
        if (DesktopListenTogether.state.value.controlsLocked) return@transport
        activeUi?.onPause?.invoke() ?: engine.pause()
    }

    fun togglePlayPause() = transport {
        if (DesktopListenTogether.state.value.controlsLocked) return@transport
        activeUi?.onPlayPause?.invoke() ?: playHeadless(toggle = true)
    }

    private fun playHeadless(toggle: Boolean) {
        if (engine.state.value.song == null) {
            liveQueue.value.current?.let { adopt(it) }
        } else if (toggle) {
            engine.togglePlayPause()
        } else {
            engine.play()
        }
    }

    fun next() = transport { activeUi?.onNext?.invoke() ?: nextHeadless() }
    fun previous() = transport { activeUi?.onPrevious?.invoke() ?: previousHeadless() }

    fun seek(positionMs: Long) = transport {
        if (DesktopListenTogether.state.value.controlsLocked) return@transport
        activeUi?.onSeek?.invoke(positionMs) ?: engine.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun shuffle(enabled: Boolean) = transport {
        val cb = activeUi
        if (cb != null) cb.onShuffleChanged(enabled)
        else {
            persistence.saveBoolean("shuffle", enabled)
            shuffleState.value = enabled
        }
    }

    fun loopStatus(status: String) {
        val cb = activeUi
        if (cb != null) cb.onLoopStatusChanged(status)
        else {
            repeatState.value = when (status) {
                "Track" -> "ONE"
                "Playlist" -> "ALL"
                else -> "OFF"
            }
            persistence.saveString("repeat_mode", repeatState.value)
        }
    }

    fun rate(value: Double) {
        val cb = activeUi
        if (cb != null) cb.onRateChanged(value)
        else {
            val rate = value.toFloat().coerceIn(0.25f, 3f)
            rateState.value = rate
            persistence.saveString("playback_speed", rate.toString())
            engine.setPlaybackSpeed(rate)
        }
    }

    fun volume(value: Double) {
        val cb = activeUi
        if (cb != null) cb.onVolumeChanged(value)
        else {
            val volume = value.toFloat().coerceIn(0f, 1f)
            persistence.saveString("volume", volume.toString())
            engine.setVolume(volume)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeUi = null
        partySync.stop()
        scope.cancel()
        engine.shutdown()
    }
}
