package com.music.bitchord.reworked

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.listentogether.*
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.scrobbling.ListenBrainzManager
import com.music.bitchord.data.scrobbling.primaryArtist
import com.music.bitchord.data.stats.ArtistFacts
import com.music.bitchord.data.stats.ListeningStats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.net.URI
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

@Serializable
internal data class MobileIntegrationPreferences(
    val lastFmEndpoint: String = LastFM.DEFAULT_API_ENDPOINT,
    val lastFmApiKey: String = "",
    val lastFmSecret: String = "",
    val lastFmSession: String = "",
    val lastFmAccount: String = "",
    val lastFmEnabled: Boolean = false,
    val listenBrainzToken: String = "",
    val listenBrainzEnabled: Boolean = false,
    val nowPlaying: Boolean = true,
    val primaryArtistOnly: Boolean = false,
    val replayEnabled: Boolean = true,
    val replayGenres: Boolean = false,
    val meteredConnection: Boolean = false,
)

internal object MobileIntegrationSettings {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _values = MutableStateFlow(DesktopStore.readText("integrations.json")?.let {
        runCatching { json.decodeFromString<MobileIntegrationPreferences>(it) }.getOrNull()
    } ?: MobileIntegrationPreferences())
    val values = _values.asStateFlow()

    @Synchronized
    fun update(change: (MobileIntegrationPreferences) -> MobileIntegrationPreferences) {
        val next = change(_values.value)
        // Validate before persistence so a mistyped custom endpoint cannot break startup.
        LastFM.normalizeEndpoint(next.lastFmEndpoint)
        DesktopStore.writeText("integrations.json", json.encodeToString(next))
        _values.value = next
    }
}

/**
 * Fresh JVM service lifecycle around the original mobile party, scrobbling and Replay clients.
 * Playback observation uses the audio adapter's delivered state. Seeking contributes no time.
 */
