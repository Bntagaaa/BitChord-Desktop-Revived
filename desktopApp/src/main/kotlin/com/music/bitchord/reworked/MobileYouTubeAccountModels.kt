package com.music.bitchord.reworked

import com.music.bitchord.auth.normalizeDataSyncId
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Direct model/identity rules from original mobile auth/AccountSessions.kt and WebSession.kt. */
internal enum class YouTubeSessionMode { SIGN_IN, SWITCH_CHANNEL }

internal data class CapturedYouTubeSession(
    val cookie: String,
    val pageId: String?,
    val dataSyncId: String?,
    val authUser: String?,
    val visitorData: String?,
    val clientVersion: String?,
    val loggedIn: Boolean,
) {
    override fun toString() = "CapturedYouTubeSession(cookie=[redacted], identity=${if (pageId == null) "personal" else "brand"}, loggedIn=$loggedIn)"
}

@Serializable
internal data class YouTubeProfileRecord(
    val profileId: String,
    val name: String,
    val handle: String = "",
    val avatar: String? = null,
    val pageId: String? = null,
    val dataSyncId: String? = null,
    val authUser: String? = null,
    val isBrandAccount: Boolean = false,
)

@Serializable
internal data class GoogleAccountRecord(
    val accountId: String,
    val cookie: String,
    val name: String = "",
    val email: String = "",
    val profiles: List<YouTubeProfileRecord> = emptyList(),
    val activeProfileId: String? = null,
    val visitorData: String? = null,
    val clientVersion: String? = null,
    val capturedScope: Boolean = false,
) {
    override fun toString() = "GoogleAccountRecord(accountId=$accountId, cookie=[redacted], profiles=${profiles.size})"
}

@Serializable
internal data class YouTubeAccountRegistry(
    val schema: Int = 1,
    val accounts: List<GoogleAccountRecord> = emptyList(),
    val activeAccountId: String? = null,
    val activeProfileId: String? = null,
) {
    val activeAccount get() = accounts.firstOrNull { it.accountId == activeAccountId }
    val activeProfile get() = activeAccount?.profiles?.firstOrNull { it.profileId == activeProfileId }
}

internal data class YouTubeAccountSummary(val accountId: String, val name: String, val email: String, val profiles: List<YouTubeProfileRecord>)
internal data class YouTubeAccountState(
    val accounts: List<YouTubeAccountSummary> = emptyList(),
    val activeAccountId: String? = null,
    val activeProfileId: String? = null,
    val revision: Long = 0,
    val busy: Boolean = false,
    val channels: List<AccountChannel> = emptyList(),
    val channelsLoading: Boolean = false,
    val error: String? = null,
) {
    val activeAccount get() = accounts.firstOrNull { it.accountId == activeAccountId }
    val activeProfile get() = activeAccount?.profiles?.firstOrNull { it.profileId == activeProfileId }
    val signedIn get() = activeAccount != null && activeProfile != null
    /** This key partitions account-owned likes/history and fences asynchronous page results. */
    val listenerKey get() = if (signedIn) accountDigest("$activeAccountId\u0000$activeProfileId").take(32) else "guest"
}

