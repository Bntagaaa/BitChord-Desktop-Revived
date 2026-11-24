package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val AUTO_MIX_LOOKAHEAD_NANOS = 120_000_000L

internal data class DesktopAutoMixArtworkTrack(
    val id: String,
    val entry: String?,
    val url: String?,
    private val title: String,
    private val artist: String,
    private val album: String?,
) {
    constructor(song: Song) : this(song.videoId, song.queueEntryId, song.thumbnailUrl,
        song.title, song.artist, song.albumName)

    fun canvasCandidate() = Song(id, title, artist, url, albumName = album)

    fun matches(song: Song) = matches(song.videoId, song.queueEntryId)
    fun matches(id: String, entry: String?) = this.id == id && this.entry == entry
}

internal data class DesktopAutoMixArtworkPair(
    val generation: Long,
    val outgoing: DesktopAutoMixArtworkTrack,
    val incoming: DesktopAutoMixArtworkTrack,
    val armedAtNanos: Long,
)

internal data class DesktopAutoMixVisualSample(
    val pair: DesktopAutoMixArtworkPair,
    val revealStart: Long = Long.MAX_VALUE,
    val revealEnd: Long = Long.MAX_VALUE,
    val played: Long = 0,
    val submitted: Long = 0,
    val rate: Int = 0,
    val sampledAtNanos: Long = 0,
    val playing: Boolean = false,
) {
    fun progress(nowNanos: Long): Float {
        if (revealStart == Long.MAX_VALUE || revealEnd <= revealStart) return 0f
        val ahead = if (playing) (nowNanos - sampledAtNanos).coerceIn(0L, AUTO_MIX_LOOKAHEAD_NANOS) / 1e9 * rate else 0.0
        val audible = minOf(played + ahead, submitted.toDouble())
        return ((audible - revealStart) / (revealEnd - revealStart)).toFloat().coerceIn(0f, 1f)
    }
}

/** The audio thread owns arming, sampling and cancellation. The UI only reads the feed. */
internal class DesktopAutoMixVisualFeed(private val releaseFrame: () -> Unit = {}) {
    private val _state = MutableStateFlow<DesktopAutoMixVisualSample?>(null)
    val state = _state.asStateFlow()
    private var generation = 0L
    private var endFrame: Long? = null

    fun arm(outgoing: Song, incoming: Song, now: Long = System.nanoTime()) {
        val old = state.value
        if (old != null && old.pair.outgoing.matches(outgoing) && old.pair.incoming.matches(incoming)) return
        endFrame = null
        releaseFrame()
        val pair = DesktopAutoMixArtworkPair(++generation, DesktopAutoMixArtworkTrack(outgoing),
            DesktopAutoMixArtworkTrack(incoming), now)
        _state.value = DesktopAutoMixVisualSample(pair)
    }

    fun begin(start: Long, swapSeconds: Double, endSeconds: Double, rate: Int, now: Long = System.nanoTime()) {
        val armed = state.value ?: return
        if (rate <= 0 || endSeconds - swapSeconds < MIN_WINDOW) {
            cancel()
            return
        }
        val prepared = ((now - armed.pair.armedAtNanos) / 1e9).coerceAtLeast(0.0)
        val remaining = endSeconds - swapSeconds
        val prepareLag = (1.5 - prepared).coerceIn(0.0, remaining - MIN_WINDOW)
        val window = minOf(2.6, remaining - prepareLag)
        val lag = maxOf(prepareLag, minOf(0.6, remaining - window))
        val revealStart = swapSeconds + lag
        val revealEndFrame = start + (minOf(endSeconds, revealStart + window) * rate).roundToLong()
        endFrame = revealEndFrame
        _state.value = armed.copy(
            revealStart = start + (revealStart * rate).roundToLong(),
            revealEnd = revealEndFrame,
            rate = rate,
        )
    }

    fun sample(played: Long, submitted: Long, playing: Boolean, now: Long = System.nanoTime()) {
        val current = state.value ?: return
        val end = endFrame ?: return
        if (played >= end) {
            cancel()
        } else if (playing != current.playing || now - current.sampledAtNanos >= 20_000_000L &&
            (played != current.played || submitted != current.submitted)
        ) {
            _state.value = current.copy(played = played, submitted = submitted.coerceAtLeast(played),
                playing = playing, sampledAtNanos = now)
        }
    }

    fun cancelArmed() {
        if (endFrame == null) cancel()
    }

    fun cancel() {
        if (_state.value == null) return
        releaseFrame()
        endFrame = null
        _state.value = null
    }

    private companion object {
        const val MIN_WINDOW = 0.8
    }
}
