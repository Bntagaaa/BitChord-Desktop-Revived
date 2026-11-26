package com.music.bitchord.desktop

import com.music.bitchord.data.connect.ConnectClientFrame
import com.music.bitchord.data.connect.ConnectHostInfo
import com.music.bitchord.data.connect.ConnectPairRequest
import com.music.bitchord.data.connect.ConnectPairResponse
import com.music.bitchord.data.connect.ConnectPlaybackState
import com.music.bitchord.data.connect.ConnectProtocol
import com.music.bitchord.data.connect.ConnectQueueState
import com.music.bitchord.data.connect.ConnectServerFrame
import com.music.bitchord.data.connect.ConnectTrack
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopConnectHostTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private class FakeDelegate : ConnectPlaybackDelegate {
        val actions = mutableListOf<String>()
        var playbackState = ConnectPlaybackState(
            queueIndex = 0,
            track = ConnectTrack(videoId = "abc12345678", title = "Initial", artist = "Artist", durationMs = 180_000L),
            isPlaying = false,
            positionMs = 12_000L,
            durationMs = 180_000L,
            volume = 0.7f,
        )
        var queueState = ConnectQueueState(
            index = 0,
            items = listOf(
                ConnectTrack(videoId = "abc12345678", title = "Initial", artist = "Artist", durationMs = 180_000L),
            ),
        )

        override fun currentPlaybackState(): ConnectPlaybackState = playbackState
        override fun currentQueueState(): ConnectQueueState = queueState
        override fun play() {
            actions += "play"
            playbackState = playbackState.copy(isPlaying = true)
        }

        override fun pause() {
            actions += "pause"
            playbackState = playbackState.copy(isPlaying = false)
        }

        override fun togglePlayPause() {
            actions += "toggle"
            playbackState = playbackState.copy(isPlaying = !playbackState.isPlaying)
        }

        override fun seek(positionMs: Long) {
            actions += "seek:$positionMs"
            playbackState = playbackState.copy(positionMs = positionMs)
        }

        override fun next() { actions += "next" }
        override fun previous() { actions += "previous" }
        override fun setVolume(volume: Float) {
            actions += "volume:$volume"
            playbackState = playbackState.copy(volume = volume)
        }

        override fun setRepeat(repeatMode: String) {
            actions += "repeat:$repeatMode"
            playbackState = playbackState.copy(repeatMode = repeatMode)
        }

        override fun setShuffle(shuffle: Boolean) {
            actions += "shuffle:$shuffle"
            playbackState = playbackState.copy(shuffle = shuffle)
        }

        override fun loadQueue(
            tracks: List<ConnectTrack>,
            index: Int,
            positionMs: Long,
            play: Boolean,
            repeatMode: String?,
        ) {
            actions += "loadQueue:${tracks.size}:$index:$positionMs:$play"
            queueState = ConnectQueueState(index = index, items = tracks)
            playbackState = playbackState.copy(
                queueIndex = index,
                track = tracks.getOrNull(index),
                positionMs = positionMs,
                isPlaying = play,
                repeatMode = repeatMode ?: playbackState.repeatMode,
            )
        }

        override fun updateUpcoming(tracks: List<ConnectTrack>) {
            actions += "updateUpcoming:${tracks.size}"
        }

        override fun jumpTo(videoId: String, index: Int?, positionMs: Long, play: Boolean) {
            actions += "jumpTo:$videoId:$index:$positionMs:$play"
        }
    }

    @Test
    fun infoPairWebsocketAndControlsEndToEnd() = runBlocking {
        val delegate = FakeDelegate()
        val persisted = mutableListOf<List<ConnectPairedClient>>()
        val server = DesktopConnectServer(
            deviceId = "test-pc-1",
            initialDeviceName = "Test Desktop",
            appVersion = "1.10.0",
            preferredPort = 0,
            enableMdns = false,
            onPairedClientsChanged = { persisted += it },
            delegate = delegate,
        )
        val client = HttpClient(CIO) {
            install(WebSockets)
        }
        try {
            server.start()
            val port = server.snapshot.value.port
            assertTrue(port > 0, "Expected ephemeral bound port")

            // 1. Info endpoint
            val infoResp = client.get("http://127.0.0.1:$port/connect/info")
            assertEquals(HttpStatusCode.OK, infoResp.status)
            val info = json.decodeFromString<ConnectHostInfo>(infoResp.bodyAsText())
            assertEquals("test-pc-1", info.deviceId)
            assertEquals("Test Desktop", info.deviceName)

            // 2. Wrong PIN rejected
            val badPair = client.post("http://127.0.0.1:$port/connect/pair") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ConnectPairRequest(pin = "000000", clientId = "phone-1", clientName = "Pixel")))
            }
            if (server.snapshot.value.pin != "000000") {
                assertEquals(HttpStatusCode.Unauthorized, badPair.status)
            }

            // 3. Correct PIN succeeds and persists client
            val pin = server.snapshot.value.pin
            val goodPair = client.post("http://127.0.0.1:$port/connect/pair") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ConnectPairRequest(pin = pin, clientId = "phone-1", clientName = "Pixel")))
            }
            assertEquals(HttpStatusCode.OK, goodPair.status)
            val pairResp = json.decodeFromString<ConnectPairResponse>(goodPair.bodyAsText())
            assertTrue(pairResp.token.length >= 32)
            assertEquals(1, persisted.last().size)

            // 4. WebSocket receives welcome, answers ping, executes controls, receives state broadcast
            withTimeout(10_000L) {
                client.webSocket(
                    urlString = "ws://127.0.0.1:$port/connect/ws",
                    request = { header(HttpHeaders.Authorization, "Bearer ${pairResp.token}") },
                ) {
                    val welcomeRaw = (incoming.receive() as Frame.Text).readText()
                    val welcome = json.decodeFromString<ConnectServerFrame>(welcomeRaw)
                    assertEquals(ConnectProtocol.FRAME_WELCOME, welcome.type)
                    assertEquals("abc12345678", welcome.state?.track?.videoId)
                    assertEquals(1, welcome.queue?.items?.size)

                    // Ping -> Pong
                    send(Frame.Text(json.encodeToString(ConnectClientFrame.ping(42L))))
                    val pongRaw = (incoming.receive() as Frame.Text).readText()
                    val pong = json.decodeFromString<ConnectServerFrame>(pongRaw)
                    assertEquals(ConnectProtocol.FRAME_PONG, pong.type)
                    assertEquals(42L, pong.clientMs)

                    // Send loadQueue + volume + seek
                    val newTrack = ConnectTrack(videoId = "xyz98765432", title = "Remote Song", artist = "Band", durationMs = 200_000L)
                    send(
                        Frame.Text(
                            json.encodeToString(
                                ConnectClientFrame.control(
                                    action = ConnectProtocol.ACTION_LOAD_QUEUE,
                                    tracks = listOf(newTrack),
                                    index = 0,
                                    positionMs = 5_000L,
                                    play = true,
                                    repeatMode = ConnectProtocol.REPEAT_ALL,
                                ),
                            ),
                        ),
                    )
                    send(Frame.Text(json.encodeToString(ConnectClientFrame.control(ConnectProtocol.ACTION_SET_VOLUME, volume = 0.45f))))
                    send(Frame.Text(json.encodeToString(ConnectClientFrame.sync())))

                    // Wait for queue + state frames triggered by sync
                    var sawNewTrack = false
                    repeat(5) {
                        val nextFrame = json.decodeFromString<ConnectServerFrame>((incoming.receive() as Frame.Text).readText())
                        if (nextFrame.type == ConnectProtocol.FRAME_STATE && nextFrame.state?.track?.videoId == "xyz98765432") {
                            assertEquals(0.45f, nextFrame.state?.volume)
                            assertEquals(true, nextFrame.state?.isPlaying)
                            sawNewTrack = true
                            return@repeat
                        }
                    }
                    assertTrue(sawNewTrack, "Expected synced state for newly loaded track")
                }
            }
            assertTrue(delegate.actions.any { it.startsWith("loadQueue:1:0:5000:true") })
            assertTrue(delegate.actions.any { it == "volume:0.45" })

            // 5. Revoke clears client
            server.revokeClient("phone-1")
            assertNotNull(persisted.last())
            assertTrue(server.snapshot.value.pairedClients.isEmpty())
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun customPinPersistsAndAuthenticates() = runBlocking {
        val delegate = FakeDelegate()
        val savedPins = mutableListOf<String>()
        val server = DesktopConnectServer(
            deviceId = "test-pc-pin",
            initialDeviceName = "Custom Pin PC",
            appVersion = "1.10.0",
            preferredPort = 0,
            enableMdns = false,
            initialPin = "123456",
            onPinChanged = { savedPins += it },
            delegate = delegate,
        )
        val client = HttpClient(CIO)
        try {
            server.start()
            val port = server.snapshot.value.port
            assertEquals("123456", server.snapshot.value.pin)

            // Reject invalid custom PIN (< 4 digits)
            assertTrue(server.setPin("12").isFailure)
            assertEquals("123456", server.snapshot.value.pin)

            // Update to custom PIN and verify callback + auth
            assertEquals("258014", server.setPin("258014").getOrThrow())
            assertEquals(listOf("258014"), savedPins)

            val oldPinResp = client.post("http://127.0.0.1:$port/connect/pair") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ConnectPairRequest(pin = "123456", clientId = "p2", clientName = "Phone")))
            }
            assertEquals(HttpStatusCode.Unauthorized, oldPinResp.status)

            val newPinResp = client.post("http://127.0.0.1:$port/connect/pair") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ConnectPairRequest(pin = "258014", clientId = "p2", clientName = "Phone")))
            }
            assertEquals(HttpStatusCode.OK, newPinResp.status)
        } finally {
            client.close()
            server.stop()
        }
    }
}
