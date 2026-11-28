package com.music.bitchord.desktop

import com.music.bitchord.data.connect.ConnectApiError
import com.music.bitchord.data.connect.ConnectClientFrame
import com.music.bitchord.data.connect.ConnectHostInfo
import com.music.bitchord.data.connect.ConnectPairLink
import com.music.bitchord.data.connect.ConnectPairRequest
import com.music.bitchord.data.connect.ConnectPairResponse
import com.music.bitchord.data.connect.ConnectPlaybackState
import com.music.bitchord.data.connect.ConnectProtocol
import com.music.bitchord.data.connect.ConnectQueueState
import com.music.bitchord.data.connect.ConnectServerFrame
import com.music.bitchord.data.connect.ConnectTrack
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.time.Duration.Companion.seconds

@Serializable
internal data class ConnectPairedClient(
    val clientId: String,
    val clientName: String,
    val platform: String = "android",
    val token: String,
    val pairedAtMs: Long = System.currentTimeMillis(),
)

internal data class ConnectConnectedClient(
    val sessionId: String,
    val clientId: String,
    val clientName: String,
    val remoteAddress: String,
    val connectedAtMs: Long,
)

/** Bridge from incoming LAN remote commands to the desktop playback engine/service. */
internal interface ConnectPlaybackDelegate {
    fun currentPlaybackState(): ConnectPlaybackState
    fun currentQueueState(): ConnectQueueState
    fun play()
    fun pause()
    fun togglePlayPause()
    fun seek(positionMs: Long)
    fun next()
    fun previous()
    fun setVolume(volume: Float)
    fun setRepeat(repeatMode: String)
    fun setShuffle(shuffle: Boolean)
    fun loadQueue(
        tracks: List<ConnectTrack>,
        index: Int,
        positionMs: Long,
        play: Boolean,
        repeatMode: String?,
    )
    fun updateUpcoming(tracks: List<ConnectTrack>)
    fun jumpTo(videoId: String, index: Int?, positionMs: Long, play: Boolean)
}

/**
 * Embedded HTTP + WebSocket server that exposes BitChord Desktop as a Spotify Connect-style
 * LAN playback receiver for BitChord Android.
 */