internal object YouTubeCapture {
    private val sidNames = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")
    fun cookieEntries(header: String): Map<String, String> {
        // Reject unsupported header characters before an HTTP builder can put
        // the rejected Cookie value into an exception message.
        require(header.length <= 128 * 1024 && header.all { it.code in 32..126 }) { "Invalid YouTube cookie header" }
        return header.split(';').mapNotNull { entry ->
            val name = entry.substringBefore('=').trim()
            val value = entry.substringAfter('=', "").trim()
            if (name.isBlank() || value.isBlank()) null else {
                require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "Invalid YouTube cookie name" }
                name to value
            }
        }.toMap()
    }
    fun signingSecret(header: String): String? = cookieEntries(header).let { jar -> sidNames.firstNotNullOfOrNull { jar[it] } }
    fun hasApiSid(header: String): Boolean = runCatching { signingSecret(header) != null }.getOrDefault(false)
    fun isMusicOrigin(address: String): Boolean = runCatching {
        val uri = URI(address)
        uri.scheme.equals("https", true) && uri.host.equals("music.youtube.com", true) && uri.port in listOf(-1, 443) && uri.rawUserInfo == null
    }.getOrDefault(false)
    fun parse(address: String, cookie: String, probe: String?): CapturedYouTubeSession {
        require(isMusicOrigin(address)) { "Finish signing in on music.youtube.com before choosing this profile" }
        require(hasApiSid(cookie)) { "Finish signing in to YouTube Music first" }
        require(probe != null && probe.length <= 64 * 1024) { "The YouTube Music page is still loading" }
        val config = runCatching { Json.parseToJsonElement(probe).jsonObject }.getOrNull()
            ?: error("The YouTube Music page has not exposed its account identity yet")
        require((config["loggedIn"] as? JsonPrimitive)?.content == "true") { "This YouTube Music page is signed out" }
        fun field(key: String): String? = (config[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            ?.also { require(it.length <= 8192 && it.none { char -> char.code < 32 || char.code == 127 }) { "The page returned an invalid account field" } }
        val pageId = field("pageId")
        val authUser = field("authUser")?.also { require(it.matches(Regex("[0-9]{1,8}"))) { "The page returned an invalid account index" } }
        return CapturedYouTubeSession(cookie, pageId, pageId ?: normalizeDataSyncId(field("dataSyncId")), authUser,
            field("visitorData"), field("clientVersion"), true)
    }

    /** Original mobile YTCFG_PROBE, serialized once for JavaFX's executeScript return value. */
    val probeScript = """
        (function(){try{
          if(!window.ytcfg || !window.ytcfg.get) return null;
          var get=function(key){var value=window.ytcfg.get(key);return value===undefined||value===null||value===''?null:String(value);};
          return JSON.stringify({loggedIn:String(!!window.ytcfg.get('LOGGED_IN')),
            pageId:get('DELEGATED_SESSION_ID'),dataSyncId:get('DATASYNC_ID'),authUser:get('SESSION_INDEX'),
            visitorData:get('VISITOR_DATA'),clientVersion:get('INNERTUBE_CLIENT_VERSION')});
        }catch(e){return null;}})()
    """.trimIndent()
}

internal object YouTubeAccountRules {
    fun profileId(pageId: String?, dataSyncId: String?, name: String): String =
        pageId ?: dataSyncId ?: "profile:${accountDigest(name).take(16)}"

    fun accepted(old: YouTubeAccountRegistry, capture: CapturedYouTubeSession, account: Account,
        mode: YouTubeSessionMode, expectedAccountId: String?): YouTubeAccountRegistry {
        require(capture.loggedIn && YouTubeCapture.hasApiSid(capture.cookie)) { "The captured session is not signed in" }
        val accountId = when (mode) {
            YouTubeSessionMode.SWITCH_CHANNEL -> expectedAccountId?.takeIf { id -> old.accounts.any { it.accountId == id } }
                ?: error("The account being changed is no longer saved. Add it again instead.")
            YouTubeSessionMode.SIGN_IN -> old.accounts.firstOrNull {
                account.email.isNotBlank() && it.email.equals(account.email, true)
            }?.accountId ?: accountDigest(capture.cookie).take(24)
        }
        val previous = old.accounts.firstOrNull { it.accountId == accountId }
        val profile = YouTubeProfileRecord(profileId(capture.pageId, capture.dataSyncId, account.name), account.name,
            account.email, account.thumbnailUrl, capture.pageId, capture.dataSyncId, capture.authUser, capture.pageId != null)
        val real = profile.pageId != null || profile.dataSyncId != null
        val profiles = previous?.profiles.orEmpty().filterNot { it.profileId == profile.profileId || (real && it.profileId.startsWith("profile:")) }
            .map { it.copy(authUser = capture.authUser) } + profile
        val stored = GoogleAccountRecord(accountId, capture.cookie, account.name, account.email, profiles, profile.profileId,
            capture.visitorData, capture.clientVersion, capturedScope = true)
        val next = if (previous == null) old.accounts + stored else old.accounts.map { if (it.accountId == accountId) stored else it }
        return old.copy(accounts = next, activeAccountId = accountId, activeProfileId = profile.profileId)
    }

    fun detected(registry: YouTubeAccountRegistry, accountId: String, channels: List<AccountChannel>): YouTubeAccountRegistry {
        val account = registry.accounts.firstOrNull { it.accountId == accountId } ?: return registry
        val current = account.profiles.firstOrNull { it.profileId == account.activeProfileId }
        val found = channels.map { channel -> YouTubeProfileRecord(profileId(channel.pageId, channel.dataSyncId, channel.name),
            channel.name, channel.subtitle, channel.thumbnailUrl, channel.pageId, channel.dataSyncId, current?.authUser, channel.pageId != null) }
        if (found.isEmpty()) return registry // A partial endpoint must not erase the captured active identity.
        val stale = account.profiles.filter { it.profileId.startsWith("profile:") }.mapTo(HashSet()) { it.profileId }
        val profiles = account.profiles.filter { old -> old.profileId !in stale && found.none { it.profileId == old.profileId } } + found
        val selected = account.activeProfileId?.takeIf { id -> profiles.any { it.profileId == id } }
            ?: found.singleOrNull()?.profileId ?: profiles.firstOrNull()?.profileId
        val updated = account.copy(profiles = profiles, activeProfileId = selected)
        return registry.copy(accounts = registry.accounts.map { if (it.accountId == accountId) updated else it },
            activeProfileId = if (registry.activeAccountId == accountId) selected else registry.activeProfileId)
    }

    fun select(registry: YouTubeAccountRegistry, accountId: String, profileId: String): YouTubeAccountRegistry {
        val account = registry.accounts.firstOrNull { it.accountId == accountId } ?: error("This account has been removed")
        require(account.profiles.any { it.profileId == profileId }) { "This profile is no longer saved" }
        return registry.copy(accounts = registry.accounts.map { if (it.accountId == accountId) it.copy(activeProfileId = profileId) else it },
            activeAccountId = accountId, activeProfileId = profileId)
    }
    fun remove(registry: YouTubeAccountRegistry, accountId: String): YouTubeAccountRegistry {
        val remaining = registry.accounts.filterNot { it.accountId == accountId }
        if (accountId != registry.activeAccountId) return registry.copy(accounts = remaining)
        val next = remaining.firstOrNull()
        val profile = next?.activeProfileId?.takeIf { id -> next.profiles.any { it.profileId == id } } ?: next?.profiles?.firstOrNull()?.profileId
        return registry.copy(accounts = remaining, activeAccountId = next?.accountId, activeProfileId = profile)
    }
    fun adjacent(registry: YouTubeAccountRegistry, forward: Boolean): Pair<String, String>? {
        val entries = registry.accounts.flatMap { account -> account.profiles.map { account.accountId to it.profileId } }
        val index = entries.indexOf(registry.activeAccountId to registry.activeProfileId)
        return entries.getOrNull(index + if (forward) 1 else -1)
    }
}

internal fun accountDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it) }
