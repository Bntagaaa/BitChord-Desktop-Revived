package com.music.bitchord.reworked

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface YouTubeAccountStore {
    fun read(): YouTubeAccountRegistry?
    fun write(value: YouTubeAccountRegistry)
    fun legacyCookie(): String? = null
    fun removeLegacyCookie() {}
}

internal interface YouTubeAccountBackend {
    /** Must not change the active Innertube session while validating a candidate. */
    suspend fun validate(capture: CapturedYouTubeSession): Account
    fun activate(account: GoogleAccountRecord?, profile: YouTubeProfileRecord?, capture: CapturedYouTubeSession? = null)
    suspend fun accountChannels(): List<AccountChannel>
    fun clearBrowserSession() {}
}

internal class MobileYouTubeAccountController(private val store: YouTubeAccountStore, private val backend: YouTubeAccountBackend) {
    private val lock = Mutex()
    private var registry = YouTubeAccountRegistry()
    private var loaded = false
    private var loadFailed = false
    private val mutable = MutableStateFlow(YouTubeAccountState())
    val state: StateFlow<YouTubeAccountState> = mutable.asStateFlow()

    /** Call before the root session starts any personalized requests. No network is performed. */
    suspend fun restore(guestOnly: Boolean = false): Result<Unit> = operation {
        if (loaded) return@operation
        try {
            registry = if (guestOnly) YouTubeAccountRegistry() else store.read() ?: legacyRegistry(store.legacyCookie()) ?: YouTubeAccountRegistry()
            require(registry.schema == 1) { "This account store was written by a newer version of Reworked" }
            require(registry.accounts.all { YouTubeCapture.hasApiSid(it.cookie) }) { "A saved YouTube account is incomplete" }
            require(registry.accounts.map { it.accountId }.distinct().size == registry.accounts.size && registry.accounts.all { account ->
                account.accountId.isNotBlank() && account.profiles.isNotEmpty() &&
                    account.profiles.map { it.profileId }.distinct().size == account.profiles.size &&
                    account.profiles.any { it.profileId == account.activeProfileId }
            }) { "The saved YouTube account identities are incomplete. Their file has been preserved." }
            require(if (registry.accounts.isEmpty()) registry.activeAccountId == null && registry.activeProfileId == null
                else registry.activeAccount != null && registry.activeProfile != null) { "The selected YouTube profile is incomplete. Its file has been preserved." }
            loaded = true
            apply(registry, null)
        } catch (error: Exception) { loadFailed = true; throw error }
    }

    suspend fun acceptCapture(capture: CapturedYouTubeSession, mode: YouTubeSessionMode, expectedAccountId: String? = null): Result<Unit> = operation {
        ready()
        require(capture.loggedIn && YouTubeCapture.hasApiSid(capture.cookie)) { "Finish signing in to YouTube Music first" }
        if (mode == YouTubeSessionMode.SWITCH_CHANNEL) {
            val expected = registry.accounts.firstOrNull { it.accountId == expectedAccountId }
                ?: error("The account being changed is no longer saved")
            val oldIndex = expected.profiles.firstOrNull { it.profileId == expected.activeProfileId }?.authUser
            require(oldIndex == null || oldIndex == capture.authUser) {
                if (capture.authUser == null) "The page has not exposed its Google account index yet. Wait a moment and confirm again."
                else "This is another Google account. Use Add account to save it separately."
            }
        }
        // Unlike the mobile global temporary assignment, validation is isolated.
        // Rejected/expired captures cannot affect ongoing playback or account writes.
        val account = withTimeout(20_000) { backend.validate(capture) }
        if (mode == YouTubeSessionMode.SWITCH_CHANNEL) {
            val previous = registry.accounts.first { it.accountId == expectedAccountId }
            fun email(value: String) = '@' in value && !value.startsWith('@')
            require(!email(previous.email) || !email(account.email) || previous.email.equals(account.email, true)) {
                "This is another Google account. Use Add account to save it separately."
            }
        }
        val next = YouTubeAccountRules.accepted(registry, capture, account, mode, expectedAccountId)
        currentCoroutineContext().ensureActive()
        store.write(next) // Persistence must succeed before any active session changes.
        registry = next
        apply(next, capture)
        runCatching { store.removeLegacyCookie() }
    }