internal class DesktopConnectServer(
    private val deviceId: String,
    initialDeviceName: String,
    private val appVersion: String,
    private val preferredPort: Int = ConnectProtocol.DEFAULT_PORT,
    private val enableMdns: Boolean = true,
    initialPin: String? = null,
    private val onPinChanged: (String) -> Unit = {},
    initialPairedClients: List<ConnectPairedClient> = emptyList(),
    private val onPairedClientsChanged: (List<ConnectPairedClient>) -> Unit = {},
    private val delegate: ConnectPlaybackDelegate,
) {
    data class Snapshot(
        val running: Boolean = false,
        val port: Int = ConnectProtocol.DEFAULT_PORT,
        val deviceId: String = "",
        val deviceName: String = "",
        val pin: String = "",
        val addresses: List<String> = emptyList(),
        val mdnsActive: Boolean = false,
        val pairedClients: List<ConnectPairedClient> = emptyList(),
        val connectedClients: List<ConnectConnectedClient> = emptyList(),
        val error: String? = null,
    ) {
        val primaryAddress: String? get() = addresses.firstOrNull()
        val pairLink: String?
            get() = primaryAddress?.let { host ->
                ConnectPairLink(
                    host = host,
                    port = port,
                    pin = pin,
                    deviceId = deviceId,
                    deviceName = deviceName,
                ).toUriString()
            }
    }

    private class ActiveSession(
        val info: ConnectConnectedClient,
        val session: DefaultWebSocketServerSession,
        val sendMutex: Mutex = Mutex(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val random = SecureRandom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pairedLock = Any()
    private val paired = LinkedHashMap<String, ConnectPairedClient>().apply {
        initialPairedClients.forEach { put(it.clientId, it) }
    }
    private val sessions = ConcurrentHashMap<String, ActiveSession>()
    private val failedAttempts = ConcurrentHashMap<String, MutableList<Long>>()
    private val stateSeq = AtomicLong(0L)
    private val queueSeq = AtomicLong(0L)

    @Volatile private var deviceName: String = initialDeviceName.ifBlank { defaultDesktopDeviceName() }
    @Volatile private var currentPin: String = normalizePin(initialPin) ?: generatePin()
    @Volatile private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    @Volatile private var boundPort: Int = preferredPort
    private val mdnsInstances = mutableListOf<JmDNS>()
    private var heartbeatJob: Job? = null

    private val _snapshot = MutableStateFlow(
        Snapshot(
            running = false,
            port = preferredPort,
            deviceId = deviceId,
            deviceName = deviceName,
            pin = currentPin,
            pairedClients = initialPairedClients,
        ),
    )
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    suspend fun start() {
        if (server != null) return
        val addresses = discoverLocalIpv4Addresses()
        val started = startKtorServer(preferredPort)
        server = started.first
        boundPort = started.second
        val mdnsOk = if (enableMdns) startMdns(boundPort) else false
        _snapshot.update {
            it.copy(
                running = true,
                port = boundPort,
                deviceName = deviceName,
                pin = currentPin,
                addresses = addresses,
                mdnsActive = mdnsOk,
                error = null,
            )
        }
        startHeartbeat()
        DesktopTrackLog.log("connect: listening on :$boundPort (addresses=${addresses.joinToString()}, mdns=$mdnsOk)")
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        stopMdns()
        val active = sessions.values.toList()
        sessions.clear()
        active.forEach { entry ->
            runCatching {
                scope.launch {
                    runCatching {
                        entry.session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "server_stopped"))
                    }
                }
            }
        }
        val runningServer = server
        server = null
        if (runningServer != null) {
            runCatching { runningServer.stop(gracePeriodMillis = 250L, timeoutMillis = 1_000L) }
        }
        scope.cancel()
        _snapshot.update {
            it.copy(
                running = false,
                mdnsActive = false,
                connectedClients = emptyList(),
            )
        }
    }

    fun rotatePin(): String {
        val next = generatePin()
        currentPin = next
        onPinChanged(next)
        _snapshot.update { it.copy(pin = next) }
        return next
    }

    fun setPin(rawPin: String): Result<String> {
        val normalized = normalizePin(rawPin)
            ?: return Result.failure(IllegalArgumentException("PIN must be 4 to 6 digits."))
        currentPin = normalized
        onPinChanged(normalized)
        _snapshot.update { it.copy(pin = normalized) }
        return Result.success(normalized)
    }

    fun updateDeviceName(name: String) {
        val cleaned = name.trim().take(64).ifBlank { defaultDesktopDeviceName() }
        if (cleaned == deviceName) return
        deviceName = cleaned
        _snapshot.update { it.copy(deviceName = cleaned) }
        if (enableMdns && server != null) {
            scope.launch {
                stopMdns()
                val ok = startMdns(boundPort)
                _snapshot.update { it.copy(mdnsActive = ok) }
            }
        }
    }

    fun refreshAddresses() {
        val addresses = discoverLocalIpv4Addresses()
        _snapshot.update { it.copy(addresses = addresses) }
        if (enableMdns && server != null && !snapshot.value.mdnsActive && addresses.isNotEmpty()) {
            scope.launch {
                stopMdns()
                val ok = startMdns(boundPort)
                _snapshot.update { it.copy(mdnsActive = ok) }
            }
        }
    }

    fun revokeClient(clientId: String) {
        val updated = synchronized(pairedLock) {
            if (paired.remove(clientId) == null) return
            paired.values.toList()
        }
        onPairedClientsChanged(updated)
        val toClose = sessions.values.filter { it.info.clientId == clientId }
        toClose.forEach { entry ->
            sessions.remove(entry.info.sessionId)
            scope.launch {
                runCatching {
                    entry.session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "revoked"))
                }
            }
        }
        _snapshot.update {
            it.copy(
                pairedClients = updated,
                connectedClients = sessions.values.map(ActiveSession::info).sortedBy(ConnectConnectedClient::connectedAtMs),
            )
        }
    }

    fun notifyPlaybackChanged() {
        if (sessions.isEmpty()) return
        val frame = buildStateFrame(bumpSeq = true)
        broadcast(frame)
    }

    fun notifyQueueChanged() {
        if (sessions.isEmpty()) return
        val qSeq = queueSeq.incrementAndGet()
        val rawQueue = delegate.currentQueueState().copy(seq = qSeq)
        val now = System.currentTimeMillis()
        val queueFrame = ConnectServerFrame(
            type = ConnectProtocol.FRAME_QUEUE,
            serverMs = now,
            queue = rawQueue,
        )
        val stateFrame = buildStateFrame(bumpSeq = true)
        broadcast(queueFrame)
        broadcast(stateFrame)
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                if (sessions.isNotEmpty()) {
                    broadcast(buildStateFrame(bumpSeq = false))
                }
            }
        }
    }

    private suspend fun startKtorServer(
        port: Int,
    ): Pair<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>, Int> {
        fun create(bindPort: Int) = embeddedServer(CIO, host = "0.0.0.0", port = bindPort) {
            install(WebSockets) {
                pingPeriodMillis = 15_000L
                timeoutMillis = 30_000L
                maxFrameSize = MAX_WS_FRAME_BYTES
                masking = false
            }
            routing {
                get("/connect/info") {
                    val info = ConnectHostInfo(
                        protocolVersion = ConnectProtocol.VERSION,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        platform = "pc",
                        appVersion = appVersion,
                        port = boundPort,
                        pairedClientsCount = synchronized(pairedLock) { paired.size },
                    )
                    call.respondText(
                        json.encodeToString(info),
                        ContentType.Application.Json,
                        HttpStatusCode.OK,
                    )
                }

                post("/connect/pair") {
                    val remoteHost = call.request.origin.remoteHost
                    if (isRateLimited(remoteHost)) {
                        call.respondText(
                            json.encodeToString(ConnectApiError("rate_limited", "Too many failed pairing attempts. Try again shortly.")),
                            ContentType.Application.Json,
                            HttpStatusCode.TooManyRequests,
                        )
                        return@post
                    }
                    val bodyText = runCatching { call.receiveText() }.getOrNull().orEmpty()
                    if (bodyText.length > MAX_HTTP_BODY_BYTES) {
                        call.respondText(
                            json.encodeToString(ConnectApiError("payload_too_large", "Request body is too large.")),
                            ContentType.Application.Json,
                            HttpStatusCode.PayloadTooLarge,
                        )
                        return@post
                    }
                    val request = runCatching { json.decodeFromString<ConnectPairRequest>(bodyText) }.getOrNull()
                    if (request == null || request.clientId.isBlank() || request.clientName.isBlank()) {
                        call.respondText(
                            json.encodeToString(ConnectApiError("invalid_request", "Missing clientId, clientName, or pin.")),
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                        return@post
                    }
                    val submittedPin = request.pin.filter(Char::isDigit)
                    if (!constantTimeEquals(submittedPin, currentPin)) {
                        recordFailedAttempt(remoteHost)
                        call.respondText(
                            json.encodeToString(ConnectApiError("invalid_pin", "Incorrect pairing PIN.")),
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized,
                        )
                        return@post
                    }
                    val token = generateToken()
                    val entry = ConnectPairedClient(
                        clientId = request.clientId.trim().take(128),
                        clientName = request.clientName.trim().take(80),
                        platform = request.platform.trim().take(32).ifBlank { "android" },
                        token = token,
                        pairedAtMs = System.currentTimeMillis(),
                    )
                    val updated = synchronized(pairedLock) {
                        paired[entry.clientId] = entry
                        paired.values.toList()
                    }
                    onPairedClientsChanged(updated)
                    _snapshot.update { it.copy(pairedClients = updated) }
                    val response = ConnectPairResponse(
                        token = token,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        protocolVersion = ConnectProtocol.VERSION,
                    )
                    call.respondText(
                        json.encodeToString(response),
                        ContentType.Application.Json,
                        HttpStatusCode.OK,
                    )
                }

                webSocket("/connect/ws") {
                    val authHeader = call.request.headers[HttpHeaders.Authorization].orEmpty()
                    val bearer = authHeader.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                        ?.substringAfter(' ')
                        ?.trim()
                        .orEmpty()
                    val client = authenticateToken(bearer)
                    if (client == null) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                        return@webSocket
                    }
                    val sessionId = UUID.randomUUID().toString()
                    val remoteHost = call.request.origin.remoteHost
                    val active = ActiveSession(
                        info = ConnectConnectedClient(
                            sessionId = sessionId,
                            clientId = client.clientId,
                            clientName = client.clientName,
                            remoteAddress = remoteHost,
                            connectedAtMs = System.currentTimeMillis(),
                        ),
                        session = this,
                    )
                    sessions[sessionId] = active
                    publishConnectedClients()
                    try {
                        val now = System.currentTimeMillis()
                        val welcome = ConnectServerFrame(
                            type = ConnectProtocol.FRAME_WELCOME,
                            deviceId = deviceId,
                            deviceName = deviceName,
                            serverMs = now,
                            state = snapshotPlaybackState(now),
                            queue = delegate.currentQueueState().copy(seq = queueSeq.get()),
                        )
                        sendFrame(active, welcome)

                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()
                            if (text.length > MAX_WS_FRAME_BYTES) continue
                            val message = runCatching { json.decodeFromString<ConnectClientFrame>(text) }.getOrNull()
                                ?: continue
                            handleClientMessage(active, message)
                        }
                    } catch (_: CancellationException) {
                        // Normal shutdown
                    } catch (error: Exception) {
                        DesktopTrackLog.log("connect: ws session ended (${error.javaClass.simpleName})")
                    } finally {
                        sessions.remove(sessionId)
                        publishConnectedClients()
                    }
                }
            }
        }

        val engine = try {
            create(port).also { it.start(wait = false) }
        } catch (bind: BindException) {
            if (port == 0) throw bind
            DesktopTrackLog.log("connect: port $port in use, falling back to ephemeral port")
            create(0).also { it.start(wait = false) }
        }
        val actualPort = engine.engine.resolvedConnectors().firstOrNull()?.port ?: port
        return engine to actualPort
    }

    private suspend fun handleClientMessage(active: ActiveSession, message: ConnectClientFrame) {
        when (message.type) {
            ConnectProtocol.FRAME_PING -> {
                sendFrame(
                    active,
                    ConnectServerFrame(
                        type = ConnectProtocol.FRAME_PONG,
                        clientMs = message.clientMs,
                        serverMs = System.currentTimeMillis(),
                    ),
                )
            }
            ConnectProtocol.FRAME_SYNC -> {
                val now = System.currentTimeMillis()
                sendFrame(
                    active,
                    ConnectServerFrame(
                        type = ConnectProtocol.FRAME_QUEUE,
                        serverMs = now,
                        queue = delegate.currentQueueState().copy(seq = queueSeq.get()),
                    ),
                )
                sendFrame(active, buildStateFrame(bumpSeq = false))
            }
            ConnectProtocol.FRAME_CONTROL -> {
                handleControl(message)
            }
        }
    }

    private fun handleControl(message: ConnectClientFrame) {
        when (message.action) {
            ConnectProtocol.ACTION_PLAY -> delegate.play()
            ConnectProtocol.ACTION_PAUSE, ConnectProtocol.ACTION_STOP -> delegate.pause()
            ConnectProtocol.ACTION_TOGGLE_PLAY_PAUSE -> delegate.togglePlayPause()
            ConnectProtocol.ACTION_SEEK -> message.positionMs?.let { delegate.seek(it.coerceAtLeast(0L)) }
            ConnectProtocol.ACTION_NEXT -> delegate.next()
            ConnectProtocol.ACTION_PREVIOUS -> delegate.previous()
            ConnectProtocol.ACTION_SET_VOLUME -> message.volume?.let { delegate.setVolume(it.coerceIn(0f, 1f)) }
            ConnectProtocol.ACTION_SET_REPEAT -> message.repeatMode?.let { delegate.setRepeat(it) }
            ConnectProtocol.ACTION_SET_SHUFFLE -> message.shuffle?.let { delegate.setShuffle(it) }
            ConnectProtocol.ACTION_LOAD_QUEUE -> {
                val tracks = message.tracks.orEmpty()
                if (tracks.isNotEmpty()) {
                    val idx = (message.index ?: 0).coerceIn(0, tracks.lastIndex)
                    delegate.loadQueue(
                        tracks = tracks.take(ConnectProtocol.MAX_QUEUE_ITEMS),
                        index = idx,
                        positionMs = (message.positionMs ?: 0L).coerceAtLeast(0L),
                        play = message.play ?: true,
                        repeatMode = message.repeatMode,
                    )
                }
            }
            ConnectProtocol.ACTION_UPDATE_UPCOMING -> {
                delegate.updateUpcoming(message.tracks.orEmpty().take(ConnectProtocol.MAX_QUEUE_ITEMS))
            }
            ConnectProtocol.ACTION_JUMP_TO -> {
                val vid = message.videoId?.takeIf { it.isNotBlank() } ?: return
                delegate.jumpTo(
                    videoId = vid,
                    index = message.index,
                    positionMs = (message.positionMs ?: 0L).coerceAtLeast(0L),
                    play = message.play ?: true,
                )
            }
        }
    }

    private fun buildStateFrame(bumpSeq: Boolean): ConnectServerFrame {
        val now = System.currentTimeMillis()
        val seq = if (bumpSeq) stateSeq.incrementAndGet() else stateSeq.get()
        return ConnectServerFrame(
            type = ConnectProtocol.FRAME_STATE,
            serverMs = now,
            state = snapshotPlaybackState(now).copy(seq = seq),
        )
    }

    private fun snapshotPlaybackState(nowMs: Long): ConnectPlaybackState =
        delegate.currentPlaybackState().copy(
            seq = stateSeq.get(),
            queueSeq = queueSeq.get(),
            anchorMs = nowMs,
        )

    private fun broadcast(frame: ConnectServerFrame) {
        val encoded = json.encodeToString(frame)
        sessions.values.forEach { active ->
            scope.launch {
                sendRaw(active, encoded)
            }
        }
    }

    private suspend fun sendFrame(active: ActiveSession, frame: ConnectServerFrame) {
        sendRaw(active, json.encodeToString(frame))
    }

    private suspend fun sendRaw(active: ActiveSession, payload: String) {
        active.sendMutex.withLock {
            runCatching { active.session.send(Frame.Text(payload)) }
                .onFailure {
                    sessions.remove(active.info.sessionId)
                    publishConnectedClients()
                }
        }
    }

    private fun publishConnectedClients() {
        val list = sessions.values.map(ActiveSession::info).sortedBy(ConnectConnectedClient::connectedAtMs)
        _snapshot.update { it.copy(connectedClients = list) }
    }

    private fun authenticateToken(token: String): ConnectPairedClient? {
        if (token.isBlank()) return null
        val candidates = synchronized(pairedLock) { paired.values.toList() }
        return candidates.firstOrNull { constantTimeEquals(it.token, token) }
    }

    private fun isRateLimited(remoteHost: String): Boolean {
        val now = System.currentTimeMillis()
        val list = failedAttempts[remoteHost] ?: return false
        synchronized(list) {
            list.removeAll { now - it > RATE_LIMIT_WINDOW_MS }
            return list.size >= MAX_PAIR_FAILURES_PER_WINDOW
        }
    }

    private fun recordFailedAttempt(remoteHost: String) {
        val now = System.currentTimeMillis()
        val list = failedAttempts.computeIfAbsent(remoteHost) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > RATE_LIMIT_WINDOW_MS }
            list.add(now)
        }
    }

    private fun startMdns(port: Int): Boolean {
        val candidates = discoverMdnsBindAddresses()
        if (candidates.isEmpty()) return false
        var registeredAny = false
        val props = mapOf(
            "id" to deviceId,
            "name" to deviceName,
            "v" to ConnectProtocol.VERSION.toString(),
            "platform" to "pc",
        )
        // Unique instance name avoids collisions when multiple PCs run BitChord on the same LAN.
        val serviceName = "${deviceName.take(40)}-${deviceId.take(6)}"
        synchronized(mdnsInstances) {
            for (addr in candidates) {
                runCatching {
                    val jmdns = JmDNS.create(addr, "bitchord-${deviceId.take(8)}")
                    val info = ServiceInfo.create(
                        ConnectProtocol.MDNS_SERVICE_TYPE_LOCAL,
                        serviceName,
                        port,
                        0,
                        0,
                        props,
                    )
                    jmdns.registerService(info)
                    mdnsInstances += jmdns
                    registeredAny = true
                }.onFailure {
                    DesktopTrackLog.log("connect: mDNS register failed on ${addr.hostAddress}: ${it.message}")
                }
            }
        }
        return registeredAny
    }

    private fun stopMdns() {
        synchronized(mdnsInstances) {
            mdnsInstances.forEach { jmdns ->
                runCatching { jmdns.unregisterAllServices() }
                runCatching { jmdns.close() }
            }
            mdnsInstances.clear()
        }
    }

    private fun generatePin(): String = "%06d".format(random.nextInt(1_000_000))

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ab = a.toByteArray(StandardCharsets.UTF_8)
        val bb = b.toByteArray(StandardCharsets.UTF_8)
        return ab.size == bb.size && MessageDigest.isEqual(ab, bb)
    }

    companion object {
        private const val HEARTBEAT_MS = 3_000L
        private const val MAX_HTTP_BODY_BYTES = 16_384
        private const val MAX_WS_FRAME_BYTES = 262_144L
        private const val RATE_LIMIT_WINDOW_MS = 60_000L
        private const val MAX_PAIR_FAILURES_PER_WINDOW = 10
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 6

        internal fun normalizePin(raw: String?): String? {
            val digits = raw?.filter(Char::isDigit).orEmpty()
            return digits.takeIf { it.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH }
        }

        internal fun generateRandomPin(): String = "%06d".format(SecureRandom().nextInt(1_000_000))

        internal fun defaultDesktopDeviceName(): String =
            runCatching { InetAddress.getLocalHost().hostName }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("localhost", ignoreCase = true) }
                ?: "BitChord PC"

        internal fun discoverMdnsBindAddresses(): List<InetAddress> =
            discoverNetworkIpv4Addresses().filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }

        internal fun discoverLocalIpv4Addresses(): List<String> =
            discoverNetworkIpv4Addresses()
                .map { it.hostAddress }
                .distinct()
                .sortedWith(compareBy(::addressPriority, { it }))

        private fun discoverNetworkIpv4Addresses(): List<Inet4Address> = runCatching {
            val result = mutableListOf<Inet4Address>()
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isPointToPoint && !isTailscaleInterface(iface)) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        result += addr
                    }
                }
            }
            result
        }.getOrDefault(emptyList())

        private fun isTailscaleInterface(iface: NetworkInterface): Boolean {
            val n = "${iface.name} ${iface.displayName}".lowercase()
            return "tailscale" in n || "zerotier" in n || "wireguard" in n
        }

        private fun addressPriority(ip: String): Int = when {
            ip.startsWith("192.168.") -> 0
            ip.startsWith("10.") -> 1
            ip.startsWith("172.") -> 2
            ip.startsWith("100.") -> 3 // Tailscale / CGNAT overlay
            else -> 4
        }
    }
}

