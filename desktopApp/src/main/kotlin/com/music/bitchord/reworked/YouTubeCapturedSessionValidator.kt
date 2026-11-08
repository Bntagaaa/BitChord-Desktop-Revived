package com.music.bitchord.reworked

import com.music.bitchord.data.Http
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.Account
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Original shared Innertube.postMusic/account_menu/SAPISIDHASH contract, with an
 * isolated candidate scope. Validation does not install candidate credentials in
 * the process-wide Innertube singleton before they have been accepted and saved.
 */
internal object YouTubeCapturedSessionValidator {
    internal data class CandidateRequest(val url: String, val headers: Map<String, String>, val body: String) {
        override fun toString() = "YouTube account validation request [credentials redacted]"
    }
    private const val ORIGIN = "https://music.youtube.com"
    private const val MAX_BODY = 2 * 1024 * 1024
    private val client by lazy { Http.client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS).build() }

    fun request(capture: CapturedYouTubeSession, timestamp: Long = System.currentTimeMillis() / 1000,
        language: String = Innertube.currentLanguage, region: String = Innertube.currentRegion): CandidateRequest {
        require(capture.loggedIn) { "This YouTube Music page is signed out" }
        val secret = YouTubeCapture.signingSecret(capture.cookie) ?: error("The page does not have a signed-in cookie")
        val version = capture.clientVersion?.takeIf { it.matches(Regex("[0-9A-Za-z._-]{1,100}")) }
            ?: error("YouTube Music is still loading its client identity. Wait a moment and choose the profile again.")
        require(capture.authUser == null || capture.authUser.matches(Regex("[0-9]{1,8}"))) { "Invalid Google account index" }
        listOf(capture.pageId, capture.dataSyncId, capture.visitorData).filterNotNull().forEach {
            require(it.length <= 8192 && it.none { char -> char.code < 32 || char.code == 127 }) { "Invalid captured account identity" }
        }
        val digest = MessageDigest.getInstance("SHA-1").digest("$timestamp $secret $ORIGIN".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        val headers = linkedMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
            "X-Origin" to ORIGIN, "Origin" to ORIGIN, "Referer" to "$ORIGIN/",
            "X-YouTube-Client-Name" to "67", "X-YouTube-Client-Version" to version,
            "Cookie" to capture.cookie, "X-Goog-AuthUser" to (capture.authUser ?: "0"),
            "Authorization" to "SAPISIDHASH ${timestamp}_$digest",
        )
        capture.pageId?.let { headers["X-Goog-PageId"] = it }
        capture.visitorData?.let { headers["X-Goog-Visitor-Id"] = it }
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX"); put("clientVersion", version); put("hl", language); put("gl", region)
                    capture.visitorData?.let { put("visitorData", it) }
                }
                putJsonObject("user") {
                    put("lockedSafetyMode", false)
                    capture.dataSyncId?.let { put("onBehalfOfUser", it) }
                }
                putJsonObject("request") { put("useSsl", true) }
            }
        }.toString()
        return CandidateRequest("$ORIGIN/youtubei/v1/account/account_menu?prettyPrint=false", headers, body)
    }

    suspend fun validate(capture: CapturedYouTubeSession, perform: suspend (CandidateRequest) -> JsonObject = ::execute): Account {
        val request = request(capture)
        var failure: Exception? = null
        repeat(2) { attempt ->
            if (attempt > 0) delay(750)
            try {
                return InnertubeParser.parseAccount(perform(request))
                    ?: error("YouTube Music could not confirm this signed-in profile. Finish choosing the account in the browser.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error }
        }
        throw failure ?: IllegalStateException("YouTube Music could not confirm this profile")
    }

    private suspend fun execute(candidate: CandidateRequest): JsonObject = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(candidate.url)
            .post(candidate.body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { candidate.headers.forEach { (name, value) -> header(name, value) } }.build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Could not reach YouTube Music to confirm this profile"))
            }
            override fun onResponse(call: Call, response: Response) {
                val answer = runCatching { response.use {
                    check(it.isSuccessful) { "YouTube Music did not confirm this profile (HTTP ${it.code})" }
                    val body = it.body
                    check(body.contentLength() <= MAX_BODY) { "The account response was too large" }
                    val bytes = body.byteStream().use { stream -> stream.readNBytes(MAX_BODY + 1) }
                    check(bytes.size <= MAX_BODY) { "The account response was too large" }
                    runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
                        .getOrElse { error("YouTube Music returned an unreadable account response") }
                } }
                if (continuation.isActive) answer.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }
}
