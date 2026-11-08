package com.music.bitchord.data.canvas

import com.music.bitchord.reworked.DesktopBrowserAuth
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/** Client-token protocol ported from the original mobile SpotifyToken.kt at v1.9.1. */
internal object SpotifyToken {
    private const val TAG = "SpotifyToken"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    @Volatile private var cachedSession: SessionInfo? = null
    @Volatile private var cachedClientToken: String? = null
    @Volatile private var clientTokenExpiresAtMs = 0L
    private data class SessionInfo(val clientVersion: String, val deviceId: String)
    suspend fun accessToken(): String? = DesktopBrowserAuth.spotifyTokens()?.bearer
    fun invalidate() { cachedClientToken = null; clientTokenExpiresAtMs = 0 }

    @Synchronized
    fun clientToken(): String? {
        val now = System.currentTimeMillis()
        cachedClientToken?.let { if (now < clientTokenExpiresAtMs - 30_000) return it }

        val clientId = DesktopBrowserAuth.spotifyClientId()
        if (clientId == null) {
            Log.w(TAG, "no client id yet (access token not minted); skipping client token")
            return null
        }
        val session = session() ?: return null

        val payload = buildJsonObject {
            putJsonObject("client_data") {
                put("client_version", session.clientVersion)
                put("client_id", clientId)
                putJsonObject("js_sdk_data") {
                    put("device_brand", "unknown")
                    put("device_model", "unknown")
                    put("os", if (System.getProperty("os.name").startsWith("Windows")) "windows" else "linux")
                    put("os_version", System.getProperty("os.version").orEmpty())
                    put("device_id", session.deviceId)
                    put("device_type", "computer")
                }
            }
        }

        // The ByteArray overload, deliberately: the String overload of
        // toRequestBody rewrites a charset-less MediaType to
        // "application/json; charset=utf-8", and clienttoken 400s on that
        // exact header rather than the bare "application/json" the real web
        // player sends.
        val request = Request.Builder()
            .url("https://clienttoken.spotify.com/v1/clienttoken")
            .post(payload.toString().toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("User-Agent", CANVAS_UA)
            .build()

        var lastCode = -1
        val body = runCatching {
            Http.client.newCall(request).execute().use { response ->
                lastCode = response.code
                if (response.isSuccessful) response.body?.string() else null
            }
        }.onFailure { Log.w(TAG, "client-token request threw: ${it.message}") }.getOrNull()
        if (body == null) {
            Log.w(TAG, "client-token request failed, http $lastCode")
            return null
        }

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (root == null) {
            Log.w(TAG, "client-token response wasn't JSON")
            return null
        }
        val responseType = root["response_type"]?.jsonPrimitive?.contentOrNull
        if (responseType != "RESPONSE_GRANTED_TOKEN_RESPONSE") {
            Log.w(TAG, "client-token request rejected: $responseType")
            return null
        }
        val granted = root["granted_token"]?.jsonObject
        val token = granted?.get("token")?.jsonPrimitive?.contentOrNull
        if (token == null) {
            Log.w(TAG, "client-token response had no granted_token.token")
            return null
        }
        val ttlSeconds = granted["expires_after_seconds"]?.jsonPrimitive?.contentOrNull
            ?.toLongOrNull() ?: 3600L

        cachedClientToken = token
        clientTokenExpiresAtMs = now + ttlSeconds * 1000
        Log.d(TAG, "minted client token, good for ${ttlSeconds}s")
        return token
    }

    /**
     * [SessionInfo.clientVersion] and [SessionInfo.deviceId] both come off the
     * plain, unauthenticated web player page — the client version from a JSON
     * blob it embeds for itself, the device id from the `sp_t` cookie it hands
     * out to every visitor. Fetched once and held for the process: neither
     * changes inside a session.
     */
    private fun session(): SessionInfo? {
        cachedSession?.let { return it }

        val request = Request.Builder()
            .url("https://open.spotify.com")
            .header("User-Agent", CANVAS_UA)
            .build()
        val (html, deviceId) = runCatching {
            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                val spT = response.headers("Set-Cookie").firstNotNullOfOrNull { header ->
                    Regex("""sp_t=([^;]+)""").find(header)?.groupValues?.get(1)
                }
                body to spT
            }
        }.getOrNull() ?: (null to null)
        if (html == null) {
            Log.w(TAG, "couldn't load the web player page for session info")
            return null
        }

        val configB64 = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""")
            .find(html)?.groupValues?.get(1)
        if (configB64 == null) {
            Log.w(TAG, "web player page had no appServerConfig block")
            return null
        }
        val clientVersion = runCatching {
            val configJson = String(Base64.getDecoder().decode(configB64), Charsets.UTF_8)
            json.parseToJsonElement(configJson).jsonObject["clientVersion"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (clientVersion == null) {
            Log.w(TAG, "appServerConfig had no clientVersion")
            return null
        }

        val session = SessionInfo(clientVersion, deviceId ?: java.util.UUID.randomUUID().toString())
        cachedSession = session
        return session
    }
}
