package com.music.bitchord.reworked

import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.planTransition
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/** Resolved by the mobile source policy; the platform layer only decodes these bytes. */
data class PlayableStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val format: String? = null,
    val boundedRanges: Boolean = false,
)

data class DesktopOutputDevice(val id: Int, val name: String, val description: String)

/** Container/codec measurements, separate from source-provider marketing labels. */
data class DecodedAudioInfo(val codec: String, val sampleRate: Int, val channels: Int,
    val bitDepth: Int?, val bitrate: Long?, val lossless: Boolean)

sealed interface PlaybackCommand {
    data class PlaySongs(val songs: List<Song>, val index: Int) : PlaybackCommand
    data object Pause : PlaybackCommand
    data object Resume : PlaybackCommand
    data object Next : PlaybackCommand
    data object Previous : PlaybackCommand
    data class Seek(val ms: Long) : PlaybackCommand
    data class Add(val song: Song, val playNext: Boolean) : PlaybackCommand
    data class Jump(val index: Int) : PlaybackCommand
    data class Remove(val index: Int) : PlaybackCommand
    data class Move(val from: Int, val to: Int) : PlaybackCommand
    data object Clear : PlaybackCommand
    data object ToggleShuffle : PlaybackCommand
    data object CycleRepeat : PlaybackCommand
    data class SetSpeed(val speed: Float) : PlaybackCommand
}

data class PlaybackState(
    val song: Song? = null,
    val queue: List<Song> = emptyList(),
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = 1f,
    val speed: Float = 1f,
    val shuffle: Boolean = false,
    val repeat: Int = 0,
    val error: String? = null,
    val format: String? = null,
    val audioInfo: DecodedAudioInfo? = null,
    val crossfadeSeconds: Int = 0,
    val automix: Boolean = false,
    val advancedAutomix: Boolean = false,
    val skipSilence: Boolean = false,
    val transition: String? = null,
    val transitionProgress: Float = 0f,
    val outputDeviceId: Int? = null,
    val mixBars: Int = 0,
    val sleepDeadlineNanos: Long? = null,
    val sleepAfterTrack: Boolean = false,
    /** Dated at the audio-device clock, for video and lyric synchronization. */
    val sampledAtNanos: Long = 0,
    /** Explicit seeks and track changes, independent of the ordinary clock ticks. */
    val discontinuity: Long = 0,
)

/** Current mobile resolver and local-file policy, shared by the default and add-on pipelines. */
suspend fun resolveMobileStream(song: Song): PlayableStream {
    song.localPath?.takeIf(String::isNotBlank)?.let { return PlayableStream(it) }
    song.localUri?.takeIf(String::isNotBlank)?.let { uri ->
        return PlayableStream(if (uri.startsWith("file:", true)) Path.of(URI(uri)).toString() else uri)
    }
    val url = StreamResolver.resolve(song.videoId)
    return PlayableStream(
        url = url,
        headers = StreamResolver.mediaHeadersFor(url),
        boundedRanges = URI(url).rawQuery?.split('&')?.any { it.startsWith("clen=") } == true,
    )
}

/**
 * A JVM playback adapter for the mobile service's queue and transport contract.
 * The shared/mobile resolver supplies streams; FFmpeg replaces Media3's decoder and Java Sound
 * replaces AudioTrack. Two peers overlap for mobile crossfades; obsolete resolutions cannot play.
 */