    suspend fun selectProfile(accountId: String, profileId: String): Result<Unit> = operation {
        ready()
        if (registry.activeAccountId == accountId && registry.activeProfileId == profileId) return@operation
        val next = YouTubeAccountRules.select(registry, accountId, profileId)
        store.write(next); registry = next; apply(next, null)
    }

    suspend fun selectChannel(channelKey: String): Result<Unit> = operation {
        ready()
        val id = registry.activeAccountId ?: error("Sign in to choose a channel")
        val channel = mutable.value.channels.firstOrNull { it.key == channelKey }
            ?: error("Refresh this account's channel list first")
        val withChannel = YouTubeAccountRules.detected(registry, id, listOf(channel))
        val profile = YouTubeAccountRules.profileId(channel.pageId, channel.dataSyncId, channel.name)
        val next = YouTubeAccountRules.select(withChannel, id, profile)
        store.write(next); registry = next; apply(next, null)
    }

    suspend fun refreshChannels(): Result<Unit> = operation {
        ready()
        val id = registry.activeAccountId ?: return@operation
        mutable.update { it.copy(channelsLoading = true) }
        try {
            // The controller's lock serializes profile changes with this active-account lookup.
            val channels = withTimeout(20_000) { backend.accountChannels() }
            val next = YouTubeAccountRules.detected(registry, id, channels)
            if (next != registry) {
                store.write(next)
                val selectedChanged = registry.activeProfileId != next.activeProfileId
                registry = next
                if (selectedChanged) apply(next, null) else publish(next, increment = false)
            }
            mutable.update { it.copy(channels = channels) }
        } finally { mutable.update { it.copy(channelsLoading = false) } }
    }

    suspend fun removeAccount(accountId: String): Result<Unit> = operation {
        ready()
        val next = YouTubeAccountRules.remove(registry, accountId)
        if (next == registry) return@operation
        store.write(next)
        val activeChanged = next.activeAccountId != registry.activeAccountId
        registry = next
        if (activeChanged) apply(next, null) else publish(next, false)
        backend.clearBrowserSession()
        runCatching { store.removeLegacyCookie() }
    }

    suspend fun signOutCurrent(): Result<Unit> = mutable.value.activeAccountId?.let { removeAccount(it) } ?: Result.success(Unit)
    suspend fun stepProfile(forward: Boolean): Boolean {
        val snapshot = mutable.value
        val entries = snapshot.accounts.flatMap { account -> account.profiles.map { account.accountId to it.profileId } }
        val index = entries.indexOf(snapshot.activeAccountId to snapshot.activeProfileId)
        val next = entries.getOrNull(index + if (forward) 1 else -1) ?: return false
        return selectProfile(next.first, next.second).isSuccess
    }

    /** Only the explicit browser boundary receives cookie snapshots. UI state contains no credentials. */
    suspend fun browserSnapshot(accountId: String): Pair<String, String>? = lock.withLock {
        registry.accounts.firstOrNull { it.accountId == accountId }?.let { it.accountId to it.cookie }
    }

    /** Account writes must either finish under their starting identity or never be sent. */
    suspend fun <T> withCurrentProfile(revision: Long, action: suspend () -> T): T = lock.withLock {
        ready()
        if (revision != mutable.value.revision) throw CancellationException("The YouTube profile changed before this action could start")
        check(mutable.value.signedIn) { "Sign in to update your YouTube Music account" }
        action()
    }

    /** Includes guest reads. Repository parsers that seed LikeState must finish before a switch. */
    suspend fun <T> withListener(revision: Long, action: suspend () -> T): T = lock.withLock {
        ready()
        if (revision != mutable.value.revision) throw CancellationException("The YouTube profile changed before this request could start")
        action()
    }

