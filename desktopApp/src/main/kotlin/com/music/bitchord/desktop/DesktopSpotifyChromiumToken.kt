package com.music.bitchord.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Fallback when JavaFX WebKit cannot run Spotify's modern web player.
 *
 * Runs the user's installed Chrome/Edge/Chromium in a short-lived, isolated headless profile.
 * Its cookie is supplied through the loopback-only Chrome DevTools Protocol (CDP), never through
 * command-line arguments or log output. We listen for the real /api/token network response;
 * no TOTP signing or proprietary token construction is attempted.
 */
internal object DesktopSpotifyChromiumToken {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun harvest(cookie: String): String? = withContext(Dispatchers.IO) {
        val binary = findChromium() ?: return@withContext null
        val profile = runCatching { Files.createTempDirectory("bitchord-spotify-chromium-") }
            .getOrNull() ?: return@withContext null
        var browser: Process? = null
        var protocol: Cdp? = null
        try {
            browser = ProcessBuilder(
                binary.absolutePath,
                "--headless=new",
                "--disable-gpu",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-extensions",
                "--remote-debugging-address=127.0.0.1",
                "--remote-debugging-port=0",
                "--remote-allow-origins=http://localhost",
                "--user-data-dir=" + profile.toAbsolutePath(),
                "about:blank",
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()

            val port = waitForPort(profile, browser) ?: return@withContext null
            val http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build()
            val metadata = http.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:" + port + "/json/version"))
                    .timeout(Duration.ofSeconds(4)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (metadata.statusCode() !in 200..299) return@withContext null
            val wsUrl = json.parseToJsonElement(metadata.body()).jsonObject
                .get("webSocketDebuggerUrl")?.jsonPrimitive?.contentOrNull
                ?: return@withContext null

            val cdp = Cdp(http, wsUrl)
            protocol = cdp
            val target = cdp.command(
                "Target.createTarget", buildJsonObject { put("url", "about:blank") },
            )["targetId"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
            val session = cdp.command(
                "Target.attachToTarget",
                buildJsonObject {
                    put("targetId", target)
                    put("flatten", true)
                },
            )["sessionId"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
            cdp.command("Network.enable", sessionId = session)
            cdp.command("Page.enable", sessionId = session)

            val applied = cdp.command(
                "Network.setCookie",
                buildJsonObject {
                    put("name", "sp_dc")
                    put("value", cookie)
                    put("url", "https://open.spotify.com/")
                    put("domain", ".spotify.com")
                    put("path", "/")
                    put("secure", true)
                    put("httpOnly", true)
                },
                session,
            )["success"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            if (!applied) return@withContext null

            cdp.command(
                "Page.navigate", buildJsonObject { put("url", "https://open.spotify.com/") },
                session,
            )

            val pending = HashSet<String>()
            val completed = HashSet<String>()
            val end = System.nanoTime() + Duration.ofSeconds(16).toNanos()
            while (System.nanoTime() < end && browser.isAlive) {
                val event = cdp.pollEvent(400) ?: continue
                if (event["sessionId"]?.jsonPrimitive?.contentOrNull != session) continue
                val method = event["method"]?.jsonPrimitive?.contentOrNull ?: continue
                val parameters = event["params"]?.jsonObject ?: continue
                val requestId = parameters["requestId"]?.jsonPrimitive?.contentOrNull ?: continue
                when (method) {
                    "Network.responseReceived" -> {
                        val url = parameters["response"]?.jsonObject
                            ?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (url.startsWith("https://open.spotify.com/api/token?") ||
                            url.startsWith("https://open.spotify.com/get_access_token?")
                        ) pending.add(requestId)
                    }
                    "Network.loadingFinished" -> {
                        completed.add(requestId)
                        if (requestId !in pending) continue
                        val response = runCatching {
                            cdp.command(
                                "Network.getResponseBody",
                                buildJsonObject { put("requestId", requestId) },
                                session,
                            )
                        }.getOrNull() ?: continue
                        val raw = response["body"]?.jsonPrimitive?.contentOrNull ?: continue
                        val encoded = response["base64Encoded"]
                            ?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                        val body = if (encoded) {
                            runCatching {
                                String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
                            }.getOrNull() ?: continue
                        } else raw
                        val root = runCatching { json.parseToJsonElement(body).jsonObject }
                            .getOrNull() ?: continue
                        val token = root["accessToken"]?.jsonPrimitive?.contentOrNull
                        val anonymous = root["isAnonymous"]?.jsonPrimitive?.contentOrNull
                            ?.toBooleanStrictOrNull() ?: false
                        if (!token.isNullOrBlank() && !anonymous) {
                            DesktopTrackLog.log("spotify: authenticated token read from Chromium")
                            return@withContext body
                        }
                    }
                    else -> Unit
                }
            }
            null
        } catch (failure: Exception) {
            // No URLs, cookies, token responses, or command arguments in the application log.
            DesktopTrackLog.log("spotify: Chromium fallback failed (" +
                failure.javaClass.simpleName + ")")
            null
        } finally {
            runCatching { protocol?.close() }
            browser?.let { process ->
                runCatching {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(2, TimeUnit.SECONDS)
                    }
                }
            }
            runCatching { profile.toFile().deleteRecursively() }
        }
    }

    private fun waitForPort(profile: Path, process: Process): Int? {
        val file = profile.resolve("DevToolsActivePort")
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (System.nanoTime() < deadline && process.isAlive) {
            if (Files.isRegularFile(file)) {
                val port = runCatching {
                    Files.readAllLines(file).firstOrNull()?.trim()?.toIntOrNull()
                }.getOrNull()
                if (port != null && port > 0) return port
            }
            Thread.sleep(150)
        }
        return null
    }

    private fun findChromium(): File? {
        if (DesktopPlatform.isMac) return DesktopBrowserSignIn.preferred()?.executable?.toFile()
        val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        val candidates = if (windows) {
            val roots = listOfNotNull(
                System.getenv("PROGRAMFILES"),
                System.getenv("PROGRAMFILES(X86)"),
                System.getenv("LOCALAPPDATA"),
            )
            roots.flatMap { root ->
                listOf(
                    File(root, "Google/Chrome/Application/chrome.exe"),
                    File(root, "Microsoft/Edge/Application/msedge.exe"),
                    File(root, "Chromium/Application/chrome.exe"),
                )
            } + onPath(listOf("chrome.exe", "msedge.exe", "chromium.exe"))
        } else {
            onPath(listOf("google-chrome-stable", "google-chrome", "chromium",
                "chromium-browser", "microsoft-edge"))
        }
        return candidates.firstOrNull(File::isFile)
    }

    private fun onPath(names: List<String>): List<File> =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .filter(String::isNotBlank)
            .flatMap { folder -> names.map { File(folder, it) } }

    private class Cdp(http: HttpClient, endpoint: String) : AutoCloseable {
        private val messages = LinkedBlockingQueue<JsonObject>()
        private val deferredEvents = ArrayDeque<JsonObject>()
        private var nextId = 1
        private val fragments = StringBuilder()
        private val webSocket: WebSocket = http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(URI(endpoint), object : WebSocket.Listener {
                override fun onOpen(webSocket: WebSocket) {
                    webSocket.request(1)
                }

                override fun onText(
                    webSocket: WebSocket,
                    data: CharSequence,
                    last: Boolean,
                ): CompletionStage<*> {
                    synchronized(fragments) {
                        fragments.append(data)
                        if (last) {
                            val message = fragments.toString()
                            fragments.setLength(0)
                            runCatching { json.parseToJsonElement(message).jsonObject }
                                .getOrNull()?.let(messages::offer)
                        }
                    }
                    webSocket.request(1)
                    return CompletableFuture.completedFuture(null)
                }
            }).get(6, TimeUnit.SECONDS)

        fun command(
            method: String,
            parameters: JsonObject = JsonObject(emptyMap()),
            sessionId: String? = null,
        ): JsonObject {
            val id = nextId++
            val command = buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", parameters)
                if (sessionId != null) put("sessionId", sessionId)
            }
            webSocket.sendText(command.toString(), true).get(3, TimeUnit.SECONDS)
            val limit = System.nanoTime() + Duration.ofSeconds(6).toNanos()
            while (System.nanoTime() < limit) {
                val remainingMs = ((limit - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
                val response = messages.poll(remainingMs, TimeUnit.MILLISECONDS) ?: break
                val responseId = response["id"]?.jsonPrimitive?.intOrNull
                if (responseId == id) {
                    if (response["error"] != null) throw IllegalStateException(
                        "Chrome DevTools rejected " + method
                    )
                    return response["result"]?.jsonObject ?: JsonObject(emptyMap())
                }
                if (responseId == null) deferredEvents.addLast(response)
            }
            throw IllegalStateException("Chrome DevTools timed out on " + method)
        }

        fun pollEvent(timeoutMs: Long): JsonObject? {
            if (deferredEvents.isNotEmpty()) return deferredEvents.removeFirst()
            return messages.poll(timeoutMs, TimeUnit.MILLISECONDS)
        }

        override fun close() {
            runCatching { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done") }
        }
    }
}