class DesktopPlayback(
    private val resolve: suspend (Song) -> PlayableStream = ::resolveMobileStream,
    private val prepareSong: suspend (Song) -> Song = { it },
    private val analysisFor: (Song) -> TrackAnalysis = { TrackAnalysis() },
    private val requestAnalysis: (Song) -> Unit = {},
    private val analysisStateFor: (Song) -> TrackAnalysisState = { if (analysisFor(it).isUsable) TrackAnalysisState.ANALYSED else TrackAnalysisState.WAITING },
    private val syncedLyrics: suspend (Song) -> List<LyricLine>? = { null },
    private val mixingAllowed: () -> Boolean = { true },
    private val outputFactory: (Int, Int, Int, Int?) -> SourceDataLine = ::openOutput,
) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioWorker = Executors.newFixedThreadPool(2) { work ->
        Thread(work, "Reworked-Playback").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _mixUi = MutableStateFlow(MixUiState())
    val mixUi: StateFlow<MixUiState> = _mixUi.asStateFlow()
    @Volatile private var mixViewOpen = false
    private var previewKey: String? = null
    private var waveJob: Job? = null
    /** A party consumes local intents here and applies authoritative replies through withRemoteCommands. */
    @Volatile var commandInterceptor: ((PlaybackCommand) -> Boolean)? = null
    @Volatile var completionInterceptor: (() -> Boolean)? = null
    private val remoteDepth = ThreadLocal.withInitial { 0 }
    private val generation = AtomicLong()
    private var resolverJob: Job? = null
    private var session: Session? = null
    private var blend: Blend? = null
    private var gapless: GaplessPending? = null
    @Volatile private var loopMix: LoopBlend? = null
    @Volatile private var loopBuild: LoopBuild? = null
    private var failedLoopEntry: String? = null
    private class LyricEvidence(val spans: List<LoopMix.Span>?)
    private val loopLyrics = ConcurrentHashMap<String, LyricEvidence>()
    private val lyricsRequested = ConcurrentHashMap.newKeySet<String>()
    private var failedBlendEntry: String? = null
    @Volatile private var closed = false
    private val heard = LinkedHashSet<String>()
    private val history = ArrayDeque<String>()

    init {
        scope.launch {
            while (isActive) {
                delay(200)
                synchronized(lock) {
                    val deadline = _state.value.sleepDeadlineNanos
                    if (deadline != null && System.nanoTime() >= deadline) {
                        _state.value = _state.value.copy(sleepDeadlineNanos = null, sleepAfterTrack = false)
                        pause()
                    }
                }
            }
        }
    }

    fun <T> withRemoteCommands(block: () -> T): T {
        remoteDepth.set(remoteDepth.get() + 1)
        return try { block() } finally { remoteDepth.set(remoteDepth.get() - 1) }
    }

    private fun intercepted(command: PlaybackCommand): Boolean = remoteDepth.get() == 0 && commandInterceptor?.invoke(command) == true
    private fun queueEntry(song: Song): Song = if (remoteDepth.get() > 0 && !song.queueEntryId.isNullOrBlank()) song else song.withQueueIdentity()

    fun playSongs(songs: List<Song>, index: Int = 0) = synchronized(lock) {
        if (intercepted(PlaybackCommand.PlaySongs(songs, index))) return@synchronized
        if (closed) return@synchronized
        if (songs.isEmpty()) return@synchronized clearQueue()
        heard.clear()
        history.clear()
        val rows = songs.map(::queueEntry)
        _state.value = _state.value.copy(queue = rows, queueIndex = -1)
        begin(rows.indices.coerce(index))
    }

    fun play(song: Song) = playSongs(listOf(song))

    /** Restore the queue without resolving or making sound until the user resumes. */
    fun restoreQueue(songs: List<Song>, index: Int, positionMs: Long) = synchronized(lock) {
        withRemoteCommands { clearQueue() }
        if (closed || songs.isEmpty()) return@synchronized
        val rows = songs.map { if (it.queueEntryId.isNullOrBlank()) it.withQueueIdentity() else it }
        val selected = index.coerceIn(rows.indices)
        val song = rows[selected]
        val duration = song.durationText.durationMillis()
        song.queueEntryId?.let(heard::add)
        _state.value = _state.value.copy(
            queue = rows, queueIndex = selected, song = song, durationMs = duration,
            positionMs = positionMs.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it },
            sampledAtNanos = System.nanoTime(),
        )
    }

    /** Apply a party's authoritative queue without reopening an unchanged selected entry. */
    fun replaceQueue(songs: List<Song>, index: Int) = synchronized(lock) {
        if (closed) return@synchronized
        if (songs.isEmpty()) return@synchronized withRemoteCommands { clearQueue() }
        val rows = songs.map { if (it.queueEntryId.isNullOrBlank()) it.withQueueIdentity() else it }
        val selected = index.coerceIn(rows.indices)
        val sameEntry = rows[selected].queueEntryId == _state.value.song?.queueEntryId
        cancelLoop()
        cancelBlend()
        _state.value = _state.value.copy(queue = rows, queueIndex = selected)
        if (sameEntry) _state.value = _state.value.copy(song = rows[selected])
        else begin(selected)
    }

    /** Explicit mobile Audio/Video selection retains this queue occurrence and transport intent. */
    fun swapToVersion(target: Song, positionMs: Long, expectedEntryId: String? = null): Boolean = synchronized(lock) {
        val current = _state.value
        val original = current.song ?: return@synchronized false
        if (closed || (expectedEntryId != null && expectedEntryId != original.queueEntryId)) return@synchronized false
        // Parties own the queue; a version change must be reconciled by their normal queue command.
        val replacement = target.copy(queueEntryId = original.queueEntryId, queueTier = original.queueTier, radioName = original.radioName)
        val rows = current.queue.map { if (it.queueEntryId == original.queueEntryId) replacement else it }
        if (intercepted(PlaybackCommand.PlaySongs(rows, current.queueIndex))) return@synchronized false
        _state.value = current.copy(queue = rows)
        begin(current.queueIndex, rememberPrevious = false, applyPolicy = false, startPosition = positionMs, playing = current.isPlaying)
        true
    }

    fun toggle() {
        if (state.value.isPlaying) pause() else resume()
    }

    fun pause() = synchronized(lock) {
        if (intercepted(PlaybackCommand.Pause)) return@synchronized
        _state.value = _state.value.copy(isPlaying = false)
        _mixUi.value = _mixUi.value.copy(blend = _mixUi.value.blend?.copy(playing = false))
        session?.line?.stop()
        blend?.outgoing?.line?.stop()
        blend?.incoming?.line?.stop()
        loopMix?.outgoing?.line?.stop()
        loopMix?.incoming?.line?.stop()
    }

    fun resume() = synchronized(lock) {
        if (intercepted(PlaybackCommand.Resume)) return@synchronized
        val current = _state.value
        if (closed || current.queue.isEmpty()) return@synchronized
        if (session == null && !current.isLoading) {
            begin(current.queueIndex.takeIf { it in current.queue.indices } ?: 0, rememberPrevious = false)
            if (current.positionMs > 0 && (current.durationMs <= 0 || current.positionMs < current.durationMs)) {
                withRemoteCommands { seek(current.positionMs) }
            }
        } else {
            _state.value = current.copy(isPlaying = true, error = null)
            _mixUi.value = _mixUi.value.copy(blend = if (current.isPlaying) _mixUi.value.blend else null)
            session?.line?.start()
            blend?.takeIf { it.started }?.let { it.outgoing.line?.start(); it.incoming?.line?.start() }
            loopMix?.let { it.outgoing.line?.start(); if (it.incomingStarted) it.incoming.line?.start() }
        }
    }

    fun next() = synchronized(lock) {
        if (!intercepted(PlaybackCommand.Next)) advance(manual = true)
    }

    fun previous() = synchronized(lock) {
        if (intercepted(PlaybackCommand.Previous)) return@synchronized
        val current = _state.value
        if (current.positionMs > 3_000) return@synchronized seek(0)
        if (current.queue.isEmpty()) return@synchronized
        val previousId = history.removeLastOrNull()
        val prior = previousId?.let { id -> current.queue.indexOfFirst { it.queueEntryId == id }.takeIf { it >= 0 } }
            ?: if (current.queueIndex > 0) current.queueIndex - 1
            else if (current.repeat == 2) current.queue.lastIndex else 0
        begin(prior, rememberPrevious = false)
    }

    fun seek(ms: Long) = synchronized(lock) {
        if (intercepted(PlaybackCommand.Seek(ms))) return@synchronized
        if (_state.value.song == null) return@synchronized
        cancelLoop()
        failedLoopEntry = null
        cancelBlend()
        val current = _state.value
        val target = ms.coerceAtLeast(0).let { if (current.durationMs > 0) it.coerceAtMost(current.durationMs) else it }
        session?.seekTo?.set(target)
        session?.line?.flush()
        _state.value = current.copy(positionMs = target, sampledAtNanos = System.nanoTime(), discontinuity = current.discontinuity + 1)
    }

    fun setVolume(volume: Float) = synchronized(lock) {
        _state.value = _state.value.copy(volume = volume.coerceIn(0f, 1f))
    }

    fun setSpeed(speed: Float) = synchronized(lock) {
        if (intercepted(PlaybackCommand.SetSpeed(speed))) return@synchronized
        cancelGapless()
        if (speed != 1f) cancelLoop()
        _state.value = _state.value.copy(speed = speed.coerceIn(0.5f, 2f))
    }

    fun setCrossfadeSeconds(seconds: Int) = synchronized(lock) {
        _state.value = _state.value.copy(crossfadeSeconds = seconds.coerceIn(0, 12))
        if (_state.value.crossfadeSeconds == 0 && !_state.value.automix) cancelBlend()
    }

    fun setAutomixEnabled(enabled: Boolean) = synchronized(lock) {
        cancelLoop()
        _state.value = _state.value.copy(automix = enabled)
        cancelBlend()
        if (!enabled) clearMixUi()
        if (enabled) _state.value.song?.let(requestAnalysis)
    }

    fun setAdvancedAutomixEnabled(enabled: Boolean) = synchronized(lock) {
        cancelLoop(); cancelBlend()
        failedLoopEntry = null
        _state.value = _state.value.copy(advancedAutomix = enabled)
    }

    fun setSkipSilence(enabled: Boolean) = synchronized(lock) {
        cancelLoop(); cancelBlend()
        _state.value = _state.value.copy(skipSilence = enabled)
    }

    fun setMixViewOpen(open: Boolean) = synchronized(lock) {
        mixViewOpen = open
        if (!open) { waveJob?.cancel(); waveJob = null }
        else {
            session?.lastPlanAt = 0
            blend?.let { publishMixPlan(it.outgoing, it.nextSong, it.plan) }
        }
    }

    /** 0 uses mobile's adaptive planner; 8/16 preserve the Revived phrase-length preference. */
    fun setMixBars(bars: Int) = synchronized(lock) {
        cancelLoop()
        cancelBlend()
        _state.value = _state.value.copy(mixBars = bars.takeIf { it == 8 || it == 16 } ?: 0)
    }

    fun setSleepMinutes(minutes: Int) = synchronized(lock) {
        cancelBlend()
        session?.gain = 1f
        _state.value = _state.value.copy(
            sleepDeadlineNanos = System.nanoTime() + minutes.coerceIn(1, 720) * 60_000_000_000L,
            sleepAfterTrack = false,
        )
    }

    fun startSleepAfterTrack() = synchronized(lock) {
        cancelLoop()
        cancelBlend()
        _state.value = _state.value.copy(sleepDeadlineNanos = null, sleepAfterTrack = true)
    }

    fun cancelSleep() = synchronized(lock) {
        _state.value = _state.value.copy(sleepDeadlineNanos = null, sleepAfterTrack = false)
        session?.gain = 1f
    }

    fun sleepRemainingMs(): Long? = state.value.sleepDeadlineNanos?.let { ((it - System.nanoTime()) / 1_000_000).coerceAtLeast(0) }

    fun outputDevices(): List<DesktopOutputDevice> = AudioSystem.getMixerInfo().mapIndexedNotNull { index, info ->
        runCatching {
            val mixer = AudioSystem.getMixer(info)
            if (mixer.sourceLineInfo.any { SourceDataLine::class.java.isAssignableFrom(it.lineClass) }) {
                DesktopOutputDevice(index, info.name, info.description)
            } else null
        }.getOrNull()
    }

    /** A route change reopens at the current timeline position and preserves pause state. */
    fun selectOutput(id: Int?) = synchronized(lock) {
        if (_state.value.outputDeviceId == id) return@synchronized
        val current = _state.value
        _state.value = current.copy(outputDeviceId = id)
        if (current.song != null) {
            begin(current.queueIndex, rememberPrevious = false, applyPolicy = false,
                startPosition = current.positionMs, playing = current.isPlaying)
        }
    }

    fun toggleShuffle() = synchronized(lock) {
        if (intercepted(PlaybackCommand.ToggleShuffle)) return@synchronized
        cancelLoop(); cancelBlend()
        heard.clear()
        _state.value.song?.queueEntryId?.let(heard::add)
        _state.value = _state.value.copy(shuffle = !_state.value.shuffle)
    }

    fun cycleRepeat() = synchronized(lock) {
        if (intercepted(PlaybackCommand.CycleRepeat)) return@synchronized
        cancelLoop(); cancelBlend()
        _state.value = _state.value.copy(repeat = (_state.value.repeat + 1) % 3)
    }

    fun addToQueue(song: Song) = synchronized(lock) {
        if (intercepted(PlaybackCommand.Add(song, false))) return@synchronized
        _state.value = _state.value.copy(queue = _state.value.queue + queueEntry(song))
        if (_state.value.queueIndex < 0) begin(0)
    }

    fun playNext(song: Song) = synchronized(lock) {
        if (intercepted(PlaybackCommand.Add(song, true))) return@synchronized
        cancelLoop(); cancelBlend()
        val current = _state.value
        val rows = current.queue.toMutableList()
        rows.add((current.queueIndex + 1).coerceIn(0, rows.size), queueEntry(song))
        _state.value = current.copy(queue = rows)
        if (current.queueIndex < 0) begin(0)
    }

    fun jump(index: Int) = synchronized(lock) {
        if (intercepted(PlaybackCommand.Jump(index))) return@synchronized
        if (index in _state.value.queue.indices) begin(index)
    }

    fun remove(index: Int) = synchronized(lock) {
        if (intercepted(PlaybackCommand.Remove(index))) return@synchronized
        val current = _state.value
        if (index !in current.queue.indices) return@synchronized
        cancelLoop()
        val rows = current.queue.toMutableList().apply { removeAt(index) }
        if (rows.isEmpty()) return@synchronized clearQueue()
        val nextIndex = when {
            index < current.queueIndex -> current.queueIndex - 1
            index == current.queueIndex -> index.coerceAtMost(rows.lastIndex)
            else -> current.queueIndex
        }
        _state.value = current.copy(queue = rows, queueIndex = nextIndex)
        if (index == current.queueIndex) begin(nextIndex, rememberPrevious = false)
    }

    fun move(from: Int, to: Int) = synchronized(lock) {
        if (intercepted(PlaybackCommand.Move(from, to))) return@synchronized
        val current = _state.value
        if (from !in current.queue.indices || to !in current.queue.indices || from == to) return@synchronized
        cancelLoop(); cancelBlend()
        val rows = current.queue.toMutableList().apply { add(to, removeAt(from)) }
        val selected = rows.indexOfFirst { it.queueEntryId == current.song?.queueEntryId }
        _state.value = current.copy(queue = rows, queueIndex = selected)
    }

    fun clearQueue() = synchronized(lock) {
        if (intercepted(PlaybackCommand.Clear)) return@synchronized
        generation.incrementAndGet()
        resolverJob?.cancel()
        cancelLoop()
        cancelBlend()
        clearMixUi()
        session?.cancel()
        session = null
        failedBlendEntry = null
        heard.clear()
        history.clear()
        val old = _state.value
        _state.value = PlaybackState(
            volume = old.volume, speed = old.speed, shuffle = old.shuffle, repeat = old.repeat,
            crossfadeSeconds = old.crossfadeSeconds, automix = old.automix,
            advancedAutomix = old.advancedAutomix,
            skipSilence = old.skipSilence,
            outputDeviceId = old.outputDeviceId,
            mixBars = old.mixBars, sleepDeadlineNanos = old.sleepDeadlineNanos, sleepAfterTrack = old.sleepAfterTrack,
        )
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        withRemoteCommands { clearQueue() }
        closed = true
        scope.cancel()
        audioWorker.close()
    }

    /** Called with [lock] held. Changing generation retires both the resolver and the decoder. */
    private fun begin(index: Int, rememberPrevious: Boolean = true, applyPolicy: Boolean = true, startPosition: Long = 0, playing: Boolean = true) {
        if (closed) return
        val old = _state.value
        val song = old.queue.getOrNull(index) ?: return
        failedBlendEntry = null
        failedLoopEntry = null
        if (rememberPrevious && old.song?.queueEntryId != song.queueEntryId) old.song?.queueEntryId?.let(history::addLast)
        song.queueEntryId?.let(heard::add)
        val request = generation.incrementAndGet()
        resolverJob?.cancel()
        cancelLoop()
        cancelBlend()
        clearMixUi()
        session?.cancel()
        session = null
        _state.value = old.copy(
            song = song,
            queueIndex = index,
            isPlaying = playing,
            isLoading = true,
            positionMs = startPosition.coerceAtLeast(0),
            durationMs = song.durationText.durationMillis(),
            sampledAtNanos = System.nanoTime(),
            discontinuity = old.discontinuity + 1,
            error = null,
            format = null,
            audioInfo = null,
            transition = null,
            transitionProgress = 0f,
        )
        if (old.automix) {
            requestAnalysis(song)
            peekNext()?.let { old.queue.getOrNull(it) }?.let(requestAnalysis)
        }
        resolverJob = scope.launch {
            try {
                val prepared = (if (applyPolicy) prepareSong(song) else song).copy(queueEntryId = song.queueEntryId, queueTier = song.queueTier, radioName = song.radioName)
                synchronized(lock) {
                    if (request != generation.get() || closed) return@synchronized
                    val current = _state.value
                    val rows = current.queue.map { if (it.queueEntryId == song.queueEntryId) prepared else it }
                    _state.value = current.copy(queue = rows, song = prepared, durationMs = prepared.durationText.durationMillis())
                    if (current.automix) requestAnalysis(prepared)
                }
                val stream = resolve(prepared)
                synchronized(lock) {
                    if (request != generation.get() || closed) return@synchronized
                    val active = Session(request, prepared)
                    active.seekTo.set(_state.value.positionMs)
                    session = active
                    scope.launch(audioWorker) { active.render(stream) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(request, failure)
            }
        }
    }

    private fun advance(manual: Boolean) {
        val current = _state.value
        if (current.queue.isEmpty()) return
        if (!manual && current.repeat == 1) return begin(current.queueIndex, rememberPrevious = false)
        val next = if (current.shuffle) {
            var candidates = current.queue.indices.filter { current.queue[it].queueEntryId !in heard }
            if (candidates.isEmpty() && current.repeat == 2) {
                heard.clear()
                candidates = current.queue.indices.filter { it != current.queueIndex }
                    .ifEmpty { current.queue.indices.toList() }
            }
            candidates.takeIf { it.isNotEmpty() }?.random(Random.Default)
        } else {
            (current.queueIndex + 1).takeIf { it in current.queue.indices }
                ?: 0.takeIf { current.repeat == 2 }
        }
        if (next == null) {
            generation.incrementAndGet()
            resolverJob?.cancel()
            cancelLoop()
            cancelBlend()
            session?.cancel()
            session = null
            _state.value = current.copy(isPlaying = false, isLoading = false)
        } else begin(next)
    }

    /** Select without consuming history: an incoming peer is not heard until handoff. */
    private fun peekNext(): Int? {
        val current = _state.value
        if (current.repeat == 1 || current.queue.isEmpty()) return null
        if (!current.shuffle) return (current.queueIndex + 1).takeIf { it in current.queue.indices }
            ?: 0.takeIf { current.repeat == 2 }
        val candidates = current.queue.indices.filter { current.queue[it].queueEntryId !in heard }
        return candidates.randomOrNull() ?: if (current.repeat == 2) {
            current.queue.indices.filter { it != current.queueIndex }.randomOrNull()
        } else null
    }

    private inner class Blend(
        val outgoing: Session,
        var nextSong: Song,
        val plan: TransitionPlan,
        val request: Long,
    ) {
        val advanced = _state.value.automix && _state.value.advancedAutomix
        val render = MobileMixRender(plan, advanced)
        val phase = MobilePhaseLock(analysisFor(outgoing.song), analysisFor(nextSong), plan)
        var job: Job? = null
        var incoming: Session? = null
        @Volatile var started = false
        var startPosition = 0L
        var startedAtNanos = 0L
        var handedOff = false
    }

    private inner class LoopBuild(val owner: Session, val song: Song, val plan: LoopMix.Plan, val request: Long) { var job: Job? = null }

    private class GaplessReady(val song: Song, val source: PlayableStream, val audio: MobileAudioDecoder, val first: ByteArray)
    private inner class GaplessPending(var owner: Session?, val song: Song, val request: Long) {
        val cancelled = AtomicBoolean()
        val ready = AtomicReference<GaplessReady?>()
        var job: Job? = null
    }

    private fun cancelGapless() {
        val pending = gapless ?: return
        gapless = null
        pending.cancelled.set(true)
        pending.job?.cancel()
        pending.ready.getAndSet(null)?.audio?.close()
    }

    /** Mobile queue preloading, with the next decoded PCM already in the current output format. */
    private fun considerGapless(owner: Session) = synchronized(lock) {
        if (owner !== session || gapless != null || blend != null || loopMix != null || loopBuild != null || owner.stopped()) return@synchronized
        val current = _state.value
        if (current.sleepAfterTrack || !mixingAllowed() || (!current.skipSilence && !owner.gaplessEligible && (current.automix || current.crossfadeSeconds > 0))) return@synchronized
        if (owner.duration <= 0 || owner.position < owner.duration - 15_000) return@synchronized
        val index = if (current.repeat == 1) current.queueIndex else peekNext() ?: return@synchronized
        val next = current.queue.getOrNull(index) ?: return@synchronized
        val format = owner.line?.format ?: return@synchronized
        val pending = GaplessPending(owner, next, generation.get())
        gapless = pending
        pending.job = scope.launch {
            var audio: MobileAudioDecoder? = null
            try {
                val prepared = prepareSong(next).copy(queueEntryId = next.queueEntryId, queueTier = next.queueTier, radioName = next.radioName)
                val source = resolve(prepared)
                val aborted = { pending.cancelled.get() || pending.request != generation.get() || closed }
                val decoder = MobileAudioDecoder(source, aborted).also { audio = it }
                decoder.open(); decoder.configureOutput(format); decoder.setControls(_state.value.speed, 1f)
                val first = decoder.read() ?: error("The next track contained no audio")
                synchronized(lock) {
                    if (gapless !== pending || aborted()) return@synchronized
                    pending.ready.set(GaplessReady(prepared, source, decoder, first))
                    audio = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Ordinary queue advancement may resolve again if this source becomes playable.
            } finally { audio?.close() }
        }
    }

    /** Append the next decoder to the very same device queue: no stop, drain, close or silence. */
    private fun continueGapless(owner: Session, output: SourceDataLine, writtenFrames: Long, filters: MobileMixDsp): Boolean = synchronized(lock) {
        val pending = gapless?.takeIf { it.owner === owner } ?: return@synchronized false
        val current = _state.value
        if (owner.stopped() || current.sleepAfterTrack || !mixingAllowed()) return@synchronized false
        val ready = pending.ready.getAndSet(null) ?: return@synchronized false
        val index = current.queue.indexOfFirst { it.queueEntryId == ready.song.queueEntryId }
        if (index < 0) { ready.audio.close(); return@synchronized false }
        gapless = null
        pending.owner = null
        val next = Session(pending.request, ready.song)
        next.preparedGapless = pending
        next.line = output
        owner.line = null
        session = next
        if (owner.song.queueEntryId != ready.song.queueEntryId) owner.song.queueEntryId?.let(history::addLast)
        ready.song.queueEntryId?.let(heard::add)
        _state.value = current.copy(queue = current.queue.map { if (it.queueEntryId == ready.song.queueEntryId) ready.song else it },
            song = ready.song, queueIndex = index, positionMs = 0, durationMs = ready.audio.durationMs,
            format = ready.audio.description, audioInfo = ready.audio.info, isLoading = false,
            sampledAtNanos = System.nanoTime(), discontinuity = current.discontinuity + 1)
        clearMixUi()
        filters.nextTrack(ready.song.videoId)
        try {
            audioWorker.executor.execute { next.render(ready.source, ready, output, writtenFrames, filters) }
        } catch (failure: Exception) {
            ready.audio.close(); output.close(); fail(pending.request, failure)
        }
        true
    }
    private inner class LoopBlend(val outgoing: Session, val incoming: Session, val native: LoopMixSession) {
        @Volatile var incomingStarted = false
        var handedOff = false
        var alignmentRound = 0
        var alignAfter = 0L
        val offsets = ArrayList<Double>()
        var lastPosition = -1L
    }

    private fun askLoopLyrics(song: Song) {
        if (!lyricsRequested.add(song.videoId)) return
        scope.launch {
            val spans = runCatching { syncedLyrics(song) }.getOrNull()?.takeIf { lines -> lines.count { it.timeMs > 0 } >= 4 }
                ?.let { lines -> LoopMix.sungSpans(lines.map { LoopMix.Line(it.timeMs, it.sungUntilMs ?: it.words.lastOrNull()?.endMs, it.isGap || it.text.isBlank()) }) }
            loopLyrics[song.videoId] = LyricEvidence(spans)
        }
    }

    /** Original mobile top rung: measured, instrumental bars through the native two-deck renderer. */
    private fun considerLoop(owner: Session, upcoming: Song, position: Long): Boolean {
        if (failedLoopEntry == owner.song.queueEntryId || _state.value.speed != 1f || !owner.loopPort.supportsLoopMix || !LoopMixRenderer.available) return false
        if (analysisStateFor(owner.song) != TrackAnalysisState.ANALYSED || analysisStateFor(upcoming) != TrackAnalysisState.ANALYSED) return false
        fun eligible(song: Song) = song.localPath != null || song.localUri != null || song.videoId.matches(Regex("[A-Za-z0-9_-]{11}"))
        if (!eligible(owner.song) || !eligible(upcoming)) return false
        askLoopLyrics(owner.song); askLoopLyrics(upcoming)
        val outLyrics = loopLyrics[owner.song.videoId] ?: return false
        val inLyrics = loopLyrics[upcoming.videoId] ?: return false
        val outAnalysis = analysisFor(owner.song)
        val inAnalysis = analysisFor(upcoming)
        val nextDuration = upcoming.durationText.durationMillis().takeIf { it > 0 } ?: (inAnalysis.duration * 1_000).toLong()
        val reason = StringBuilder()
        val plan = LoopMix.plan(
            outAnalysis, owner.duration / 1_000.0, inAnalysis, nextDuration / 1_000.0,
            position / 1_000.0 + 9.0, outLyrics.spans, inLyrics.spans,
            outVideo = owner.song.isVideoOrigin, inVideo = upcoming.isVideoOrigin, why = reason,
        ) ?: return false
        _mixUi.value = _mixUi.value.copy(
            analysis = SmartAnalysis(TrackAnalysisState.ANALYSED, TrackAnalysisState.ANALYSED),
            detail = "Instrumental loop mix · ${plan.out.bpm.roundToInt()} → ${plan.inn.bpm.roundToInt()} BPM",
            window = TransitionWindow((plan.out.loopStart * 1_000 / owner.duration).toFloat(), (plan.out.loopEnd * 1_000 / owner.duration).toFloat()),
        )
        if (position < plan.out.loopStart * 1_000 - 12_000) return true
        val pending = LoopBuild(owner, upcoming, plan, generation.get())
        loopBuild = pending
        pending.job = scope.launch {
            var made: LoopMixSession? = null
            try {
                val outSource = resolve(owner.song)
                val song = prepareSong(upcoming).copy(queueEntryId = upcoming.queueEntryId, queueTier = upcoming.queueTier, radioName = upcoming.radioName)
                val inSource = resolve(song)
                val cancelled = { closed || pending.request != generation.get() || loopBuild !== pending || !isActive }
                fun level(id: String): Float = if (MobileDspSettings.state.value.loudness) {
                    StreamResolver.loudnessDbFor(id)?.let { 10.0.pow((-it * 100).roundToInt().coerceIn(-1_500, 300) / 2_000.0).toFloat() } ?: 1f
                } else 1f
                made = LoopMixSession.build(plan, owner.song.videoId, song.videoId, owner.loopPort.sampleRate, level(song.videoId) / level(owner.song.videoId)) { side, from, to ->
                    loopRegion(if (side == LoopMix.Side.OUT) outSource else inSource, from, to, owner.loopPort.sampleRate, cancelled)
                } ?: error("The loop decks could not be decoded")
                synchronized(lock) {
                    if (cancelled() || owner.stopped() || owner.position >= plan.out.loopStart * 1_000 - 700) return@synchronized
                    val native = checkNotNull(made)
                    val incoming = Session(pending.request, song)
                    val group = LoopBlend(owner, incoming, native)
                    incoming.preparedLoop = group
                    incoming.seekTo.set((native.inStartTrack * 1_000).roundToLong())
                    native.attach(owner.loopPort, incoming.loopPort)
                    incoming.loopPort.armIncoming(LoopMixPort.InArm((native.handoverTrack * 1_000_000).roundToLong()))
                    owner.ownedLoop = native
                    made = null
                    loopBuild = null
                    loopMix = group
                    _state.value = _state.value.copy(queue = _state.value.queue.map { if (it.queueEntryId == song.queueEntryId) song else it })
                    scope.launch(audioWorker) { incoming.render(inSource) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) { if (loopBuild === pending) failedLoopEntry = owner.song.queueEntryId }
            } finally {
                made?.close()
                synchronized(lock) { if (loopBuild === pending) { loopBuild = null; failedLoopEntry = owner.song.queueEntryId } }
            }
        }
        return true
    }

    private fun loopPosition(group: LoopBlend, owner: Session): Long? {
        if (!group.outgoing.loopPort.tookOver) return null
        val seconds = group.outgoing.position / 1_000.0 - group.native.plan.out.loopStart
        val side = if (owner === group.incoming) LoopMix.Side.IN else LoopMix.Side.OUT
        return LoopMix.positionAt(group.native.clock, side, seconds)?.times(1_000)?.roundToLong()
    }

    private fun driveLoop(owner: Session) = synchronized(lock) {
        val group = loopMix ?: return@synchronized
        if (group.outgoing !== owner || owner.stopped()) return@synchronized
        if (owner.loopPort.interrupted || group.incoming.loopPort.interrupted || !mixingAllowed() || _state.value.queue.none { it.queueEntryId == group.incoming.song.queueEntryId }) {
            cancelLoop(); return@synchronized
        }
        if (!owner.loopPort.tookOver) return@synchronized
        val native = group.native
        val mixSeconds = owner.position / 1_000.0 - native.plan.out.loopStart
        val now = System.nanoTime()
        if (mixSeconds >= native.inStartAt) group.incomingStarted = true
        if (group.incomingStarted && group.incoming.ready && _state.value.isPlaying && group.alignmentRound < 3 && now >= group.alignAfter) {
            native.offsetNow(now)?.let(group.offsets::add)
            if (group.offsets.size >= 15) {
                val error = group.offsets.sorted()[group.offsets.size / 2]
                group.offsets.clear()
                native.recordAligned(error)
                if (LoopMixSession.aligned(error)) group.alignmentRound = 3
                else {
                    native.correct(error)
                    group.alignmentRound++
                    group.alignAfter = now + 1_200_000_000
                }
            }
        }
        if (!group.handedOff && mixSeconds >= native.clock.handoffAt && group.incoming.ready) {
            val current = _state.value
            val index = current.queue.indexOfFirst { it.queueEntryId == group.incoming.song.queueEntryId }
            if (index < 0) { cancelLoop(); return@synchronized }
            group.handedOff = true
            group.outgoing.song.queueEntryId?.let(history::addLast)
            group.incoming.song.queueEntryId?.let(heard::add)
            session = group.incoming
            _state.value = current.copy(song = group.incoming.song, queueIndex = index, durationMs = group.incoming.duration,
                positionMs = loopPosition(group, group.incoming) ?: group.incoming.position, format = group.incoming.description, audioInfo = group.incoming.audioInfo,
                discontinuity = current.discontinuity + 1, isLoading = false)
        }
        val current = _state.value
        val progress = (mixSeconds / native.clock.elementAt).coerceIn(0.0, 1.0).toFloat()
        _state.value = current.copy(transition = "Advanced loop mix", transitionProgress = progress)
        val (beat, fraction) = native.clock.beatAt(mixSeconds)
        val beatMs = (60_000 / native.clock.tempo(beat.coerceAtLeast(0))).toFloat()
        _mixUi.value = _mixUi.value.copy(window = null, blend = MixBlend(beatMs, now - (fraction * beatMs * 1_000_000).toLong(), current.isPlaying))
        val displayed = session?.let { loopPosition(group, it) }
        if (displayed != null) {
            val wrapped = group.lastPosition >= 0 && displayed + 400 < group.lastPosition
            group.lastPosition = displayed
            _state.value = _state.value.copy(positionMs = displayed, sampledAtNanos = now, discontinuity = _state.value.discontinuity + if (wrapped) 1 else 0)
        }
        if (group.handedOff && mixSeconds >= native.clock.elementAt + 0.35) {
            loopMix = null
            native.outPort.disarm(); native.inPort.disarm()
            group.outgoing.cancel()
            group.incoming.preparedLoop = null
            _state.value = _state.value.copy(transition = null, transitionProgress = 0f)
            clearMixUi()
        }
    }

    private fun cancelLoop() {
        loopBuild?.job?.cancel(); loopBuild = null
        val group = loopMix ?: return
        val selected = session
        val position = selected?.let { loopPosition(group, it) }
        loopMix = null
        group.native.outPort.disarm(); group.native.inPort.disarm()
        group.outgoing.ownedLoop = null
        group.outgoing.retiredLoops.add(group.native)
        if (group.outgoing !== selected) group.outgoing.cancel()
        if (group.incoming !== selected) group.incoming.cancel()
        selected?.preparedLoop = null
        if (selected != null && position != null && !selected.stopped()) {
            selected.seekTo.set(position)
            selected.line?.flush()
            _state.value = _state.value.copy(positionMs = position, discontinuity = _state.value.discontinuity + 1)
        }
        _state.value = _state.value.copy(transition = null, transitionProgress = 0f)
        clearMixUi()
    }

    /** Mobile CrossfadeController's prepared incoming peer and midpoint ownership swap. */
    private fun considerBlend(owner: Session, position: Long) = synchronized(lock) {
        if (owner !== session || blend != null || loopMix != null || loopBuild != null || owner.stopped() || failedBlendEntry == owner.song.queueEntryId) return@synchronized
        val now = System.nanoTime()
        if (now - owner.lastPlanAt < 250_000_000) return@synchronized
        owner.lastPlanAt = now
        val current = _state.value
        if (current.sleepAfterTrack || current.skipSilence) return@synchronized
        if ((!current.automix && current.crossfadeSeconds == 0) || !mixingAllowed()) return@synchronized
        if (owner.song.isVideoOrigin || current.durationMs <= 0) return@synchronized
        val nextIndex = peekNext() ?: run { clearMixUi(); return@synchronized }
        val upcoming = current.queue.getOrNull(nextIndex) ?: return@synchronized
        if (upcoming.isVideoOrigin || upcoming.queueEntryId == owner.song.queueEntryId) return@synchronized
        if (current.automix) requestAnalysis(upcoming)
        if (current.automix && current.advancedAutomix && considerLoop(owner, upcoming, position)) return@synchronized
        val plan = planTransition(
            analysis = if (current.automix) analysisFor(owner.song) else TrackAnalysis(),
            nextAnalysis = if (current.automix) analysisFor(upcoming) else TrackAnalysis(),
            currentTrack = owner.song.transitionInfo(current.durationMs),
            nextTrack = upcoming.transitionInfo(upcoming.durationText.durationMillis()),
            currentTime = position / 1_000.0,
            duration = current.durationMs / 1_000.0,
            fadeSeconds = if (current.automix) 8.0 else current.crossfadeSeconds.toDouble(),
            mode = if (current.automix) CrossfadeMode.SMART else CrossfadeMode.STANDARD,
            phraseBars = current.mixBars,
            advanced = current.advancedAutomix,
            albumSequential = owner.song.albumId != null && owner.song.albumId == upcoming.albumId && nextIndex == current.queueIndex + 1,
        )
        if (current.automix) publishMixPlan(owner, upcoming, plan)
        owner.gaplessEligible = !plan.blocked && plan.fadeMs <= 0
        if (plan.blocked || plan.fadeMs <= 0 || position < plan.transitionStart * 1_000 - 12_000) return@synchronized
        cancelGapless()
        val pending = Blend(owner, upcoming, plan, generation.get())
        blend = pending
        pending.job = scope.launch {
            try {
                val prepared = prepareSong(upcoming).copy(queueEntryId = upcoming.queueEntryId, queueTier = upcoming.queueTier, radioName = upcoming.radioName)
                val source = resolve(prepared)
                synchronized(lock) {
                    if (blend !== pending || pending.request != generation.get()) return@synchronized
                    pending.nextSong = prepared
                    _state.value = _state.value.copy(queue = _state.value.queue.map { if (it.queueEntryId == prepared.queueEntryId) prepared else it })
                    val incoming = Session(pending.request, prepared)
                    incoming.preparedFor = pending
                    incoming.gain = 0f
                    incoming.tempoMultiplier = plan.incomingPlaybackRate.toFloat()
                    incoming.seekTo.set((plan.incomingCueTime * 1_000).toLong())
                    pending.incoming = incoming
                    scope.launch(audioWorker) { incoming.render(source) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) {
                    if (blend === pending) {
                        failedBlendEntry = owner.song.queueEntryId
                        cancelBlend()
                    }
                }
            }
        }
    }

    private fun publishMixPlan(owner: Session, upcoming: Song, plan: TransitionPlan) {
        val analysis = SmartAnalysis(analysisStateFor(owner.song), analysisStateFor(upcoming))
        val window = if (!plan.blocked && plan.markerVisible && owner.duration > 0) TransitionWindow(
            (plan.transitionStart * 1_000 / owner.duration).toFloat().coerceIn(0f, 1f),
            (plan.transitionEnd * 1_000 / owner.duration).toFloat().coerceIn(0f, 1f),
        ) else null
        _mixUi.value = _mixUi.value.copy(analysis = analysis, window = window, detail = plan.reason)
        if (plan.blocked || plan.fadeMs <= 0) { _mixUi.value = _mixUi.value.copy(preview = null); return }
        val outAnalysis = analysisFor(owner.song)
        val inAnalysis = analysisFor(upcoming)
        val key = "${owner.song.queueEntryId}:${upcoming.queueEntryId}:${plan.transitionStart}:${plan.transitionEnd}:${plan.incomingCueTime}:${outAnalysis.bpm}:${inAnalysis.bpm}:${_state.value.mixBars}"
        if (previewKey != key) {
            waveJob?.cancel(); waveJob = null
            previewKey = key
            _mixUi.value = _mixUi.value.copy(preview = mixView(
                owner.song, upcoming, plan, outAnalysis, inAnalysis, owner.duration,
                upcoming.durationText.durationMillis().takeIf { it > 0 } ?: (inAnalysis.duration * 1_000).toLong(),
                advanced = _state.value.advancedAutomix,
            ))
        }
        val preview = _mixUi.value.preview ?: return
        if (!mixViewOpen || waveJob != null || preview.outgoing.low.isNotEmpty()) return
        val request = generation.get()
        _mixUi.value = _mixUi.value.copy(preview = preview.withDecks(preview.outgoing.withDecoding(true), preview.incoming.withDecoding(true)))
        waveJob = scope.launch {
            val aborted = { request != generation.get() || !mixViewOpen || closed || !isActive }
            try {
                val outWave = waveform(resolve(owner.song), preview.outgoing, aborted)
                val inWave = waveform(resolve(upcoming), preview.incoming, aborted)
                synchronized(lock) {
                    if (!aborted() && previewKey == key) {
                        val drawn = preview.withDecks(outWave, inWave)
                        _mixUi.value = _mixUi.value.copy(preview = drawn, view = if (blend?.started == true) drawn else _mixUi.value.view)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) {
                    if (!aborted() && previewKey == key) _mixUi.value = _mixUi.value.copy(preview = preview.withDecks(preview.outgoing.withDecoding(false), preview.incoming.withDecoding(false)))
                }
            }
        }
    }

    private fun clearMixUi() {
        waveJob?.cancel(); waveJob = null
        previewKey = null
        _mixUi.value = MixUiState()
    }

    private fun driveBlend(owner: Session, position: Long) = synchronized(lock) {
        val pending = blend ?: return@synchronized
        if (pending.outgoing !== owner || owner.stopped()) return@synchronized
        if (_state.value.queue.none { it.queueEntryId == pending.nextSong.queueEntryId } || !mixingAllowed()) {
            cancelBlend()
            return@synchronized
        }
        if (pending.advanced && !pending.started) owner.tempoMultiplier = mobileOutgoingRamp(pending.plan, position)
        val incoming = pending.incoming ?: return@synchronized
        if (!incoming.ready || !_state.value.isPlaying) return@synchronized
        if (!pending.started) {
            if (position < pending.plan.transitionStart * 1_000) return@synchronized
            pending.startPosition = position
            pending.startedAtNanos = System.nanoTime()
            pending.started = true
            owner.tempoMultiplier = pending.plan.outgoingPlaybackRate.toFloat()
        }
        val remaining = (owner.duration - pending.startPosition).coerceAtLeast(1)
        val incomingCap = ((incoming.duration - pending.plan.incomingCueTime * 1_000) / 3).takeIf { it > 0 } ?: Double.MAX_VALUE
        val span = minOf(pending.plan.fadeMs, remaining, incomingCap.toLong()).coerceAtLeast(1)
        val ratio = pending.plan.incomingPlaybackRate / pending.plan.outgoingPlaybackRate
        val wallSpan = if (pending.advanced) span * ratio else span.toDouble()
        val elapsed = (incoming.position - pending.plan.incomingCueTime * 1_000).coerceAtLeast(0.0)
        val rawFraction = elapsed / wallSpan
        val endProgress = ((span - (owner.duration - position)).toDouble() / span).coerceIn(0.0, 1.0)
        val fraction = maxOf(rawFraction.coerceIn(0.0, 1.0), endProgress).toFloat()
        val outgoingGain = pending.render.fall(fraction)
        val incomingGain = pending.render.rise(fraction)
        // Same smooth headroom trim as mobile's two-player blend.
        val headroom = 1f / sqrt((pending.render.level(rawFraction.toFloat(), wallSpan.toFloat()) + incomingGain).coerceAtLeast(1f))
        owner.gain = outgoingGain * headroom
        incoming.gain = incomingGain * headroom
        pending.render.ride((maxOf(rawFraction, fraction.toDouble()) + owner.renderAheadMs / span).toFloat(), owner.dsp?.filter, null)
        pending.render.ride(((elapsed + incoming.renderAheadMs) / wallSpan).toFloat(), null, incoming.dsp?.filter)
        if (pending.render.phaseLock && System.nanoTime() - pending.startedAtNanos >= 300_000_000) {
            incoming.tempoMultiplier = pending.phase.update(position, incoming.position, fraction, _state.value.speed, System.nanoTime())
        }
        _state.value = _state.value.copy(
            transition = if (_state.value.automix) "AutoMix · ${pending.plan.reason}" else "Crossfade",
            transitionProgress = fraction.toFloat(),
        )
        if (_state.value.automix) {
            val beatMs = (pending.plan.beatSeconds * 1_000 / (_state.value.speed * owner.tempoMultiplier)).toFloat()
            val analysis = analysisFor(owner.song)
            val anchor = analysis.tailBeats?.beats?.lastOrNull { it <= position / 1_000.0 }
                ?: analysis.downbeats.lastOrNull { it <= position / 1_000.0 } ?: pending.plan.transitionStart
            val anchorNanos = System.nanoTime() - ((position - anchor * 1_000) / (_state.value.speed * owner.tempoMultiplier) * 1_000_000).toLong()
            _mixUi.value = _mixUi.value.copy(
                view = _mixUi.value.view ?: _mixUi.value.preview,
                blend = _mixUi.value.blend?.takeIf { it.beatMs == beatMs }?.copy(playing = _state.value.isPlaying)
                    ?: MixBlend(beatMs, anchorNanos, _state.value.isPlaying),
            )
        }
        if (fraction >= pending.render.handoffAt || owner.duration - position <= 400) handoff(pending)
        if (fraction >= 1f && pending.render.tailDone(rawFraction.toFloat(), wallSpan.toFloat())) finishBlend(pending)
    }

    private fun handoff(pending: Blend) {
        if (pending.handedOff) return
        val incoming = pending.incoming ?: return
        val current = _state.value
        val index = current.queue.indexOfFirst { it.queueEntryId == pending.nextSong.queueEntryId }
        if (index < 0) return cancelBlend()
        pending.handedOff = true
        pending.outgoing.song.queueEntryId?.let(history::addLast)
        if (current.shuffle && pending.nextSong.queueEntryId in heard && current.repeat == 2) heard.clear()
        pending.nextSong.queueEntryId?.let(heard::add)
        session = incoming
        _state.value = current.copy(
            song = pending.nextSong,
            queueIndex = index,
            durationMs = incoming.duration,
            positionMs = incoming.position,
            sampledAtNanos = System.nanoTime(),
            discontinuity = current.discontinuity + 1,
            format = incoming.description,
            audioInfo = incoming.audioInfo,
            isLoading = false,
            error = null,
        )
    }

    private fun finishBlend(pending: Blend) {
        if (blend !== pending) return
        handoff(pending)
        if (blend !== pending) return
        pending.outgoing.cancel()
        pending.incoming?.apply {
            gain = 1f; preparedFor = null
            tempoMultiplier = if (pending.advanced) pending.plan.incomingPlaybackRate.toFloat() else 1f
            tempoEase = if (pending.advanced && abs(tempoMultiplier - 1f) > 0.0001f) MobileTempoEase(tempoMultiplier, position, analysisFor(song)) else null
            dsp?.filter?.open(); dsp?.filter?.parkEcho()
        }
        blend = null
        _state.value = _state.value.copy(transition = null, transitionProgress = 0f)
        clearMixUi()
    }

    private fun cancelBlend() {
        cancelGapless()
        val pending = blend ?: return
        blend = null
        pending.job?.cancel()
        if (pending.outgoing !== session) pending.outgoing.cancel()
        if (pending.incoming !== session) pending.incoming?.cancel()
        session?.apply {
            gain = 1f; tempoMultiplier = 1f; tempoEase = null; preparedFor = null
            dsp?.filter?.open(); dsp?.filter?.parkEcho()
        }
        _state.value = _state.value.copy(transition = null, transitionProgress = 0f)
        if (pending.handedOff) clearMixUi() else _mixUi.value = _mixUi.value.copy(view = null, blend = null)
    }

    private fun fail(request: Long, failure: Exception) = synchronized(lock) {
        if (request != generation.get() || closed) return@synchronized
        cancelLoop()
        cancelBlend()
        session?.cancel()
        session = null
        _state.value = _state.value.copy(isPlaying = false, isLoading = false, error = failure.message ?: "Playback failed")
    }

    private inner class Session(private val request: Long, val song: Song) {
        private val cancelled = AtomicBoolean()
        val seekTo = AtomicLong(-1)
        @Volatile var line: SourceDataLine? = null
        @Volatile private var decoder: MobileAudioDecoder? = null
        @Volatile var gain = 1f
        @Volatile var tempoMultiplier = 1f
        @Volatile var tempoEase: MobileTempoEase? = null
        @Volatile var preparedGapless: GaplessPending? = null
        @Volatile var gaplessEligible = false
        @Volatile var ready = false
        @Volatile var preparedFor: Blend? = null
        val loopPort = LoopMixPort()
        @Volatile var preparedLoop: LoopBlend? = null
        @Volatile var ownedLoop: LoopMixSession? = null
        val retiredLoops = ConcurrentLinkedQueue<LoopMixSession>()
        @Volatile var duration = song.durationText.durationMillis()
        @Volatile var position = 0L
        @Volatile var description: String? = null
        @Volatile var audioInfo: DecodedAudioInfo? = null
        @Volatile var dsp: MobileMixDsp? = null
        @Volatile var renderAheadMs = 0.0
        var lastPlanAt = 0L

        fun stopped(): Boolean = cancelled.get() || request != generation.get() || closed

        fun cancel() {
            cancelled.set(true)
            preparedGapless?.cancelled?.set(true)
            decoder?.abortNetwork()
            // This also releases a write waiting on a stopped or disconnected audio device.
            runCatching { line?.close() }
        }

        fun render(source: PlayableStream, prepared: GaplessReady? = null, reusedOutput: SourceDataLine? = null,
                   boundaryFrame: Long = 0, reusedFilters: MobileMixDsp? = null) {
            try {
                val audio = (prepared?.audio ?: MobileAudioDecoder(source, ::stopped)).also { decoder = it }
                if (prepared == null) audio.open()
                if (stopped()) return
                val output = reusedOutput ?: outputFactory(audio.sampleRate, audio.channels, audio.bitDepth, _state.value.outputDeviceId)
                line = output
                val loopPcm = MobileLoopPcm(output.format, loopPort)
                val silence = MobileSilence(output.format)
                val filters = (reusedFilters ?: MobileMixDsp(output.format, song.videoId)).also { dsp = it }
                if (prepared == null) audio.configureOutput(output.format)
                var speed = (_state.value.speed * tempoMultiplier).coerceIn(0.5f, 2f)
                var renderedGain = gain * _state.value.volume
                // Native loop decks and ordinary decoded PCM share the final user-volume fader.
                audio.setControls(speed, 1f)
                val initial = seekTo.getAndSet(-1).coerceAtLeast(0)
                if (initial > 0) audio.seek(initial)
                // Arm only after real PCM exists: a slow first read cannot leave a hole.
                var primed = prepared?.first ?: if (preparedFor != null || preparedLoop != null) audio.read() ?: error("The incoming track contained no audio") else null
                val clock = AudioDeviceClock(output.format.sampleRate.toDouble())
                clock.reset(if (reusedOutput != null) boundaryFrame else output.longFramePosition, initial.toDouble())
                var writtenFrames = if (reusedOutput != null) boundaryFrame else output.longFramePosition
                var writePosition = initial.toDouble()
                var lastPublish = 0L
                position = initial
                loopPort.positionUs = initial * 1_000
                loopPort.positionNanos = System.nanoTime()
                duration = audio.durationMs.takeIf { it > 0 } ?: duration
                description = audio.description
                audioInfo = audio.info
                synchronized(lock) {
                    if (stopped()) return
                    ready = true
                    if (session === this) _state.value = _state.value.copy(durationMs = duration, format = description, audioInfo = audioInfo)
                }

                while (!stopped()) {
                    loopPcm.refresh()
                    while (true) (retiredLoops.poll() ?: break).close()
                    if (preparedFor?.started == false || preparedLoop?.incomingStarted == false) {
                        Thread.sleep(10)
                        continue
                    }
                    val seek = seekTo.getAndSet(-1)
                    if (seek >= 0) {
                        primed = null
                        silence.reset()
                        filters.reset()
                        output.flush()
                        audio.seek(seek)
                        writtenFrames = output.longFramePosition
                        writePosition = seek.toDouble()
                        clock.reset(writtenFrames, writePosition)
                    }
                    val control = _state.value
                    if (!control.isPlaying) {
                        output.stop()
                        Thread.sleep(20)
                        continue
                    }
                    if (!output.isRunning) output.start()
                    tempoEase?.let { ease ->
                        tempoMultiplier = if (mixingAllowed()) ease.rate(position) else 1f
                        if (abs(tempoMultiplier - 1f) < 0.0001f) tempoEase = null
                    }
                    val requestedSpeed = (control.speed * tempoMultiplier).coerceIn(0.5f, 2f)
                    if (speed != requestedSpeed) {
                        speed = requestedSpeed
                        audio.setControls(speed, 1f)
                    }
                    var silenceSkip = 0L
                    val packet = loopPcm.next(writePosition) {
                        silence.next(control.skipSilence && mixingAllowed(), speed) { primed?.also { primed = null } ?: audio.read() }
                            ?.also { silenceSkip += it.skipBeforeFrames }?.bytes
                    } ?: break
                    if (stopped() || seekTo.get() >= 0) continue
                    if (packet.timelineJumpFrames != 0L || silenceSkip != 0L) {
                        writePosition += (packet.timelineJumpFrames + silenceSkip * speed) * 1_000.0 / output.format.sampleRate
                        clock.mark(writtenFrames, writePosition, speed.toDouble())
                    }
                    val pcm = packet.floats?.let { samples ->
                        filters.process(samples, packet.frames)
                        encodePcm(samples, output.format)
                    } ?: checkNotNull(packet.bytes).also(filters::process)
                    val nextGain = gain * control.volume
                    scalePcm(pcm, output.format.sampleSizeInBits, output.format.channels, renderedGain, nextGain)
                    renderedGain = nextGain
                    val frames = pcm.size / output.format.frameSize
                    clock.append(writtenFrames, writePosition, speed.toDouble())
                    var offset = 0
                    while (offset < pcm.size && !stopped() && seekTo.get() < 0) {
                        val count = output.write(pcm, offset, pcm.size - offset)
                        if (count <= 0) break
                        offset += count
                    }
                    val accepted = offset / output.format.frameSize
                    writtenFrames += accepted
                    writePosition += accepted * 1_000.0 / output.format.sampleRate * speed
                    if (accepted < frames && !stopped() && seekTo.get() < 0) error("The audio output stopped accepting samples")
                    position = clock.position(output.longFramePosition).toLong()
                    loopPort.positionUs = (clock.position(output.longFramePosition) * 1_000).roundToLong()
                    loopPort.positionNanos = System.nanoTime()
                    renderAheadMs = (writePosition - position).coerceAtLeast(0.0)
                    val sleep = _state.value
                    if (sleep.sleepAfterTrack && session === this && duration > 0) {
                        val fade = (if (sleep.automix) 8_000 else sleep.crossfadeSeconds * 1_000).toLong().coerceAtMost(duration)
                        if (fade > 0) {
                            val fraction = ((position - (duration - fade)).toDouble() / fade).coerceIn(0.0, 1.0)
                            gain = cos(fraction * PI / 2).toFloat()
                        }
                    }
                    considerBlend(this, position)
                    considerGapless(this)
                    driveBlend(this, position)
                    driveLoop(this)
                    val now = System.nanoTime()
                    if (now - lastPublish >= 80_000_000L || _state.value.isLoading) {
                        lastPublish = now
                        synchronized(lock) {
                            if (!stopped() && session === this) _state.value = _state.value.copy(
                                isLoading = false,
                                positionMs = (loopMix?.let { loopPosition(it, this) } ?: position).coerceAtLeast(0),
                                sampledAtNanos = now,
                            )
                        }
                    }
                }
                if (!stopped()) {
                    if (continueGapless(this, output, writtenFrames, filters)) return
                    output.drain()
                    synchronized(lock) {
                        if (!stopped()) {
                            val pending = blend
                            val loop = loopMix
                            when {
                                loop?.incoming === this && session !== this -> {
                                    failedLoopEntry = loop.outgoing.song.queueEntryId
                                    cancelLoop()
                                }
                                pending?.outgoing === this && pending.started -> finishBlend(pending)
                                pending?.incoming === this && session !== this -> {
                                    failedBlendEntry = pending.outgoing.song.queueEntryId
                                    cancelBlend()
                                }
                                session === this -> {
                                    cancelLoop()
                                    cancelBlend()
                                    _state.value = _state.value.copy(positionMs = _state.value.durationMs, isLoading = false)
                                    session = null
                                    if (_state.value.sleepAfterTrack) {
                                        _state.value = _state.value.copy(isPlaying = false, sleepAfterTrack = false)
                                        commandInterceptor?.invoke(PlaybackCommand.Pause)
                                    } else if (completionInterceptor?.invoke() != true) advance(manual = false)
                                    else if (session == null) _state.value = _state.value.copy(isPlaying = false)
                                }
                            }
                        }
                    }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (failure: Exception) {
                synchronized(lock) {
                    if (!stopped()) {
                        if (session === this) fail(request, failure)
                        else {
                            loopMix?.let { failedLoopEntry = it.outgoing.song.queueEntryId }
                            cancelLoop()
                            blend?.let { failedBlendEntry = it.outgoing.song.queueEntryId }
                            cancelBlend()
                        }
                    }
                }
            } finally {
                runCatching { line?.close() }
                line = null
                decoder?.close()
                decoder = null
                ownedLoop?.close()
                ownedLoop = null
                while (true) (retiredLoops.poll() ?: break).close()
            }
        }
    }
}

private fun Song.withQueueIdentity(): Song = copy(queueEntryId = UUID.randomUUID().toString())
private fun Song.transitionInfo(duration: Long) = TransitionTrackInfo(
    id = videoId, durationMs = duration, title = title, artist = artist,
    album = albumName.orEmpty(), albumId = albumId.orEmpty(),
)
private fun IntRange.coerce(index: Int): Int = index.coerceIn(first, last)
private fun String?.durationMillis(): Long {
    if (this == null) return 0
    return split(':').fold(0L) { value, part -> value * 60 + (part.toLongOrNull() ?: 0) } * 1_000
}

/** A per-frame gain ramp avoids clicks when mobile's two peer faders change. */
internal fun scalePcm(bytes: ByteArray, bits: Int, channels: Int, from: Float, to: Float) {
    if (from == 1f && to == 1f) return
    if (from == 0f && to == 0f) { bytes.fill(0); return }
    val width = bits / 8
    val frames = bytes.size / (width * channels)
    if (frames <= 0) return
    val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frames) { frame ->
        val gain = from + (to - from) * (frame + 1).toDouble() / frames
        repeat(channels) {
            val offset = (frame * channels + it) * width
            if (bits == 16) pcm.putShort(offset, (pcm.getShort(offset) * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            else pcm.putInt(offset, (pcm.getInt(offset).toDouble() * gain).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
        }
    }
}

private fun openOutput(rate: Int, channels: Int, bits: Int, deviceId: Int?): SourceDataLine {
    val selected = deviceId?.let { id -> AudioSystem.getMixerInfo().getOrNull(id)?.let(AudioSystem::getMixer) }
    require(deviceId == null || selected != null) { "The selected audio output is no longer connected" }
    val counts = listOf(channels.coerceIn(1, 2), 2).distinct()
    val depths = if (bits > 16) listOf(32, 16) else listOf(16)
    val rates = listOf(rate.coerceAtLeast(8_000), 48_000, 44_100).distinct()
    var last: Exception? = null
    for (sampleRate in rates) for (count in counts) for (depth in depths) {
        val format = AudioFormat(sampleRate.toFloat(), depth, count, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        if (!(selected?.isLineSupported(info) ?: AudioSystem.isLineSupported(info))) continue
        var output: SourceDataLine? = null
        try {
            output = (selected?.getLine(info) ?: AudioSystem.getLine(info)) as SourceDataLine
            output.open(format, (sampleRate / 8) * format.frameSize)
            return output
        } catch (failure: Exception) {
            last = failure
            runCatching { output?.close() }
        }
    }
    throw IllegalStateException("No usable audio output is available. Check the Windows/Linux output device.", last)
}

/** Maps played device frames across speed changes, excluding audio that is merely buffered. */
internal class AudioDeviceClock(private val sampleRate: Double) {
    private data class Segment(val frame: Long, val milliseconds: Double, val speed: Double)
    private val segments = ArrayDeque<Segment>()

    fun reset(frame: Long, milliseconds: Double) {
        segments.clear()
        segments.addLast(Segment(frame, milliseconds, 1.0))
    }

    fun append(frame: Long, milliseconds: Double, speed: Double) {
        val previous = segments.lastOrNull()
        if (previous == null || abs(previous.speed - speed) > 0.0001) {
            segments.addLast(Segment(frame, milliseconds, speed))
        }
    }

    /** A silence/drop splice changes media time only once its boundary reaches the device. */
    fun mark(frame: Long, milliseconds: Double, speed: Double) {
        if (segments.lastOrNull()?.frame == frame) segments.removeLast()
        segments.addLast(Segment(frame, milliseconds, speed))
    }

    fun position(playedFrame: Long): Double {
        while (segments.size > 1 && segments.elementAt(1).frame <= playedFrame) segments.removeFirst()
        val segment = segments.firstOrNull() ?: return 0.0
        return segment.milliseconds + (playedFrame - segment.frame).coerceAtLeast(0) * 1_000 / sampleRate * segment.speed
    }
}