    private fun ready() { check(loaded && !loadFailed) { "Saved YouTube accounts could not be opened. Their file has been preserved." } }
    private fun apply(next: YouTubeAccountRegistry, capture: CapturedYouTubeSession?) {
        backend.activate(next.activeAccount, next.activeProfile, capture)
        publish(next, increment = true)
    }
    private fun publish(next: YouTubeAccountRegistry, increment: Boolean) = mutable.update { before ->
        before.copy(accounts = next.accounts.map { YouTubeAccountSummary(it.accountId, it.name, it.email, it.profiles) },
            activeAccountId = next.activeAccountId, activeProfileId = next.activeProfileId,
            revision = before.revision + if (increment) 1 else 0, channels = if (increment) emptyList() else before.channels, error = null)
    }
    private suspend fun operation(block: suspend () -> Unit): Result<Unit> = lock.withLock {
        mutable.update { it.copy(busy = true, error = null) }
        try { block(); Result.success(Unit) }
        catch (timeout: TimeoutCancellationException) {
            val error = IllegalStateException("YouTube Music did not confirm the account in time. Try again.")
            mutable.update { it.copy(error = error.message) }
            Result.failure(error)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            // Exceptions never include raw endpoint bodies, cookies or captured ytcfg fields.
            mutable.update { it.copy(error = error.message?.take(220) ?: "Could not update the YouTube account") }
            Result.failure(error)
        } finally { mutable.update { it.copy(busy = false) } }
    }

    private fun legacyRegistry(cookie: String?): YouTubeAccountRegistry? {
        if (cookie == null || !YouTubeCapture.hasApiSid(cookie)) return null
        val profile = YouTubeProfileRecord(YouTubeAccountRules.profileId(null, null, "Saved account"), "Saved account")
        val account = GoogleAccountRecord(accountDigest(cookie).take(24), cookie, profiles = listOf(profile), activeProfileId = profile.profileId)
        // Existing Reworked-only single-cookie data is held in memory. A confirmed
        // capture/channel lookup writes the new registry; a failed capture never erases it.
        return YouTubeAccountRegistry(accounts = listOf(account), activeAccountId = account.accountId, activeProfileId = profile.profileId)
    }
}

internal object MobileYouTubeAccounts {
    private val controller by lazy { MobileYouTubeAccountController(DesktopYouTubeAccountStore(), OriginalYouTubeAccountBackend()) }
    val state get() = controller.state
    suspend fun restore() = controller.restore(guestOnly = OfflineSmoke.active)
    suspend fun acceptCapture(capture: CapturedYouTubeSession, mode: YouTubeSessionMode, expectedAccountId: String? = null) = controller.acceptCapture(capture, mode, expectedAccountId)
    suspend fun selectProfile(accountId: String, profileId: String) = controller.selectProfile(accountId, profileId)
    suspend fun refreshChannels() = controller.refreshChannels()
    suspend fun selectChannel(channelKey: String) = controller.selectChannel(channelKey)
    suspend fun removeAccount(accountId: String) = controller.removeAccount(accountId)
    suspend fun signOutCurrent() = controller.signOutCurrent()
    suspend fun stepProfile(forward: Boolean) = controller.stepProfile(forward)
    suspend fun browserSnapshot(accountId: String) = controller.browserSnapshot(accountId)
    suspend fun <T> withCurrentProfile(revision: Long, action: suspend () -> T): T {
        OfflineSmoke.requireNetwork()
        return controller.withCurrentProfile(revision, action)
    }
    suspend fun <T> withListener(revision: Long, action: suspend () -> T): T {
        OfflineSmoke.requireNetwork()
        return controller.withListener(revision, action)
    }
}

private class OriginalYouTubeAccountBackend : YouTubeAccountBackend {
    override suspend fun validate(capture: CapturedYouTubeSession): Account = YouTubeCapturedSessionValidator.validate(capture)
    override suspend fun accountChannels(): List<AccountChannel> = YtMusicRepository.accountChannels().getOrThrow()
    override fun clearBrowserSession() = DesktopBrowserAuth.clearGoogleSession()
    override fun activate(account: GoogleAccountRecord?, profile: YouTubeProfileRecord?, capture: CapturedYouTubeSession?) {
        Innertube.cookie = account?.cookie
        if (capture != null) Innertube.adoptSessionScope(capture.pageId, capture.dataSyncId, capture.authUser,
            capture.visitorData, capture.clientVersion, capture.loggedIn)
        else if (account?.capturedScope == true && profile != null) {
            // Personal viewers can have no channel id. selectChannel(null,null,...)
            // has no override in shared Innertube, so retain their captured account
            // index and client scope explicitly instead of silently returning to 0.
            Innertube.adoptSessionScope(profile.pageId, profile.dataSyncId, profile.authUser,
                account.visitorData, account.clientVersion, true)
        }
        Innertube.selectChannel(profile?.pageId, profile?.dataSyncId, profile?.authUser)
        com.music.bitchord.data.LikeState.clear()
        StreamResolver.onSessionChanged()
    }
}
