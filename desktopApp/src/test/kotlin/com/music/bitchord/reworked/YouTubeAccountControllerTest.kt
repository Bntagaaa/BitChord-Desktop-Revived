package com.music.bitchord.reworked

import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import kotlinx.coroutines.*
import kotlin.test.*

class YouTubeAccountControllerTest {
    @Test fun rejectedCaptureCannotChangeSavedOrActiveSession() = runBlocking {
        val fixture = controllerFixture()
        fixture.backend.validation = { error("This profile was not confirmed") }
        val before = fixture.controller.state.value
        assertTrue(fixture.controller.acceptCapture(testCapture(), YouTubeSessionMode.SIGN_IN).isFailure)
        assertEquals(fixture.initial, fixture.store.value)
        assertEquals(before.activeAccountId, fixture.controller.state.value.activeAccountId)
        assertEquals(before.revision, fixture.controller.state.value.revision)
        assertEquals(listOf("validate"), fixture.events)
        assertEquals(fixture.initial.activeAccount, fixture.backend.active)
    }

    @Test fun successfulCaptureValidatesThenPersistsThenActivates() = runBlocking {
        val fixture = controllerFixture()
        assertTrue(fixture.controller.acceptCapture(testCapture(pageId = "brand-new"), YouTubeSessionMode.SIGN_IN).isSuccess)
        assertEquals(listOf("validate", "write", "activate", "remove-legacy"), fixture.events)
        assertEquals(2, fixture.store.value!!.accounts.size)
        assertEquals("brand-new", fixture.controller.state.value.activeProfileId)
        assertEquals("0", fixture.controller.state.value.activeProfile!!.authUser)
        assertFalse(fixture.controller.state.value.toString().contains("fixture-secret"))
    }

    @Test fun failedPersistenceDoesNotInstallValidatedCandidate() = runBlocking {
        val fixture = controllerFixture()
        fixture.store.failWrite = true
        val revision = fixture.controller.state.value.revision
        assertTrue(fixture.controller.acceptCapture(testCapture(), YouTubeSessionMode.SIGN_IN).isFailure)
        assertEquals(listOf("validate", "write"), fixture.events)
        assertEquals(fixture.initial.activeAccount, fixture.backend.active)
        assertEquals(revision, fixture.controller.state.value.revision)
    }

    @Test fun closingBrowserDuringValidationDoesNotPersistOrActivateCapture() = runBlocking {
        val fixture = controllerFixture()
        val started = CompletableDeferred<Unit>()
        fixture.backend.validation = { started.complete(Unit); awaitCancellation() }
        val capture = launch { fixture.controller.acceptCapture(testCapture(), YouTubeSessionMode.SIGN_IN) }
        started.await(); capture.cancelAndJoin()
        assertEquals(listOf("validate"), fixture.events)
        assertFalse(fixture.controller.state.value.busy)
        assertEquals(fixture.initial, fixture.store.value)
    }

    @Test fun channelCaptureCannotBeSavedToRemovedOrDifferentGoogleAccount() = runBlocking {
        val fixture = controllerFixture()
        assertTrue(fixture.controller.acceptCapture(testCapture(authUser = "2"), YouTubeSessionMode.SWITCH_CHANNEL,
            fixture.initial.activeAccountId).isFailure)
        assertTrue(fixture.events.isEmpty())
        assertTrue(fixture.controller.acceptCapture(testCapture().copy(authUser = null), YouTubeSessionMode.SWITCH_CHANNEL,
            fixture.initial.activeAccountId).isFailure)
        assertTrue(fixture.events.isEmpty())
        assertTrue(fixture.controller.acceptCapture(testCapture(), YouTubeSessionMode.SWITCH_CHANNEL, "removed-account").isFailure)
        assertTrue(fixture.events.isEmpty())
        fixture.backend.validation = { Account("Different person", "different@example.invalid", null) }
        assertTrue(fixture.controller.acceptCapture(testCapture(), YouTubeSessionMode.SWITCH_CHANNEL, fixture.initial.activeAccountId).isFailure)
        assertEquals(listOf("validate"), fixture.events)
        assertEquals(fixture.initial, fixture.store.value)
    }

