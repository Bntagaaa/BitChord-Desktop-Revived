package com.music.bitchord.data.innertube.potoken

import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoTokenGeneratorTest {

    private class FakeMinter(val token: String = "fake-po-token") : PoTokenMinter {
        override var isDead: Boolean = false
        override var isExpired: Boolean = false
        val generatedFor = mutableListOf<String>()

        override suspend fun generatePoToken(identifier: String): String {
            generatedFor.add(identifier)
            return "$token-$identifier"
        }

        override fun close() {
            isDead = true
        }
    }

    @Test
    fun blankVideoIdSkipsMinterAndAvailableCheck() = runBlocking {
        val availableQueried = AtomicBoolean(false)
        val minterCreated = AtomicInteger(0)

        val generator = PoTokenGenerator(
            createMinter = {
                minterCreated.incrementAndGet()
                FakeMinter()
            },
            available = {
                availableQueried.set(true)
                true
            },
        )

        val tokenBlank = generator.getWebClientPoToken(videoId = "", sessionId = "session123")
        assertNull(tokenBlank, "blank videoId must return null immediately")
        assertFalse(availableQueried.get(), "available must not be queried for blank videoId")
        assertEquals(0, minterCreated.get(), "minter must not be created for blank videoId")

        val tokenWhitespace = generator.getWebClientPoToken(videoId = "   ", sessionId = "session123")
        assertNull(tokenWhitespace, "whitespace videoId must return null immediately")
        assertFalse(availableQueried.get())
        assertEquals(0, minterCreated.get())

        val provider = generator.asTokenProvider()
        val providerResult = provider.getPoToken(videoId = "", visitorData = "visitor123", cookie = null)
        assertNull(providerResult, "provider getPoToken must return null for blank videoId")
        assertFalse(availableQueried.get())
        assertEquals(0, minterCreated.get())
    }

    @Test
    fun validVideoIdQueriesAvailableAndCreatesMinter() = runBlocking {
        val availableQueried = AtomicBoolean(false)
        val minterCreated = AtomicInteger(0)

        val generator = PoTokenGenerator(
            createMinter = {
                minterCreated.incrementAndGet()
                FakeMinter("minted")
            },
            available = {
                availableQueried.set(true)
                true
            },
        )

        val result = generator.getWebClientPoToken(videoId = "video456", sessionId = "session123")
        assertTrue(availableQueried.get(), "available must be queried for non-blank videoId")
        assertEquals(1, minterCreated.get(), "minter must be created")
        assertEquals("minted-video456", result?.streamingDataPoToken)
    }
}
