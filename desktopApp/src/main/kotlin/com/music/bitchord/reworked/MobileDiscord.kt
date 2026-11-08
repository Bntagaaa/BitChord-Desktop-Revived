package com.music.bitchord.reworked

import com.music.bitchord.data.discord.DiscordApplication
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** Settings and lifecycle are new JVM boundaries. Presence fields and gateway behavior are
 * ported from app/.../data/discord/DiscordRPC.kt and playback/PlaybackService.kt in mobile v1.9.1. */
@Serializable
internal data class MobileDiscordPreferences(
    val token: String = "",
    val enabled: Boolean = false,
    val username: String = "",
    val displayName: String = "",
    val avatar: String? = null,
    val useDetails: Boolean = false,
    val showAudioQuality: Boolean = true,
    val advanced: Boolean = false,
    val status: String = "online",
    val activityType: String = "listening",
    val activityName: String = "",
    val button1Text: String = "",
    val button1Visible: Boolean = true,
    val button2Text: String = "",
    val button2Visible: Boolean = true,
) {
    override fun toString() = "MobileDiscordPreferences(enabled=$enabled, connected=${token.isNotBlank()})"
}

internal object MobileDiscordSettingsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(DesktopStore.readText("discord.json")?.let {
        runCatching { json.decodeFromString<MobileDiscordPreferences>(it) }.getOrNull()
    } ?: MobileDiscordPreferences())
    val values = mutable.asStateFlow()

    @Synchronized
    fun update(change: (MobileDiscordPreferences) -> MobileDiscordPreferences) {
        val next = change(mutable.value)
        require(next.status in setOf("online", "idle", "dnd")) { "Choose a supported Discord status." }
        require(next.activityType in setOf("listening", "playing", "watching", "competing")) { "Choose a supported Discord activity." }
        DesktopStore.writeText("discord.json", json.encodeToString(next))
        mutable.value = next
    }

    fun connect(rawToken: String) {
        val token = rawToken.trim().removeSurrounding("\"")
        require(token.length in 16..8192 && token.none(Char::isWhitespace)) { "Enter a valid Discord account token." }
        // An account change never silently starts publishing. The listener turns on presence.
        update { it.copy(token = token, enabled = false, username = "", displayName = "", avatar = null) }
    }

    fun disconnect() = update { it.copy(token = "", enabled = false, username = "", displayName = "", avatar = null) }
}

/** Original mobile compatibility header, with Android Build/Base64 calls replaced by JVM data.
 * The account token is passed only to the existing Discord clients, never to profile logging. */
internal object MobileDiscordFingerprint {
    private const val MOBILE_BUILD = 314013
    val device: String = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
    val userAgent = "Discord-Android/$MOBILE_BUILD;RNA"
    val properties: String by lazy {
        val body = JSONObject().apply {
            put("os", "Android"); put("browser", "Discord Android"); put("device", device)
            put("system_locale", Locale.getDefault().toString())
            put("client_version", "314.13 - Stable"); put("release_channel", "googleRelease")
            put("device_vendor_id", UUID.randomUUID().toString())
            put("client_uuid", UUID.randomUUID().toString()); put("client_launch_id", UUID.randomUUID().toString())
            put("os_version", System.getProperty("os.version")); put("os_sdk_version", "0")
            put("client_build_number", MOBILE_BUILD); put("client_event_source", JSONObject.NULL); put("design_id", 0)
        }
        Base64.getEncoder().encodeToString(body.toString().toByteArray(Charsets.UTF_8))
    }
}

internal const val DISCORD_BUTTON_LISTEN = "Listen on YouTube Music"
internal const val DISCORD_BUTTON_PROJECT = "Visit BitChord"
internal const val DISCORD_PROJECT_URL = "https://github.com/kushagrasinghx/BitChord"

internal data class MobileDiscordPresence(
    val name: String, val details: String, val artist: String, val artwork: String?,
    val watchUrl: String?, val quality: String?, val buttons: List<Pair<String, String>>,
    val type: KizzyRPC.Type, val display: KizzyRPC.StatusDisplayType, val status: String,
    val sinceMs: Long, val startMs: Long, val endMs: Long?,
)