    @Test fun switchingProfileKeepsItsGoogleCookieAndIdentityTogether() = runBlocking {
        val fixture = controllerFixture()
        fixture.backend.validation = { Account("Brand channel", "old@example.invalid", null) }
        assertTrue(fixture.controller.acceptCapture(testCapture("SAPISID=rotated", pageId = "brand-2"),
            YouTubeSessionMode.SWITCH_CHANNEL, fixture.initial.activeAccountId).isSuccess)
        val accountId = fixture.initial.activeAccountId!!
        assertEquals(1, fixture.store.value!!.accounts.size)
        assertEquals(2, fixture.store.value!!.accounts.single().profiles.size)
        assertTrue(fixture.controller.selectProfile(accountId, "old-personal").isSuccess)
        assertEquals("SAPISID=rotated", fixture.backend.active!!.cookie)
        assertEquals("old-personal", fixture.backend.profile!!.dataSyncId)
        assertNull(fixture.backend.profile!!.pageId)
    }

    @Test fun detectedChannelsKeepCapturedIdentityAndInheritTheGoogleAccountIndex() = runBlocking {
        val fixture = controllerFixture()
        fixture.backend.channels = listOf(AccountChannel("Brand", "@brand", null, "brand-2", "brand-2", true))
        assertTrue(fixture.controller.refreshChannels().isSuccess)
        assertEquals("old-personal", fixture.controller.state.value.activeProfileId)
        assertEquals(2, fixture.controller.state.value.activeAccount!!.profiles.size)
        assertTrue(fixture.controller.selectChannel("brand-2").isSuccess)
        assertEquals("brand-2", fixture.backend.profile!!.pageId)
        assertEquals("0", fixture.backend.profile!!.authUser)
        assertTrue(fixture.controller.state.value.channels.isEmpty())
        fixture.backend.channels = emptyList()
        assertTrue(fixture.controller.refreshChannels().isSuccess)
        assertEquals(2, fixture.controller.state.value.activeAccount!!.profiles.size)
        assertEquals("brand-2", fixture.controller.state.value.activeProfileId)
    }

    @Test fun accountWritesFinishBeforeSwitchAndStaleWritesNeverStart() = runBlocking {
        val fixture = controllerFixture()
        fixture.backend.validation = { Account("Brand", "old@example.invalid", null) }
        fixture.controller.acceptCapture(testCapture(pageId = "brand-2"), YouTubeSessionMode.SWITCH_CHANNEL, fixture.initial.activeAccountId).getOrThrow()
        val revision = fixture.controller.state.value.revision
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val write = async {
            fixture.controller.withCurrentProfile(revision) { started.complete(Unit); release.await(); "written" }
        }
        started.await()
        val switching = async(start = CoroutineStart.UNDISPATCHED) { fixture.controller.selectProfile(fixture.initial.activeAccountId!!, "old-personal") }
        assertFalse(switching.isCompleted)
        release.complete(Unit)
        assertEquals("written", write.await()); assertTrue(switching.await().isSuccess)
        var executed = false
        assertFailsWith<CancellationException> { fixture.controller.withCurrentProfile(revision) { executed = true } }
        assertFalse(executed)
    }

    @Test fun removeInactiveAccountKeepsActiveProfileAndRemoveActiveFallsBack() = runBlocking {
        val fixture = controllerFixture()
        fixture.controller.acceptCapture(testCapture(pageId = "brand-new"), YouTubeSessionMode.SIGN_IN).getOrThrow()
        val active = fixture.controller.state.value.activeAccountId!!
        val revision = fixture.controller.state.value.revision
        fixture.controller.removeAccount(fixture.initial.activeAccountId!!).getOrThrow()
        assertEquals(active, fixture.controller.state.value.activeAccountId)
        assertEquals(revision, fixture.controller.state.value.revision)
        assertEquals(1, fixture.backend.browserClears)
        fixture.controller.signOutCurrent().getOrThrow()
        assertFalse(fixture.controller.state.value.signedIn)
        assertNull(fixture.backend.active)
        assertEquals(2, fixture.backend.browserClears)
    }

    @Test fun corruptedRegistryIsPreservedAndCannotBeOverwrittenByNewCapture() = runBlocking {
        val fixture = controllerFixture(restore = false)
        fixture.store.failRead = true
        assertTrue(fixture.controller.restore().isFailure)
        assertTrue(fixture.controller.acceptCapture(testCapture(), YouTubeSessionMode.SIGN_IN).isFailure)
        assertEquals(fixture.initial, fixture.store.value)
        assertEquals(emptyList(), fixture.events)
    }

