package com.music.bitchord.reworked

import com.music.bitchord.data.Http
import com.music.bitchord.data.innertube.potoken.*
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.scene.web.WebView
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import netscape.javascript.JSObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** New JavaFX boundary for the original mobile PoTokenWebView challenge sequence. */
internal class MobilePoToken private constructor() : PoTokenMinter {
    private var web: WebView? = null
    private val loaded = CompletableDeferred<Unit>()
    private val guard = CompletableDeferred<String>()
    private val ready = CompletableDeferred<Unit>()
    private val replies = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val sequence = AtomicLong()
    @Volatile private var dead = false
    @Volatile private var expires = Instant.EPOCH
    // Strong reference: the JavaFX JS bridge otherwise holds its Java object weakly.
    private val bridge = PoTokenBridge(
        guard = { guard.complete(it) }, ready = { ready.complete(Unit) },
        error = { message ->
            val error = PoTokenException(message.take(300))
            dead = true; guard.completeExceptionally(error); ready.completeExceptionally(error)
            replies.values.forEach { it.completeExceptionally(error) }
        },
        result = { key, bytes -> replies.remove(key)?.complete(u8ToBase64(bytes)) },
        tokenError = { key, message -> replies.remove(key)?.completeExceptionally(PoTokenException(message.take(300))) },
    )
    override val isExpired: Boolean get() = Instant.now().isAfter(expires)
    override val isDead: Boolean get() = dead

    private suspend fun initialize() {
        DesktopBrowserAuth.ensureStarted()
        onFx {
            val view = WebView(); web = view
            view.engine.loadWorker.stateProperty().addListener { _, _, state ->
                if (state == Worker.State.SUCCEEDED) loaded.complete(Unit)
                if (state == Worker.State.FAILED || state == Worker.State.CANCELLED) loaded.completeExceptionally(PoTokenException("Challenge page could not load"))
            }
            view.engine.loadContent(PO_TOKEN_HTML, "text/html")
        }
        loaded.await()
        onFx { (web!!.engine.executeScript("window") as JSObject).setMember("ReworkedPoToken", bridge) }
        val challenge = parseChallengeData(request("Create", "[\"$REQUEST_KEY\"]"))
        onFx { web!!.engine.executeScript("""
            runBotGuard($challenge).then(function(result) {
                window.webPoSignalOutput = result.webPoSignalOutput;
                ReworkedPoToken.onGuard(result.botguardResponse);
            }).catch(function(error) { ReworkedPoToken.onError(String(error)); });
        """) }
        val response = guard.await()
        val (integrity, lifetime) = parseIntegrityTokenData(request("GenerateIT", "[${JsonPrimitive(REQUEST_KEY)},${JsonPrimitive(response)}]"))
        expires = Instant.now().plusSeconds((lifetime - 600).coerceAtLeast(1))
        onFx { web!!.engine.executeScript("""
            createPoTokenMinter(window.webPoSignalOutput, $integrity).then(function() {
                ReworkedPoToken.onReady();
            }).catch(function(error) { ReworkedPoToken.onError(String(error)); });
        """) }
        ready.await()
    }

    override suspend fun generatePoToken(identifier: String): String {
        check(!dead && !isExpired) { "Challenge session expired" }
        val key = sequence.incrementAndGet().toString()
        val reply = CompletableDeferred<String>(); replies[key] = reply
        return try {
            withTimeout(15_000) {
                val bytes = stringToU8(identifier)
                onFx { web!!.engine.executeScript("""
                    obtainPoToken($bytes).then(function(token) {
                        ReworkedPoToken.onToken('$key', token.join(','));
                    }).catch(function(error) { ReworkedPoToken.onTokenError('$key', String(error)); });
                """) }
                reply.await()
            }
        } finally { replies.remove(key) }
    }

    override fun close() {
        if (dead && web == null) return
        dead = true
        replies.values.forEach { it.cancel() }; replies.clear()
        runCatching { Platform.runLater { web?.engine?.load(null); web = null } }
    }

    companion object {
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        suspend fun create(): MobilePoToken {
            val minter = MobilePoToken()
            try { withTimeout(45_000) { minter.initialize() }; return minter }
            catch (error: Throwable) { minter.close(); throw error }
        }
        private suspend fun request(method: String, body: String) = withContext(Dispatchers.IO) {
            val request = Request.Builder().url("https://www.youtube.com/api/jnn/v1/$method")
                .post(body.toByteArray(Charsets.UTF_8).toRequestBody("application/json+protobuf".toMediaType()))
                .header("Accept", "application/json").header("x-goog-api-key", GOOGLE_API_KEY)
                .header("x-user-agent", "grpc-web-javascript/0.1")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3").build()
            Http.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Challenge service returned HTTP ${response.code}" }
                response.body?.string()?.takeIf(String::isNotBlank) ?: error("Empty challenge response")
            }
        }
    }
}

internal suspend fun <T> onFx(block: () -> T): T = suspendCancellableCoroutine { continuation ->
    Platform.runLater {
        if (continuation.isActive) try { continuation.resume(block()) } catch (error: Throwable) { continuation.resumeWithException(error) }
    }
}

/** Public methods are required by WebKit's Java-to-JavaScript bridge. */
class PoTokenBridge internal constructor(
    private val guard: (String) -> Unit,
    private val ready: () -> Unit,
    private val error: (String) -> Unit,
    private val result: (String, String) -> Unit,
    private val tokenError: (String, String) -> Unit,
) {
    fun onGuard(value: String) = guard(value)
    fun onReady() = ready()
    fun onError(message: String) = error(message)
    fun onToken(key: String, value: String) = result(key, value)
    fun onTokenError(key: String, message: String) = tokenError(key, message)
}