internal fun discordTemplate(text: String, song: Song): String = text
    .replace("{song_name}", song.title).replace("{artist_name}", song.artist)
    .replace("{album_name}", song.albumName.orEmpty())

/** The mobile card's premium line is always based on the opened decoder, not a source label. */
internal fun discordAudioQualityLine(info: DecodedAudioInfo?): String? {
    info ?: return null
    if (!info.lossless) return null
    return buildList {
        add(if ((info.bitDepth ?: 0) > 16 || info.sampleRate > 48_000) "Hi-Res Lossless" else "Lossless")
        add(info.codec.uppercase(Locale.ROOT))
        info.bitrate?.takeIf { it > 0 }?.let { add("${it / 1_000} kbps") }
        info.bitDepth?.takeIf { it > 0 }?.let { add("$it-bit") }
        if (info.sampleRate > 0) add("${String.format(Locale.ROOT, "%.1f", info.sampleRate / 1000.0).removeSuffix(".0")} kHz")
        if (info.channels > 0) add(when (info.channels) { 1 -> "Mono"; 2 -> "Stereo"; else -> "${info.channels} ch" })
    }.joinToString(" · ").take(128).trimEnd()
}

internal fun discordPresence(state: PlaybackState, settings: MobileDiscordPreferences, now: Long): MobileDiscordPresence? {
    val song = state.song ?: return null
    if (!state.isPlaying || state.isLoading) return null
    val speed = state.speed.takeIf { it.isFinite() && it > 0 } ?: 1f
    val position = state.positionMs.coerceAtLeast(0).let { if (state.durationMs > 0) it.coerceAtMost(state.durationMs) else it }
    val watch = song.videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) && song.localUri == null && song.localPath == null }
        ?.let { "https://music.youtube.com/watch?v=$it" }
    val buttons = buildList {
        if (settings.button1Visible && watch != null) add(discordTemplate(settings.button1Text.ifBlank { DISCORD_BUTTON_LISTEN }, song).take(32) to watch)
        if (settings.button2Visible) add(discordTemplate(settings.button2Text.ifBlank { DISCORD_BUTTON_PROJECT }, song).take(32) to DISCORD_PROJECT_URL)
    }
    return MobileDiscordPresence(
        name = settings.activityName.ifBlank { "BitChord Desktop Reworked" }.take(128),
        details = (song.title + if (speed != 1f) " [${String.format(Locale.ROOT, "%.2fx", speed)}]" else "").take(128),
        artist = song.artist.take(128), artwork = song.artworkAt(480)?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        watchUrl = watch, quality = if (settings.showAudioQuality) discordAudioQualityLine(state.audioInfo) else null,
        buttons = buttons, type = when (settings.activityType) {
            "playing" -> KizzyRPC.Type.PLAYING; "watching" -> KizzyRPC.Type.WATCHING
            "competing" -> KizzyRPC.Type.COMPETING; else -> KizzyRPC.Type.LISTENING
        },
        display = if (settings.useDetails) KizzyRPC.StatusDisplayType.DETAILS else KizzyRPC.StatusDisplayType.STATE,
        status = settings.status, sinceMs = now, startMs = now - (position / speed).toLong(),
        endMs = state.durationMs.takeIf { it > 0 }?.let { now + ((it - position).coerceAtLeast(0) / speed).toLong() },
    )
}

internal interface MobileDiscordGateway {
    val connected: Boolean
    suspend fun publish(presence: MobileDiscordPresence)
    suspend fun clear()
    fun dispose()
}

