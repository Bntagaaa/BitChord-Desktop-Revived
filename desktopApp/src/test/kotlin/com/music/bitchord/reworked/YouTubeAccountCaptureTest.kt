package com.music.bitchord.reworked

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import kotlin.test.*

class YouTubeAccountCaptureTest {
    @Test fun signedInCaptureUsesExplicitBrandAndGoogleAccountIndex() {
        val capture = YouTubeCapture.parse("https://music.youtube.com/", "__Secure-3PAPISID=fixture-secret",
            """{"loggedIn":"true","pageId":"brand-42","dataSyncId":"owner||wrong-default","authUser":"2","visitorData":"visitor","clientVersion":"1.20260401.01.00"}""")
        assertEquals("brand-42", capture.pageId)
        assertEquals("brand-42", capture.dataSyncId)
        assertEquals("2", capture.authUser)
        assertFalse(capture.toString().contains("fixture-secret"))
    }

    @Test fun personalViewerNormalizesMobileDataSyncContractWithoutInventingPageId() {
        val capture = YouTubeCapture.parse("https://music.youtube.com/", "SAPISID=fixture-secret",
            """{"loggedIn":true,"dataSyncId":"owner||personal-13","authUser":0,"clientVersion":"1.2"}""")
        assertNull(capture.pageId)
        assertEquals("personal-13", capture.dataSyncId)
        assertEquals("0", capture.authUser)
        val noChannel = YouTubeCapture.parse("https://music.youtube.com/", "SAPISID=fixture-secret", """{"loggedIn":true,"clientVersion":"1.2"}""")
        assertNull(noChannel.dataSyncId)
    }

    @Test fun captureRejectsRedirectsLookalikesSignedOutPagesAndCookieNameSubstrings() {
        for (address in listOf("https://accounts.google.com/", "http://music.youtube.com/", "https://music.youtube.com.attacker.invalid/", "https://user@music.youtube.com/", "https://music.youtube.com:444/")) {
            assertFailsWith<IllegalArgumentException> { YouTubeCapture.parse(address, "SAPISID=fixture", """{"loggedIn":true}""") }
        }
        for (cookie in listOf("NOT_SAPISID=fixture", "SAPISID=", "name=SAPISID=fixture", "SAPISID=x\r\nInjected: y", "SAPISID=x\u0001", "SAPISID=non-ascii-é")) assertFalse(YouTubeCapture.hasApiSid(cookie))
        for (cookie in listOf("SAPISID=fixture", "__Secure-1PAPISID=fixture", "__Secure-3PAPISID=fixture")) assertTrue(YouTubeCapture.hasApiSid(cookie))
        assertFailsWith<IllegalArgumentException> { YouTubeCapture.parse("https://music.youtube.com/", "SAPISID=fixture", """{"loggedIn":false}""") }
        assertFailsWith<IllegalStateException> { YouTubeCapture.parse("https://music.youtube.com/", "SAPISID=fixture", "\"{\\\"loggedIn\\\":true}\"") }
        assertFailsWith<IllegalArgumentException> { YouTubeCapture.parse("https://music.youtube.com/", "SAPISID=fixture", """{"loggedIn":true,"authUser":"one"}""") }
    }

    @Test fun isolatedValidationRequestMatchesOriginalMobileSigningAndScope() {
        val capture = testCapture(pageId = "brand-42", authUser = "2")
        val request = YouTubeCapturedSessionValidator.request(capture, timestamp = 1234, language = "id", region = "ID")
        assertEquals("https://music.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false", request.url)
        assertEquals("SAPISIDHASH 1234_21ba0c3fa35abb990a69446ee29a2d4e7bc9ccd0", request.headers["Authorization"])
        assertEquals("2", request.headers["X-Goog-AuthUser"])
        assertEquals("brand-42", request.headers["X-Goog-PageId"])
        val context = Json.parseToJsonElement(request.body).jsonObject.getValue("context").jsonObject
        assertEquals("brand-42", context.getValue("user").jsonObject.getValue("onBehalfOfUser").jsonPrimitive.content)
        assertEquals("WEB_REMIX", context.getValue("client").jsonObject.getValue("clientName").jsonPrimitive.content)
        assertEquals("id", context.getValue("client").jsonObject.getValue("hl").jsonPrimitive.content)
        assertFalse(request.toString().contains("fixture-secret"))
    }

    @Test fun validationUsesOriginalAccountHeaderParserAndRejectsAnonymousResponses() = runBlocking {
        val account = YouTubeCapturedSessionValidator.validate(testCapture()) {
            Json.parseToJsonElement("""{"actions":[{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"Fixture listener"}]},"email":{"simpleText":"listener@example.invalid"}}}]}""").jsonObject
        }
        assertEquals("Fixture listener", account.name)
        assertEquals("listener@example.invalid", account.email)
        var attempts = 0
        assertFailsWith<IllegalStateException> { YouTubeCapturedSessionValidator.validate(testCapture()) { attempts++; buildJsonObject {} } }
        assertEquals(2, attempts)
    }

    @Test fun browserCookieSwapTouchesOnlyGoogleAndYouTubeAndUsesSelectedSnapshot() {
        val manager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        fun add(host: String, name: String, value: String) = manager.cookieStore.add(URI("https://$host"),
            HttpCookie(name, value).apply { domain = ".$host"; path = "/"; secure = true; version = 0 })
        add("google.com", "SID", "old-google")
        add("youtube.com", "SAPISID", "old-youtube")
        add("spotify.com", "sp_dc", "spotify-fixture")
        add("discord.com", "session", "discord-fixture")
        add("youtube.com.attacker.invalid", "SAPISID", "unrelated")
        DesktopYouTubeBrowser.installGoogleCookies(manager, "SAPISID=selected; PREF=value")
        val music = manager.get(URI("https://music.youtube.com/"), emptyMap()).values.flatten().joinToString(";")
        assertTrue(music.contains("SAPISID=selected"))
        assertFalse(music.contains("old-youtube"))
        assertTrue(manager.cookieStore.cookies.any { it.name == "sp_dc" && it.value == "spotify-fixture" })
        assertTrue(manager.cookieStore.cookies.any { it.domain == ".youtube.com.attacker.invalid" })
        DesktopYouTubeBrowser.clearGoogleCookies(manager)
        assertFalse(manager.cookieStore.cookies.any { it.value == "selected" || it.value == "old-google" })
        assertTrue(manager.cookieStore.cookies.any { it.name == "session" && it.value == "discord-fixture" })
    }
}

internal fun testCapture(cookie: String = "SAPISID=fixture-secret", pageId: String? = null, identity: String = "personal-1", authUser: String = "0") =
    CapturedYouTubeSession(cookie, pageId, pageId ?: identity, authUser, "visitor-fixture", "1.20260401.01.00", true)
