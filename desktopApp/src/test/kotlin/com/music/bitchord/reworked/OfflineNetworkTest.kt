package com.music.bitchord.reworked

import com.music.bitchord.data.Http
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OfflineNetworkTest {
    @Test fun offlineHostPolicyStopsRequestsBeforeAnyPayloadLeavesTheClient() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("unexpected network request"))
            server.start()
            val previous = Http.networkAllowed
            try {
                Http.networkAllowed = false
                val request = Request.Builder().url(server.url("/must-not-be-sent"))
                    .header("Cookie", "synthetic_fixture=not_a_user_cookie").build()
                assertFailsWith<IOException> { Http.client.newCall(request).execute().close() }
                assertEquals(0, server.requestCount, "Offline mode must reject before transport, not after a response")
            } finally { Http.networkAllowed = previous }
        }
    }
}