private class OriginalMobileDiscordGateway(token: String) : MobileDiscordGateway {
    private val rpc = KizzyRPC(token, device = MobileDiscordFingerprint.device,
        userAgent = MobileDiscordFingerprint.userAgent, superPropertiesBase64 = MobileDiscordFingerprint.properties)
    override val connected get() = rpc.isRpcRunning()
    override suspend fun publish(p: MobileDiscordPresence) = rpc.setActivity(
        name = p.name, details = p.details, state = p.artist, detailsUrl = p.watchUrl,
        largeImage = p.artwork?.let(RpcImage::ExternalImage), smallImage = null,
        largeText = p.quality, smallText = null, buttons = p.buttons.takeIf { it.isNotEmpty() },
        type = p.type, statusDisplayType = p.display, since = p.sinceMs, startTime = p.startMs,
        endTime = p.endMs, applicationId = DiscordApplication.ID, status = p.status,
    )
    override suspend fun clear() = rpc.close()
    override fun dispose() = rpc.closeRPC()
}

/** A new JVM lifecycle around the mobile gateway. Clock ticks never create gateway traffic;
 * track/seek/rate/settings changes update the timestamp pair, while pause clears the card. */
internal class MobileDiscordService(
    playback: StateFlow<PlaybackState>,
    settings: StateFlow<MobileDiscordPreferences> = MobileDiscordSettingsStore.values,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val gatewayFactory: (String) -> MobileDiscordGateway = ::OriginalMobileDiscordGateway,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableStatus = MutableStateFlow("Presence is off")
    val status = mutableStatus.asStateFlow()
    private var gateway: MobileDiscordGateway? = null
    private var activeToken: String? = null
    private var presenceUp = false
    private var lastPublishMs = 0L

    private data class Snapshot(val playback: PlaybackState, val settings: MobileDiscordPreferences) {
        val key: List<Any?> get() = listOf(settings, playback.song, playback.isPlaying, playback.isLoading,
            playback.durationMs, playback.speed, playback.discontinuity,
            if (settings.showAudioQuality) discordAudioQualityLine(playback.audioInfo) else null)
    }

    private val worker = scope.launch {
        try {
            combine(playback, settings, ::Snapshot).distinctUntilChangedBy { it.key }.collectLatest { current ->
                val desiredToken = current.settings.token.takeIf { current.settings.enabled && it.isNotBlank() }
                if (desiredToken != activeToken) {
                    retire()
                    activeToken = desiredToken
                    gateway = desiredToken?.let(gatewayFactory)
                }
                val connection = gateway
                if (connection == null) {
                    mutableStatus.value = if (current.settings.token.isBlank()) "Connect an account to share your music" else "Presence is off"
                    return@collectLatest
                }
                val card = discordPresence(current.playback, current.settings, System.currentTimeMillis())
                if (card == null) {
                    if (presenceUp) {
                        presenceUp = false
                        withTimeoutOrNull(2_000L) {
                            try { connection.clear() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* The mobile gateway replays its clear on reconnect. */ }
                        }
                    }
                    mutableStatus.value = "Ready · waiting for playback"
                    return@collectLatest
                }
                // Coalesce quick seeks or typing without allowing ordinary progress ticks
                // to cancel a pending update. The original gateway owns network retries.
                val remaining = 750L - (System.nanoTime() / 1_000_000L - lastPublishMs)
                if (remaining > 0) delay(remaining)
                try {
                    presenceUp = true
                    connection.publish(card)
                    lastPublishMs = System.nanoTime() / 1_000_000L
                    mutableStatus.value = if (connection.connected) "Sharing this track on Discord" else "Connecting · presence will resume with the gateway"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableStatus.value = "Discord could not update your presence. Check your account and connection." }
            }
        } finally { retire() }
    }

    private suspend fun retire() = withContext(NonCancellable) {
        val previous = gateway
        gateway = null; activeToken = null
        if (previous != null) {
            if (presenceUp) withTimeoutOrNull(2_000L) { runCatching { previous.clear() } }
            runCatching { previous.dispose() }
        }
        presenceUp = false
    }

    suspend fun shutdown() { worker.cancelAndJoin(); scope.cancel() }
    override fun close() { runBlocking(Dispatchers.IO) { shutdown() } }
}
