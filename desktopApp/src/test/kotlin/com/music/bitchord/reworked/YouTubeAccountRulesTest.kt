package com.music.bitchord.reworked

import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import kotlin.test.*

class YouTubeAccountRulesTest {
    @Test fun repeatedGoogleLoginDeduplicatesRotatingCookiesByConfirmedEmail() {
        val first = YouTubeAccountRules.accepted(YouTubeAccountRegistry(), testCapture(), Account("Personal", "fixture@example.invalid", null), YouTubeSessionMode.SIGN_IN, null)
        val second = YouTubeAccountRules.accepted(first, testCapture("SAPISID=rotated", pageId = "brand", authUser = "2"),
            Account("Brand", "FIXTURE@example.invalid", null), YouTubeSessionMode.SIGN_IN, null)
        assertEquals(1, second.accounts.size)
        assertEquals(first.activeAccountId, second.activeAccountId)
        assertEquals(2, second.activeAccount!!.profiles.size)
        assertTrue(second.activeAccount!!.profiles.all { it.authUser == "2" })
        assertEquals("brand", second.activeProfileId)
    }

    @Test fun detectedRealChannelReplacesPlaceholderButEmptyResponseDoesNot() {
        val capture = testCapture().copy(dataSyncId = null)
        val before = YouTubeAccountRules.accepted(YouTubeAccountRegistry(), capture, Account("Listener", "", null), YouTubeSessionMode.SIGN_IN, null)
        assertTrue(before.activeProfileId!!.startsWith("profile:"))
        assertEquals(before, YouTubeAccountRules.detected(before, before.activeAccountId!!, emptyList()))
        val after = YouTubeAccountRules.detected(before, before.activeAccountId,
            listOf(AccountChannel("Listener", "@listener", null, null, "personal-real", false)))
        assertEquals(1, after.activeAccount!!.profiles.size)
        assertEquals("personal-real", after.activeProfileId)
    }

    @Test fun profileStepOrderIsStableAndDoesNotWrap() {
        val first = YouTubeAccountRules.accepted(YouTubeAccountRegistry(), testCapture(), Account("One", "one@example.invalid", null), YouTubeSessionMode.SIGN_IN, null)
        val second = YouTubeAccountRules.accepted(first, testCapture("SAPISID=two", pageId = "second"), Account("Two", "two@example.invalid", null), YouTubeSessionMode.SIGN_IN, null)
        assertNull(YouTubeAccountRules.adjacent(second, forward = true))
        assertEquals(first.activeAccountId!! to first.activeProfileId!!, YouTubeAccountRules.adjacent(second, forward = false))
        val back = YouTubeAccountRules.select(second, first.activeAccountId, first.activeProfileId)
        assertNull(YouTubeAccountRules.adjacent(back, forward = false))
        val removed = YouTubeAccountRules.remove(second, second.activeAccountId!!)
        assertEquals(first.activeAccountId, removed.activeAccountId)
        assertEquals(first.activeProfileId, removed.activeProfileId)
    }

    @Test fun personalViewerWithoutChannelKeepsCapturedGoogleIndexAcrossRegistryRoundTrip() {
        val capture = testCapture(authUser = "3").copy(pageId = null, dataSyncId = null)
        val registry = YouTubeAccountRules.accepted(YouTubeAccountRegistry(), capture, Account("Personal viewer", "person@example.invalid", null),
            YouTubeSessionMode.SIGN_IN, null)
        val restored = kotlinx.serialization.json.Json.decodeFromString<YouTubeAccountRegistry>(
            kotlinx.serialization.json.Json.encodeToString(YouTubeAccountRegistry.serializer(), registry))
        assertNull(restored.activeProfile!!.pageId)
        assertNull(restored.activeProfile!!.dataSyncId)
        assertEquals("3", restored.activeProfile!!.authUser)
        assertTrue(restored.activeAccount!!.capturedScope)
        assertEquals("visitor-fixture", restored.activeAccount!!.visitorData)
        assertEquals("1.20260401.01.00", restored.activeAccount!!.clientVersion)
    }
}
