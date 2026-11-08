package com.music.bitchord.reworked

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import org.freedesktop.dbus.errors.InvalidMethodArgument
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.errors.UnknownInterface
import org.freedesktop.dbus.errors.UnknownProperty
import java.nio.file.Path

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface ReworkedMprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface ReworkedMprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}

/** The Android media-session contract mapped to Linux MPRIS, authored for Reworked. */
internal class DesktopMpris(
    private val player: DesktopPlayback,
    private val raise: () -> Unit,
    private val quit: () -> Unit,
    private val openUri: (String) -> Unit,
) : ReworkedMprisRoot, ReworkedMprisPlayer, Properties, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var connection: DBusConnection? = null
    @Volatile private var explicitlyStopped = false
    val status = MutableStateFlow("Not connected")
    init {
        if (System.getProperty("os.name").startsWith("Linux") && !System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) scope.launch {
            var owned: DBusConnection? = null
            try {
                val bus = DBusConnectionBuilder.forSessionBus().withShared(false).build()
                owned = bus
                connection = bus
                ensureActive()
                bus.exportObject(PATH, this@DesktopMpris)
                bus.requestBusName("org.mpris.MediaPlayer2.BitChordReworked.instance${ProcessHandle.current().pid()}")
                status.value = "Connected"
                var previousDiscontinuity = -1L
                player.state.map { it.copy(positionMs = if (it.positionMs > 0) 1 else 0, sampledAtNanos = 0, transitionProgress = 0f) }.distinctUntilChanged().collect { state ->
                    if (player.state.value.isPlaying) explicitlyStopped = false
                    bus.sendMessage(Properties.PropertiesChanged(PATH, PLAYER, properties(PLAYER).filterKeys { it != "Position" }, emptyList()))
                    if (previousDiscontinuity >= 0 && state.discontinuity != previousDiscontinuity)
                        bus.sendMessage(ReworkedMprisPlayer.Seeked(PATH, player.state.value.positionMs * 1000))
                    previousDiscontinuity = state.discontinuity
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (isActive) status.value = "Unavailable: ${error.message?.take(160)}" }
            finally {
                if (connection === owned) connection = null
                runCatching { owned?.close() }
            }
        }
    }
    override fun getObjectPath() = PATH
    override fun Raise() { scope.launch(Dispatchers.Main) { raise() } }
    override fun Quit() { scope.launch(Dispatchers.Main) { quit() } }
    override fun Next() { if (canNext(player.state.value)) { explicitlyStopped = false; player.next() } }
    override fun Previous() { if (canPrevious(player.state.value)) { explicitlyStopped = false; player.previous() } }
    override fun Pause() = player.pause()
    override fun PlayPause() { explicitlyStopped = false; player.toggle() }
    override fun Stop() { explicitlyStopped = true; player.pause(); player.seek(0) }
    override fun Play() { explicitlyStopped = false; player.resume() }
    override fun Seek(offset: Long) {
        val state = player.state.value
        if (state.durationMs <= 0 || state.song == null) return
        val target = state.positionMs + offset / 1000
        if (target > state.durationMs) Next() else player.seek(target.coerceAtLeast(0))
    }
    override fun SetPosition(trackId: DBusPath, position: Long) {
        val state = player.state.value
        if (state.song != null && state.durationMs > 0 && trackId.path == trackPath(state.song).path && position in 0..state.durationMs * 1000)
            player.seek(position / 1000)
    }
    override fun OpenUri(uri: String) { scope.launch(Dispatchers.Main) { openUri(uri) } }
    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        (properties(interfaceName)[propertyName] ?: throw UnknownProperty(propertyName)).value as A
    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        if (interfaceName != PLAYER && interfaceName != ROOT) throw UnknownInterface(interfaceName)
        if (propertyName !in properties(interfaceName)) throw UnknownProperty(propertyName)
        if (interfaceName != PLAYER) throw PropertyReadOnly(propertyName)
        val unwrapped = if (value is Variant<*>) value.value else value
        fun number(): Float = (unwrapped as? Number)?.toFloat()?.takeIf(Float::isFinite)
            ?: throw InvalidMethodArgument("$propertyName requires a finite number")
        when (propertyName) {
            "Volume" -> player.setVolume(number())
            "Rate" -> number().let { if (it == 0f) player.pause() else player.setSpeed(it) }
            "Shuffle" -> {
                val requested = unwrapped as? Boolean ?: throw InvalidMethodArgument("Shuffle requires a boolean")
                if (player.state.value.shuffle != requested) player.toggleShuffle()
            }
            "LoopStatus" -> {
                val requested = when (unwrapped) { "None" -> 0; "Track" -> 1; "Playlist" -> 2; else -> throw InvalidMethodArgument("Invalid loop status") }
                player.setRepeat(requested)
            }
            else -> throw PropertyReadOnly(propertyName)
        }
    }
    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = properties(interfaceName)

    private fun properties(name: String): Map<String, Variant<*>> {
        val state = player.state.value
        if (state.isPlaying) explicitlyStopped = false
        return if (name == ROOT) mapOf(
            "CanQuit" to Variant(true), "CanRaise" to Variant(true), "HasTrackList" to Variant(false),
            "Identity" to Variant("BitChord Desktop Reworked"), "DesktopEntry" to Variant("BitChordReworked"),
            "SupportedUriSchemes" to Variant(listOf("file", "https", "http"), "as"),
            "SupportedMimeTypes" to Variant(listOf("audio/mpeg", "audio/flac", "audio/ogg", "audio/mp4", "audio/wav", "audio/x-matroska"), "as"),
        ) else if (name == PLAYER) mapOf(
            "PlaybackStatus" to Variant(if (state.song == null || explicitlyStopped || (!state.isPlaying && !state.isLoading && state.durationMs > 0 && state.positionMs >= state.durationMs)) "Stopped" else if (state.isPlaying) "Playing" else "Paused"),
            "LoopStatus" to Variant(when (state.repeat) { 1 -> "Track"; 2 -> "Playlist"; else -> "None" }),
            "Rate" to Variant(state.speed.toDouble()), "Shuffle" to Variant(state.shuffle),
            "Volume" to Variant(state.volume.toDouble()), "Position" to Variant(state.positionMs * 1000),
            "MinimumRate" to Variant(0.5), "MaximumRate" to Variant(2.0),
            "CanGoNext" to Variant(canNext(state)),
            "CanGoPrevious" to Variant(canPrevious(state)),
            "CanPlay" to Variant(state.song != null), "CanPause" to Variant(state.song != null),
            "CanSeek" to Variant(state.durationMs > 0), "CanControl" to Variant(true),
            "Metadata" to Variant(metadata(state), "a{sv}"),
        ) else throw UnknownInterface(name)
    }

    private fun metadata(state: PlaybackState): Map<String, Variant<*>> = buildMap {
        val song = state.song ?: return@buildMap
        put("mpris:trackid", Variant(trackPath(song)))
        put("mpris:length", Variant(state.durationMs * 1000))
        put("xesam:title", Variant(song.title))
        put("xesam:artist", Variant(song.artists.map { it.name }.ifEmpty { listOf(song.artist) }, "as"))
        song.albumName?.let { put("xesam:album", Variant(it)) }
        song.thumbnailUrl?.let { put("mpris:artUrl", Variant(it)) }
        val url = song.localUri ?: song.localPath?.let { runCatching { Path.of(it).toUri().toString() }.getOrNull() }
            ?: song.videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let { "https://music.youtube.com/watch?v=$it" }
        url?.let { put("xesam:url", Variant(it)) }
    }
    override fun close() { scope.cancel(); runCatching { connection?.close() }; connection = null }
    companion object {
        const val PATH = "/org/mpris/MediaPlayer2"
        private const val ROOT = "org.mpris.MediaPlayer2"
        private const val PLAYER = "$ROOT.Player"
        private fun canNext(state: PlaybackState) = state.song != null && (state.queueIndex < state.queue.lastIndex || state.repeat == 2 || (state.shuffle && state.queue.size > 1))
        private fun canPrevious(state: PlaybackState) = state.song != null && (state.queueIndex > 0 || state.positionMs > 0 || state.repeat == 2)
        private fun trackPath(song: Song?) = DBusPath("/com/music/bitchord/reworked/track/" + (song?.queueEntryId ?: song?.videoId ?: "none").replace(Regex("[^A-Za-z0-9_]"), "_"))
    }
}