    @Test fun legacyCookieIsOnlyMigratedAfterAcceptedAccountCapture() = runBlocking {
        val events = mutableListOf<String>()
        val store = TestAccountStore(null, events).apply { legacy = "SAPISID=legacy-fixture" }
        val backend = TestAccountBackend(events)
        val controller = MobileYouTubeAccountController(store, backend)
        controller.restore().getOrThrow()
        assertTrue(controller.state.value.signedIn)
        assertNull(store.value)
        assertNotNull(store.legacy)
        events.clear()
        controller.acceptCapture(testCapture(), YouTubeSessionMode.SIGN_IN).getOrThrow()
        assertNotNull(store.value)
        assertNull(store.legacy)
        assertEquals(listOf("validate", "write", "activate", "remove-legacy"), events)
    }

    @Test fun guestReadGateAllowsAnonymousPagesButRejectsAccountWritesAndStaleResults() = runBlocking {
        val events = mutableListOf<String>()
        val controller = MobileYouTubeAccountController(TestAccountStore(null, events), TestAccountBackend(events))
        controller.restore().getOrThrow()
        val guest = controller.state.value.revision
        assertEquals("public home", controller.withListener(guest) { "public home" })
        assertFailsWith<IllegalStateException> { controller.withCurrentProfile(guest) { "must not send" } }
        controller.acceptCapture(testCapture(), YouTubeSessionMode.SIGN_IN).getOrThrow()
        assertFailsWith<CancellationException> { controller.withListener(guest) { "old public result" } }
        Unit
    }

    @Test fun explicitGuestRestoreNeverOpensSavedAccountsOrLegacyCookie() = runBlocking {
        val events = mutableListOf<String>()
        val store = TestAccountStore(null, events).apply { failRead = true; legacy = "SAPISID=must-not-load" }
        val backend = TestAccountBackend(events)
        val controller = MobileYouTubeAccountController(store, backend)
        controller.restore(guestOnly = true).getOrThrow()
        assertFalse(controller.state.value.signedIn)
        assertNull(backend.active)
        assertEquals("SAPISID=must-not-load", store.legacy)
        assertEquals(listOf("activate"), events)
    }
}

private data class ControllerFixture(val controller: MobileYouTubeAccountController, val store: TestAccountStore,
    val backend: TestAccountBackend, val initial: YouTubeAccountRegistry, val events: MutableList<String>)

private suspend fun controllerFixture(restore: Boolean = true): ControllerFixture {
    val initial = YouTubeAccountRules.accepted(YouTubeAccountRegistry(), testCapture("SAPISID=old-fixture", identity = "old-personal"),
        Account("Old listener", "old@example.invalid", null), YouTubeSessionMode.SIGN_IN, null)
    val events = mutableListOf<String>(); val store = TestAccountStore(initial, events); val backend = TestAccountBackend(events)
    val controller = MobileYouTubeAccountController(store, backend)
    if (restore) controller.restore().getOrThrow()
    events.clear()
    return ControllerFixture(controller, store, backend, initial, events)
}

private class TestAccountStore(var value: YouTubeAccountRegistry?, private val events: MutableList<String>) : YouTubeAccountStore {
    var failWrite = false; var failRead = false; var legacy: String? = null
    override fun read(): YouTubeAccountRegistry? { check(!failRead) { "Unreadable saved account file" }; return value }
    override fun write(value: YouTubeAccountRegistry) { events += "write"; check(!failWrite) { "Account file could not be saved" }; this.value = value }
    override fun legacyCookie() = legacy
    override fun removeLegacyCookie() { events += "remove-legacy"; legacy = null }
}

private class TestAccountBackend(private val events: MutableList<String>) : YouTubeAccountBackend {
    var validation: suspend (CapturedYouTubeSession) -> Account = { Account("New listener", "new@example.invalid", null) }
    var active: GoogleAccountRecord? = null; var profile: YouTubeProfileRecord? = null
    var channels: List<AccountChannel> = emptyList(); var browserClears = 0
    override suspend fun validate(capture: CapturedYouTubeSession): Account { events += "validate"; return validation(capture) }
    override fun activate(account: GoogleAccountRecord?, profile: YouTubeProfileRecord?, capture: CapturedYouTubeSession?) {
        events += "activate"; active = account; this.profile = profile
    }
    override suspend fun accountChannels() = channels
    override fun clearBrowserSession() { browserClears++ }
}