/**
 * Process-wide controller for BitChord Desktop LAN Connect.
 *
 * Wired into [DesktopBackgroundPlaybackHost] so Android stays connected even when the desktop
 * window is minimized to the system tray.
 */
internal object DesktopConnectHost {
    private const val KEY_ENABLED = "connect_lan_enabled"
    private const val KEY_DEVICE_ID = "connect_device_id"
    private const val KEY_DEVICE_NAME = "connect_device_name"
    private const val KEY_PIN = "connect_pairing_pin"
    private const val KEY_PAIRED_CLIENTS = "connect_paired_clients_v1"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val persistence by lazy { DesktopPersistence() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _enabled = MutableStateFlow(persistence.boolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _state = MutableStateFlow(
        DesktopConnectServer.Snapshot(
            deviceId = loadOrCreateDeviceId(),
            deviceName = loadDeviceName(),
            pin = loadOrCreatePin(),
            pairedClients = loadPairedClients(),
        ),
    )
    val state: StateFlow<DesktopConnectServer.Snapshot> = _state.asStateFlow()

    private var server: DesktopConnectServer? = null
    private var snapshotForwardJob: Job? = null
    private var attachedService: DesktopPlaybackService? = null

    @Synchronized
    fun attach(service: DesktopPlaybackService) {
        attachedService = service
        if (_enabled.value) {
            startInternal(service)
        }
    }

    @Synchronized
    fun detach(service: DesktopPlaybackService) {
        if (attachedService !== service) return
        attachedService = null
        stopInternal()
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        _enabled.value = value
        persistence.saveBoolean(KEY_ENABLED, value)
        if (value) {
            attachedService?.let(::startInternal)
        } else {
            stopInternal()
        }
    }

    fun setDeviceName(name: String) {
        val cleaned = name.trim().take(64).ifBlank { DesktopConnectServer.defaultDesktopDeviceName() }
        persistence.saveString(KEY_DEVICE_NAME, cleaned)
        server?.updateDeviceName(cleaned)
        if (server == null) {
            _state.update { it.copy(deviceName = cleaned) }
        }
    }

    fun rotatePin(): String {
        val running = server
        if (running != null) return running.rotatePin()
        val next = DesktopConnectServer.generateRandomPin()
        savePin(next)
        _state.update { it.copy(pin = next) }
        return next
    }

    fun setCustomPin(rawPin: String): Result<String> {
        val running = server
        if (running != null) return running.setPin(rawPin)
        val normalized = DesktopConnectServer.normalizePin(rawPin)
            ?: return Result.failure(IllegalArgumentException("PIN must be 4 to 6 digits."))
        savePin(normalized)
        _state.update { it.copy(pin = normalized) }
        return Result.success(normalized)
    }

    fun refreshNetwork() {
        server?.refreshAddresses()
    }

    fun revokeClient(clientId: String) {
        val running = server
        if (running != null) {
            running.revokeClient(clientId)
        } else {
            val updated = loadPairedClients().filterNot { it.clientId == clientId }
            savePairedClients(updated)
            _state.update { it.copy(pairedClients = updated) }
        }
    }

    fun onPlaybackChanged() {
        server?.notifyPlaybackChanged()
    }

    fun onQueueChanged() {
        server?.notifyQueueChanged()
    }

    private fun startInternal(service: DesktopPlaybackService) {
        if (server != null) return
        val deviceId = loadOrCreateDeviceId()
        val deviceName = loadDeviceName()
        val pin = loadOrCreatePin()
        val paired = loadPairedClients()
        val version = System.getProperty("bitchord.version").orEmpty()
        val instance = DesktopConnectServer(
            deviceId = deviceId,
            initialDeviceName = deviceName,
            appVersion = version,
            initialPin = pin,
            onPinChanged = ::savePin,
            initialPairedClients = paired,
            onPairedClientsChanged = ::savePairedClients,
            delegate = ServiceDelegate(service),
        )
        server = instance
        snapshotForwardJob?.cancel()
        snapshotForwardJob = scope.launch {
            instance.snapshot.collect { _state.value = it }
        }
        scope.launch {
            runCatching { instance.start() }.onFailure { error ->
                DesktopTrackLog.log("connect: failed to start server: ${error.message}")
                _state.update {
                    it.copy(
                        running = false,
                        error = error.message ?: "Failed to start LAN Connect server",
                    )
                }
            }
        }
    }

    private fun stopInternal() {
        snapshotForwardJob?.cancel()
        snapshotForwardJob = null
        val running = server
        server = null
        running?.stop()
        _state.update {
            it.copy(
                running = false,
                mdnsActive = false,
                connectedClients = emptyList(),
                error = null,
            )
        }
    }

    private fun loadOrCreateDeviceId(): String {
        val existing = persistence.string(KEY_DEVICE_ID, "").trim()
        if (existing.isNotEmpty()) return existing
        val created = UUID.randomUUID().toString()
        persistence.saveString(KEY_DEVICE_ID, created)
        return created
    }

    private fun loadDeviceName(): String =
        persistence.string(KEY_DEVICE_NAME, "").trim().ifBlank { DesktopConnectServer.defaultDesktopDeviceName() }

    private fun loadOrCreatePin(): String {
        val existing = DesktopConnectServer.normalizePin(persistence.string(KEY_PIN, ""))
        if (existing != null) return existing
        val created = DesktopConnectServer.generateRandomPin()
        savePin(created)
        return created
    }

    private fun savePin(pin: String) {
        persistence.saveString(KEY_PIN, pin)
    }

    private fun loadPairedClients(): List<ConnectPairedClient> {
        val raw = persistence.string(KEY_PAIRED_CLIENTS, "").trim()
        if (raw.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<ConnectPairedClient>>(raw) }.getOrDefault(emptyList())
    }

    private fun savePairedClients(clients: List<ConnectPairedClient>) {
        runCatching { persistence.saveString(KEY_PAIRED_CLIENTS, json.encodeToString(clients)) }
    }

    private class ServiceDelegate(
        private val service: DesktopPlaybackService,
    ) : ConnectPlaybackDelegate {
        override fun currentPlaybackState(): ConnectPlaybackState {
            val engineState = service.engine.state.value
            val queue = service.liveQueue.value
            val song = engineState.song ?: service.selectedSong.value ?: queue.current
            val positionMs = if (engineState.isPlaying && !engineState.isLoading && !engineState.awaitingAudio &&
                engineState.positionSampledAtNanos > 0L
            ) {
                val elapsed = ((System.nanoTime() - engineState.positionSampledAtNanos) / 1_000_000L).coerceAtLeast(0L)
                val est = engineState.positionMs + elapsed
                if (engineState.durationMs > 0L) est.coerceAtMost(engineState.durationMs) else est
            } else {
                engineState.positionMs.coerceAtLeast(0L)
            }
            return ConnectPlaybackState(
                queueIndex = queue.index.takeIf { it in queue.songs.indices } ?: -1,
                track = song?.toConnectTrack(engineState.durationMs),
                isPlaying = engineState.isPlaying,
                isLoading = engineState.isLoading,
                buffering = engineState.awaitingAudio,
                positionMs = positionMs,
                anchorMs = System.currentTimeMillis(),
                durationMs = engineState.durationMs.coerceAtLeast(0L),
                volume = DesktopPlayerHost.volumeLevel.value.coerceIn(0f, 1f),
                repeatMode = service.repeatState.value,
                shuffle = service.shuffleState.value,
            )
        }

        override fun currentQueueState(): ConnectQueueState {
            val queue = service.liveQueue.value
            val playable = queue.songs.withIndex()
                .filter { (_, song) -> song.localPath == null && song.localUri == null }
            if (playable.isEmpty()) return ConnectQueueState(index = -1, items = emptyList())
            val mappedCurrent = playable.indexOfFirst { it.index == queue.index }.takeIf { it >= 0 } ?: 0
            val windowStart = (mappedCurrent - 10).coerceAtLeast(0)
            val windowEnd = (windowStart + ConnectProtocol.MAX_QUEUE_ITEMS).coerceAtMost(playable.size)
            val slice = playable.subList(windowStart, windowEnd)
            val engineState = service.engine.state.value
            val items = slice.map { (_, song) ->
                val dur = if (song.videoId == engineState.song?.videoId) engineState.durationMs else 0L
                song.toConnectTrack(dur)
            }
            return ConnectQueueState(
                index = (mappedCurrent - windowStart).coerceIn(0, items.lastIndex),
                items = items,
            )
        }

        override fun play() = service.play()
        override fun pause() = service.pause()
        override fun togglePlayPause() = service.togglePlayPause()
        override fun seek(positionMs: Long) = service.seek(positionMs)
        override fun next() = service.next()
        override fun previous() = service.previous()
        override fun setVolume(volume: Float) = service.volume(volume.toDouble())
        override fun setShuffle(shuffle: Boolean) = service.shuffle(shuffle)
        override fun setRepeat(repeatMode: String) {
            val loopStatus = when (repeatMode.uppercase()) {
                ConnectProtocol.REPEAT_ONE -> "Track"
                ConnectProtocol.REPEAT_ALL -> "Playlist"
                else -> "None"
            }
            service.loopStatus(loopStatus)
        }

        override fun loadQueue(
            tracks: List<ConnectTrack>,
            index: Int,
            positionMs: Long,
            play: Boolean,
            repeatMode: String?,
        ) = service.loadRemoteQueue(tracks, index, positionMs, play, repeatMode)

        override fun updateUpcoming(tracks: List<ConnectTrack>) = service.updateRemoteUpcoming(tracks)

        override fun jumpTo(videoId: String, index: Int?, positionMs: Long, play: Boolean) =
            service.jumpToRemote(videoId, index, positionMs, play)
    }
}

internal fun Song.toConnectTrack(durationMs: Long = 0L): ConnectTrack = ConnectTrack(
    videoId = videoId,
    title = title,
    artist = artist,
    albumName = albumName,
    thumbnailUrl = thumbnailUrl,
    durationMs = durationMs.takeIf { it > 0L } ?: parseDurationTextMs(durationText),
    isVideo = isVideo,
    fromAutoplay = queueTier == QueueTier.AUTOPLAY || fromAutoplay,
)

internal fun ConnectTrack.toDesktopConnectSong(): Song = Song(
    videoId = videoId,
    title = title,
    artist = artist,
    albumName = albumName,
    thumbnailUrl = thumbnailUrl,
    durationText = durationMs?.takeIf { it > 0L }?.let { ms ->
        val total = ms / 1000L
        "%d:%02d".format(total / 60L, total % 60L)
    },
    isVideo = isVideo,
    isVideoOrigin = isVideo,
    queueTier = if (fromAutoplay) QueueTier.AUTOPLAY else QueueTier.USER_QUEUE,
)

private fun parseDurationTextMs(text: String?): Long? {
    val parts = text?.trim()?.split(':')?.mapNotNull(String::toLongOrNull) ?: return null
    return when (parts.size) {
        2 -> (parts[0] * 60L + parts[1]) * 1000L
        3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
        else -> null
    }?.takeIf { it > 0L }
}