internal class MobileIntegrations(val session: MobileSession) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val party = ListenTogether.state
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val authenticationPending = MutableStateFlow(false)
    private var lastFmToken: String? = null
    private val ledger = ListeningLedger()
    private val extraMetadata = mutableMapOf<String, Song>()
    private val requestedMetadata = mutableSetOf<String>()
    val discord = MobileDiscordService(session.player.state)
    private val partySync: MobilePartySync
    private var lastFlushMs = 0L
    private var closed = false

    init {
        ListenTogether.init()
        ListeningStats.init(DesktopStore.root.toFile())
        ArtistFacts.init(DesktopStore.root.toFile())
        partySync = MobilePartySync(session.player, scope, ::notice)
        scope.launch {
            MobileIntegrationSettings.values.collect { settings ->
                LastFM.configure(settings.lastFmEndpoint, settings.lastFmApiKey,
                    settings.lastFmSecret, settings.lastFmSession.takeIf(String::isNotBlank))
                ListenTogether.meteredConnection = settings.meteredConnection
            }
        }
        scope.launch { session.autoplay.collect { ListenTogether.autoplayEnabled = it } }
        scope.launch {
            session.signedIn.collectLatest { signedIn ->
                if (signedIn) refreshPartyIdentity() else {
                    ListenTogether.setAccount(null)
                    if (party.value.inParty) ListenTogether.leaveParty()
                }
            }
        }
        scope.launch {
            session.player.state.collect { state ->
                val sample = ledger.sample(state, System.nanoTime() / 1_000_000L, System.currentTimeMillis())
                sample?.let { record(it, state) }
                if (!state.isPlaying) ListeningStats.flush()
            }
        }
    }

    fun notice(text: String) { message.value = text; session.notice(text) }

    private suspend fun refreshPartyIdentity(): Boolean {
        if (!session.signedIn.value) return false
        val account = YtMusicRepository.account().getOrNull() ?: return false
        // Identity comes from the authenticated mobile account endpoint. Raw identifiers never
        // leave this client: the unchanged mobile transport hashes this value before joining.
        val accountKey = account.email.takeIf(String::isNotBlank) ?: return false
        ListenTogether.setAccount(ListenTogether.AccountIdentity(accountKey, account.name, account.thumbnailUrl))
        return true
    }

    fun createParty(nickname: String, members: Int) = perform {
        check(refreshPartyIdentity()) { "Sign in to YouTube Music before creating a party." }
        ListenTogether.setNickname(nickname)
        val code = ListenTogether.createParty(nickname, members.coerceIn(2, 5)).getOrThrow()
        message.value = "Party $code is ready. Share its code with your friends."
    }

    fun joinParty(invite: String, nickname: String) = perform {
        check(refreshPartyIdentity()) { "Sign in to YouTube Music before joining a party." }
        val code = parsePartyCode(invite) ?: error("Enter a six-character party code or a BitChord invite link.")
        ListenTogether.setNickname(nickname)
        if (party.value.inParty) {
            if (party.value.code == code) { message.value = "You are already in party $code."; return@perform }
            val server = ListenTogether.activePartyServerBase() ?: ListenTogether.resolveSwitchTarget(null, ListenTogether.customServerUrl.value)
            message.value = when (val result = ListenTogether.switchPartyWithRecovery(server, code, nickname)) {
                is ListenTogether.SwitchPartyResult.Success -> "Joined party ${result.partyCode}."
                is ListenTogether.SwitchPartyResult.TargetFailedRecovered -> "${result.targetError} You are still in party ${result.partyCode}."
                is ListenTogether.SwitchPartyResult.TargetFailedNoParty -> result.targetError
            }
        } else {
            ListenTogether.joinParty(code, nickname).getOrThrow()
            message.value = "Joined party $code."
        }
    }

    fun leaveParty() = perform { ListenTogether.leaveParty(); message.value = "You left the party." }

    fun savePartyServer(value: String) = perform {
        val parsed = ListenTogether.parseAndNormalizeServerUrl(value)
        require(parsed is ServerUrlValidationResult.Valid) { "Enter an HTTP or HTTPS server address without a query or fragment." }
        val result = ListenTogether.setCustomServerUrl(parsed.normalizedUrl)
        message.value = when (result) {
            is ServerConnectionState.CustomOnline, is ServerConnectionState.DefaultOnline -> "Party server connected."
            is ServerConnectionState.CustomFallback -> "The custom server is unavailable; the configured default is connected."
            else -> "Address saved. The party server is not reachable yet."
        }
    }

    fun startLastFmSignIn() = perform {
        val settings = MobileIntegrationSettings.values.value
        require(settings.lastFmApiKey.isNotBlank() && settings.lastFmSecret.isNotBlank()) { "Save your API key and API secret first." }
        LastFM.configure(settings.lastFmEndpoint, settings.lastFmApiKey, settings.lastFmSecret,
            settings.lastFmSession.takeIf(String::isNotBlank))
        val token = LastFM.getToken().getOrThrow().token
        lastFmToken = token
        authenticationPending.value = true
        withContext(Dispatchers.IO) { Desktop.getDesktop().browse(URI(LastFM.getAuthUrl(token))) }
        message.value = "Approve BitChord in your browser, then select Complete sign in."
    }

    fun finishLastFmSignIn() = perform {
        val token = lastFmToken ?: error("Start browser sign in first.")
        val account = LastFM.getSession(token).getOrThrow().session
        MobileIntegrationSettings.update { it.copy(lastFmSession = account.key, lastFmAccount = account.name, lastFmEnabled = true) }
        lastFmToken = null; authenticationPending.value = false
        message.value = "Scrobbling connected as ${account.name}."
    }

    fun disconnectLastFm() {
        lastFmToken = null; authenticationPending.value = false
        MobileIntegrationSettings.update { it.copy(lastFmSession = "", lastFmAccount = "", lastFmEnabled = false) }
    }

    fun perform(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; message.value = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message.value = error.message ?: "The request could not be completed." }
            finally { busy.value = false }
        }
    }

    private fun enriched(song: Song): Song {
        extraMetadata[song.videoId]?.let { extra ->
            return song.copy(artistId = song.artistId ?: extra.artistId,
                albumId = song.albumId ?: extra.albumId, albumName = song.albumName ?: extra.albumName)
        }
        if (song.localUri == null && song.videoId.length == 11 && requestedMetadata.add(song.videoId)) {
            scope.launch { YtMusicRepository.trackLinks(song.videoId).getOrNull()?.let { extraMetadata[song.videoId] = it } }
        }
        return song
    }

    private fun record(sample: ListeningSample, state: PlaybackState) {
        val config = MobileIntegrationSettings.values.value
        val run = sample.listen
        val song = enriched(run.song)
        val duration = state.durationMs.takeIf { it > 0 } ?: song.durationMillis()
        if (config.replayEnabled && sample.elapsedMs > 0) {
            val threshold = if (duration > 0) min(duration / 2, 240_000L).coerceAtLeast(30_000L) else 30_000L
            val counts = !run.replayCounted && run.playedMs >= threshold
            if (counts) run.replayCounted = true
            ListeningStats.record(song, sample.elapsedMs, counts)
            if (sample.monotonicMs - lastFlushMs > 30_000L) {
                lastFlushMs = sample.monotonicMs; ListeningStats.flush()
            }
        }
        if (sample.becameAudible && config.nowPlaying) {
            if (config.lastFmEnabled && config.lastFmSession.isNotBlank()) scope.launch {
                LastFM.updateNowPlaying(if (config.primaryArtistOnly) song.artist.primaryArtist() else song.artist,
                    song.title, song.albumName, duration = duration.takeIf { it > 0 }?.div(1000)?.toInt())
                    .onFailure { if (it is CancellationException) throw it else message.value = "Last.fm: ${it.message}" }
            }
            if (config.listenBrainzEnabled && config.listenBrainzToken.isNotBlank()) scope.launch {
                ListenBrainzManager.submitPlayingNow(config.listenBrainzToken, song, state.positionMs,
                    duration.takeIf { it > 0 }, config.primaryArtistOnly)
            }
        }
        // The mobile Last.fm defaults: longer than 30s, half the track or three minutes.
        if (duration > 30_000L && run.playedMs >= min(duration / 2, 180_000L)) {
            if (!run.lastFmSubmitted && config.lastFmEnabled && config.lastFmSession.isNotBlank()) {
                run.lastFmSubmitted = true
                scope.launch {
                    LastFM.scrobble(if (config.primaryArtistOnly) song.artist.primaryArtist() else song.artist,
                        song.title, run.startedAtMs / 1000, song.albumName, duration = (duration / 1000).toInt())
                        .onFailure { if (it is CancellationException) throw it else message.value = "Last.fm: ${it.message}" }
                }
            }
            if (!run.listenBrainzSubmitted && config.listenBrainzEnabled && config.listenBrainzToken.isNotBlank()) {
                run.listenBrainzSubmitted = true
                scope.launch {
                    val ok = ListenBrainzManager.submitFinished(config.listenBrainzToken, song, run.startedAtMs,
                        System.currentTimeMillis(), duration, config.primaryArtistOnly)
                    if (!ok) message.value = "ListenBrainz could not submit this listen. Check the token and connection."
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        partySync.close()
        discord.close()
        scope.cancel()
        // Both writers use daemon workers. Give them a bounded exit window before the JVM
        // closes; a failed network release still falls back to the server's disconnect grace.
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(2_000L) {
                coroutineScope {
                    launch { ListeningStats.flushForShutdown() }
                    launch { ListenTogether.leaveParty() }
                }
            }
        }
    }
}

internal fun parsePartyCode(value: String): String? {
    val text = value.trim()
    return JamInvite.parse(text) ?: text.uppercase().takeIf { it.length == 6 && it.all(Char::isLetterOrDigit) }
}

/** Wall-clock accounting adapted from the mobile ListeningRecorder; positions never add time. */
internal class ListeningLedger {
    private var current: ListeningRun? = null
    private var previousAt = 0L
    private var wasAudible = false
    private var previousPosition = 0L
    private var previousDiscontinuity = 0L

    fun sample(state: PlaybackState, monotonicMs: Long, epochMs: Long): ListeningSample? {
        val song = state.song
        if (song == null) { current = null; wasAudible = false; previousAt = monotonicMs; return null }
        val identity = song.queueEntryId ?: song.videoId
        val looped = current?.identity == identity && state.discontinuity != previousDiscontinuity &&
            state.durationMs > 0 && previousPosition >= state.durationMs - 1_000 && state.positionMs < 1_000
        val changed = current?.identity != identity || looped
        if (changed) current = ListeningRun(identity, song, epochMs)
        val run = current ?: return null
        run.song = song
        val audible = state.isPlaying && !state.isLoading
        val step = if (!changed && wasAudible && audible) (monotonicMs - previousAt).coerceIn(0, 2_000) else 0L
        run.playedMs += step
        val began = audible && (!wasAudible || changed)
        previousAt = monotonicMs; previousPosition = state.positionMs
        previousDiscontinuity = state.discontinuity; wasAudible = audible
        return ListeningSample(run, step, began, monotonicMs)
    }
}

internal class ListeningRun(val identity: String, var song: Song, val startedAtMs: Long) {
    var playedMs: Long = 0
    var replayCounted = false
    var lastFmSubmitted = false
    var listenBrainzSubmitted = false
}
internal data class ListeningSample(val listen: ListeningRun, val elapsedMs: Long, val becameAudible: Boolean, val monotonicMs: Long)

/**
 * New Java Sound binding for the original mobile party protocol. Controls wait for the server
 * echo; the server's monotonic anchor owns start/seek. Mobile drift limits are kept unchanged.
 */
internal class MobilePartySync(
    private val player: DesktopPlayback,
    private val scope: CoroutineScope,
    private val notice: (String) -> Unit,
) : AutoCloseable {
    private val jobs = mutableListOf<Job>()
    private var personal: PlaybackState? = null
    private var partyCode: String? = null
    private var rows = emptyList<Song>()
    private var queueSeq = -1L
    private var appliedSeq = -1L
    private var lastAnchor: Pair<Long, Long>? = null
    private var seedSent = false
    private var driftStrikes = 0
    private var driftCooldownUntil = 0L
    private var completedSeq = -1L
    private var localPaused = false
    private var startJob: Job? = null
    private var autoplaySeed: String? = null

    init {
        player.commandInterceptor = ::intercept
        player.completionInterceptor = {
            val party = ListenTogether.state.value
            if (party.inParty) {
                scope.launch {
                    if (party.you?.isHost == true && completedSeq != party.playback.seq) {
                        completedSeq = party.playback.seq; ListenTogether.next()
                    }
                }
                true
            } else false
        }
        jobs += scope.launch { ListenTogether.state.collect { reconcile(it) } }
        jobs += scope.launch {
            ListenTogether.state.map { party ->
                AutoplayKey(party.code, party.you?.isHost == true,
                    party.connection == ListenTogether.Connection.LIVE,
                    party.playback.autoplayEnabled, party.playback.track?.videoId, party.queue.seq)
            }.distinctUntilChanged().collectLatest { key ->
                val seed = key.trackId ?: return@collectLatest
                val identity = "${key.code}:$seed"
                val party = ListenTogether.state.value
                val at = partyQueueIndexOf(party.queue, party.playback, seed)
                if (!key.host || !key.live || !key.enabled || key.code == null ||
                    party.queue.items.size - at - 1 > 4 || autoplaySeed == identity) return@collectLatest
                val radio = YtMusicRepository.radio(seed).getOrNull() ?: return@collectLatest
                val current = ListenTogether.state.value
                if (current.code != key.code || current.you?.isHost != true ||
                    !current.playback.autoplayEnabled || current.playback.track?.videoId != seed) return@collectLatest
                val known = current.queue.items.map(PartyTrack::videoId).toSet()
                val next = radio.filter { it.canShare() && it.videoId !in known }.distinctBy(Song::videoId).take(25)
                autoplaySeed = identity
                if (next.isNotEmpty()) ListenTogether.queueAdd(next.map { it.copy(queueTier = QueueTier.AUTOPLAY).partyTrack() })
            }
        }
        jobs += scope.launch {
            while (isActive) {
                delay(700L)
                if (ListenTogether.state.value.inParty) ListenTogether.ensureConnected()
                reconcile(ListenTogether.state.value)
            }
        }
    }

    private fun intercept(command: PlaybackCommand): Boolean {
        val party = ListenTogether.state.value
        if (!party.inParty) return false
        scope.launch { sendCommand(command, ListenTogether.state.value) }
        return true
    }

    private fun sendCommand(command: PlaybackCommand, party: ListenTogether.State) {
        if (!party.inParty) return
        if (party.controlsLocked) {
            // Mobile host-only semantics still let a listener silence their own device.
            if (command == PlaybackCommand.Pause) {
                localPaused = true; player.withRemoteCommands { player.pause() }; return
            }
            if (command == PlaybackCommand.Resume) { localPaused = false; reconcile(party); return }
            notice("Playback controls are held by the party host."); return
        }
        localPaused = false
        if (party.connection != ListenTogether.Connection.LIVE) {
            notice("The party is reconnecting. Controls will be available when it reconnects."); return
        }
        when (command) {
            is PlaybackCommand.PlaySongs -> {
                val selected = command.songs.getOrNull(command.index)
                if (selected == null || !selected.canShare()) { notice("Only online tracks can be shared with a party."); return }
                val shareable = command.songs.filter { it.canShare() }
                val index = command.songs.take(command.index).count { it.canShare() }
                ListenTogether.setQueue(shareable.map(Song::partyTrack), index)
                ListenTogether.setTrack(selected.partyTrack(), 0, true)
            }
            PlaybackCommand.Pause -> ListenTogether.pause(player.state.value.positionMs)
            PlaybackCommand.Resume -> { ListenTogether.setAwaitingStart(true); ListenTogether.play(player.state.value.positionMs) }
            PlaybackCommand.Next -> ListenTogether.next()
            PlaybackCommand.Previous -> ListenTogether.previous()
            is PlaybackCommand.Seek -> ListenTogether.seek(command.ms.coerceAtLeast(0))
            is PlaybackCommand.Add -> if (command.song.canShare()) ListenTogether.queueAdd(listOf(command.song.partyTrack()), command.playNext)
                else notice("A file on this device cannot be shared with the party.")
            is PlaybackCommand.Jump -> party.queue.items.getOrNull(command.index)?.let {
                ListenTogether.setQueue(party.queue.items, command.index)
                ListenTogether.setTrack(it, 0, true)
            }
            is PlaybackCommand.Remove -> party.queue.items.getOrNull(command.index)?.let { ListenTogether.queueRemove(it.videoId) }
            is PlaybackCommand.Move -> ListenTogether.queueMove(command.from, command.to, party.queue.items.getOrNull(command.from)?.videoId)
            PlaybackCommand.Clear -> ListenTogether.queueClear()
            PlaybackCommand.ToggleShuffle, PlaybackCommand.CycleRepeat, is PlaybackCommand.SetSpeed ->
                notice("The party uses one shared running order at normal speed.")
        }
    }

    private fun reconcile(party: ListenTogether.State) {
        if (party.code != partyCode) {
            if (partyCode == null && party.inParty) personal = player.state.value
            if (partyCode != null && !party.inParty) restorePersonal()
            partyCode = party.code; rows = emptyList(); queueSeq = -1; appliedSeq = -1
            seedSent = false; lastAnchor = null; localPaused = false; completedSeq = -1; autoplaySeed = null
        }
        if (!party.inParty) return
        player.withRemoteCommands { if (player.state.value.speed != 1f) player.setSpeed(1f) }
        if (!party.controlsLocked) localPaused = false
        if (party.connection != ListenTogether.Connection.LIVE) return
        val target = party.playback
        if (target.track == null) {
            if (party.you?.isHost == true && !seedSent) {
                val own = personal ?: player.state.value
                val selected = own.song
                if (selected != null && selected.canShare()) {
                    seedSent = true
                    val list = own.queue.filter { it.canShare() }
                    val index = own.queue.take(own.queueIndex.coerceAtLeast(0)).count { it.canShare() }
                    ListenTogether.setQueue(list.map(Song::partyTrack), index)
                    ListenTogether.setTrack(selected.partyTrack(), own.positionMs, own.isPlaying)
                }
            } else if (seedSent) player.withRemoteCommands { player.clearQueue() }
            return
        }
        seedSent = true
        // A state may arrive before its separate queue frame. Wait for the matching queue.
        if (target.queueSeq != party.queue.seq && party.queue.items.isNotEmpty()) return
        val track = target.track ?: return
        val items = party.queue.items.ifEmpty { listOf(track) }
        val index = partyQueueIndexOf(party.queue, target, track.videoId).takeIf { it in items.indices } ?: 0
        if (queueSeq != party.queue.seq || rows.isEmpty() || rows.map(Song::videoId) != items.map(PartyTrack::videoId)) {
            rows = reconcilePartyRows(rows, items)
            queueSeq = party.queue.seq
        }
        val before = player.state.value
        val changedTrack = before.song?.queueEntryId != rows[index].queueEntryId
        if (changedTrack || before.queue != rows || before.queueIndex != index) {
            player.withRemoteCommands { player.replaceQueue(rows, index); if (changedTrack) player.pause() }
        }
        if (!party.clockSynced) return
        val position = ListenTogether.partyPositionMs() ?: return
        val wait = ListenTogether.msUntilStart()
        val current = player.state.value
        val anchor = target.positionMs to target.anchorMs
        val newAnchor = anchor != lastAnchor
        val newState = target.seq != appliedSeq
        val drift = current.positionMs - position
        val now = System.nanoTime() / 1_000_000
        player.withRemoteCommands {
            if (localPaused || !target.isPlaying || wait > 0) {
                if (current.isPlaying) player.pause()
                if (abs(drift) > 400L || changedTrack) player.seek(position)
            } else {
                if (changedTrack || (newState && newAnchor && abs(drift) > 120L)) {
                    player.seek(position); driftStrikes = 0
                } else if (!current.isLoading && now > driftCooldownUntil && abs(drift) > 1_200L) {
                    if (++driftStrikes >= 2) { player.seek(position); driftStrikes = 0; driftCooldownUntil = now + 6_000L }
                } else if (abs(drift) <= 1_200L) driftStrikes = 0
                if (!current.isPlaying) player.resume()
            }
        }
        if (newState || changedTrack) {
            startJob?.cancel()
            if (wait > 0 && !localPaused) {
                ListenTogether.setAwaitingStart(true)
                startJob = scope.launch { delay(wait); reconcile(ListenTogether.state.value) }
            }
        }
        if (wait <= 0 || !target.isPlaying || localPaused) ListenTogether.setAwaitingStart(false)
        appliedSeq = target.seq; lastAnchor = anchor
    }

    private fun restorePersonal() {
        startJob?.cancel(); ListenTogether.setAwaitingStart(false)
        personal?.let { own ->
            player.withRemoteCommands {
                player.replaceQueue(own.queue, own.queueIndex)
                player.seek(own.positionMs); player.setSpeed(own.speed)
                if (own.isPlaying) player.resume() else player.pause()
            }
        }
        personal = null
    }

    override fun close() {
        jobs.forEach(Job::cancel); startJob?.cancel()
        player.commandInterceptor = null; player.completionInterceptor = null
    }

    private data class AutoplayKey(val code: String?, val host: Boolean, val live: Boolean,
        val enabled: Boolean, val trackId: String?, val queueSeq: Long)
}

private fun Song.canShare() = localUri == null && localPath == null && videoId.isNotBlank()
internal fun Song.partyTrack() = PartyTrack(videoId, title, artist, thumbnailUrl,
    durationMillis().takeIf { it > 0 }, queueTier == QueueTier.AUTOPLAY)

/** Occurrence-aware identity survives queue edits, including repeated copies of one song. */
internal fun reconcilePartyRows(previous: List<Song>, tracks: List<PartyTrack>): List<Song> {
    val existing = previous.groupBy(Song::videoId).mapValues { ArrayDeque(it.value) }
    return tracks.map { track ->
        val old = existing[track.videoId]?.removeFirstOrNull()
        val seconds = track.durationMs?.div(1000)
        Song(track.videoId, track.title, track.artist, track.thumbnailUrl,
            durationText = seconds?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
            queueEntryId = old?.queueEntryId ?: "party:${UUID.randomUUID()}",
            queueTier = if (track.fromAutoplay) QueueTier.AUTOPLAY else QueueTier.USER_QUEUE,
            playbackSource = "Listen Together")
    }
}
