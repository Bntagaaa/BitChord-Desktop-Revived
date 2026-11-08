package com.music.bitchord.reworked

import com.music.bitchord.data.listentogether.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Exercises the real mobile HTTP/WebSocket client against an isolated protocol fixture. */
class MobilePartyTransportTest {
    @Test fun membershipClockOrderedControlsAndStaleFramesUseTheMobileContract() = runBlocking {
        val json = Json { encodeDefaults = true }
        val commands = LinkedBlockingQueue<String>()
        val requests = LinkedBlockingQueue<RecordedRequest>()
        val socket = AtomicReference<WebSocket>()
        val member = PartyMember("member-1", "hashed-user", "Listener", isHost = true, connected = true)
        val track = PartyTrack("video-1", "Fixture song", "Fixture artist", durationMs = 120_000)
        val queue = PartyQueue(3, 0, listOf(track))
        val playback = PartyPlayback(seq = 10, track = track, queueSeq = 3, queueLength = 1,
            queueIndex = 0, isPlaying = false, positionMs = 12_000)
        val party = PartySnapshot("ABC123", maxMembers = 5, members = listOf(member), playback = playback, queue = queue)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.offer(request)
                return when {
                    request.path == "/healthz" -> MockResponse().setBody("{\"ok\":true}")
                    request.path == "/api/parties" -> MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(json.encodeToString(PartyMembership("ABC123", "member-token", member, party)))
                    request.path == "/api/parties/ABC123/leave" -> MockResponse().setBody("{\"ok\":true}")
                    request.path == "/ws/parties/ABC123" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            socket.set(webSocket)
                            webSocket.send(buildJsonObject {
                                put("type", "welcome"); put("party", json.encodeToJsonElement(party)); put("you", json.encodeToJsonElement(member))
                            }.toString())
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = json.parseToJsonElement(text).jsonObject
                            when (frame["type"]?.jsonPrimitive?.content) {
                                "ping" -> webSocket.send(buildJsonObject {
                                    put("type", "pong"); put("clientMs", frame.getValue("clientMs")); put("serverMs", System.currentTimeMillis())
                                }.toString())
                                "control" -> commands.offer(frame.getValue("action").jsonPrimitive.content)
                            }
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            ListenTogether.init()
            ListenTogether.setAccount(ListenTogether.AccountIdentity("account@example.test", "Listener", null))
            val health = ListenTogether.setCustomServerUrl(server.url("/").toString().trimEnd('/'))
            assertIs<ServerConnectionState.CustomOnline>(health)
            assertEquals("ABC123", ListenTogether.createParty("Listener").getOrThrow())
            val ready = withTimeout(5_000) { ListenTogether.state.first { it.connection == ListenTogether.Connection.LIVE && it.clockSynced } }
            assertEquals("ABC123", ready.code)
            assertEquals(12_000L, ListenTogether.partyPositionMs())
            assertEquals(3L, ready.queue.seq)

            ListenTogether.setQueue(listOf(track), 0)
            ListenTogether.setTrack(track)
            ListenTogether.pause(5_000)
            ListenTogether.seek(7_000)
            assertEquals(listOf("setQueue", "setTrack", "pause", "seek"),
                List(4) { commands.poll(3, TimeUnit.SECONDS) })

            socket.get().send(buildJsonObject {
                put("type", "state"); put("playback", json.encodeToJsonElement(playback.copy(seq = 11, positionMs = 20_000)))
            }.toString())
            withTimeout(3_000) { ListenTogether.state.first { it.playback.seq == 11L } }
            socket.get().send(buildJsonObject {
                put("type", "state"); put("playback", json.encodeToJsonElement(playback.copy(seq = 4, positionMs = 1_000)))
            }.toString())
            // A subsequent distinct frame provides a barrier after the obsolete state.
            socket.get().send(buildJsonObject {
                put("type", "queue"); put("queue", json.encodeToJsonElement(queue.copy(seq = 4)))
            }.toString())
            withTimeout(3_000) { ListenTogether.state.first { it.queue.seq == 4L } }
            assertEquals(11L, ListenTogether.state.value.playback.seq)
            assertEquals(20_000L, ListenTogether.partyPositionMs())

            val seen = requests.toList()
            val created = seen.first { it.path == "/api/parties" }
            val body = created.body.readUtf8()
            assertFalse(body.contains("account@example.test"))
            assertEquals(32, json.parseToJsonElement(body).jsonObject.getValue("userId").jsonPrimitive.content.length)
            assertEquals("Bearer member-token", seen.first { it.path == "/ws/parties/ABC123" }.getHeader("Authorization"))
        } finally {
            ListenTogether.leaveParty()
            ListenTogether.setAccount(null)
            ListenTogether.setCustomServerUrl("")
            socket.get()?.close(1000, "fixture complete")
            server.shutdown()
        }
    }
}
