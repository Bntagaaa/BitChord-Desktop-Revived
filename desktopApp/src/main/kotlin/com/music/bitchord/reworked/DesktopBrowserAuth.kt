package com.music.bitchord.reworked

import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.layout.BorderPane
import javafx.scene.web.WebView
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import netscape.javascript.JSObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** Fresh JVM WebView boundary for the mobile sign-in and Spotify token-harvest flow. */
internal object DesktopBrowserAuth {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var token: SpotifyTokens? = null
    data class SpotifyTokens(val bearer: String, val expiresAt: Long, val clientId: String?)

    fun spotifyClientId(): String? = token?.clientId

    internal fun ensureStarted() = start()
    fun close() {
        scope.cancel(); token = null
        if (started.get()) Platform.exit()
    }

    private fun start() {
        OfflineSmoke.requireNetwork()
        if (!started.compareAndSet(false, true)) return
        CookieHandler.setDefault(cookies)
        val initialize = Runnable { JFXPanel(); Platform.setImplicitExit(false) }
        try { if (SwingUtilities.isEventDispatchThread()) initialize.run() else SwingUtilities.invokeAndWait(initialize) }
        catch (error: Throwable) { started.set(false); throw error }
        DesktopStore.readText("spotify-cookie.txt")?.takeIf(String::isNotBlank)?.let { value ->
            val cookie = HttpCookie("sp_dc", value).apply { domain = ".spotify.com"; path = "/"; secure = true }
            cookies.cookieStore.add(URI("https://open.spotify.com"), cookie)
        }
    }

    fun youtubeSession(
        mode: YouTubeSessionMode = YouTubeSessionMode.SIGN_IN,
        accountId: String? = MobileYouTubeAccounts.state.value.activeAccountId,
        onConnected: () -> Unit = {},
        onError: (String) -> Unit = { message -> SwingUtilities.invokeLater {
            javax.swing.JOptionPane.showMessageDialog(null, message, "YouTube Music account", javax.swing.JOptionPane.INFORMATION_MESSAGE)
        } },
    ) {
        scope.launch {
            try {
                MobileYouTubeAccounts.restore().getOrThrow()
                start()
                val snapshot = if (mode == YouTubeSessionMode.SWITCH_CHANNEL) {
                    accountId?.let { MobileYouTubeAccounts.browserSnapshot(it) }
                        ?: error("Select a saved Google account before choosing its channel")
                } else null
                DesktopYouTubeBrowser.launch(cookies, mode, snapshot, scope, onConnected, onError)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { onError(error.message ?: "Could not open the YouTube Music account window") }
        }
    }

    /** Local Google/YouTube browser data only; never contacts Google's logout endpoint. */
    internal fun clearGoogleSession() = DesktopYouTubeBrowser.clearGoogleCookies(cookies)

    fun spotify(onConnected: () -> Unit) = signIn("Spotify", "https://open.spotify.com", "spotify.com") { _ ->
        val value = cookies.cookieStore.cookies.firstOrNull { it.name == "sp_dc" && !it.hasExpired() }?.value
            ?: error("Finish signing in to Spotify first")
        DesktopStore.writeText("spotify-cookie.txt", value); token = null; com.music.bitchord.data.canvas.SpotifyToken.invalidate(); onConnected()
    }

    fun discord(onToken: (String) -> Unit) = signIn("Discord", "https://discord.com/login", "discord.com", readCredential = { web ->
        val address = runCatching { URI(web.engine.location) }.getOrNull()
        require(address?.scheme == "https" && address.host == "discord.com" && !address.path.contains("/login") && !address.path.contains("/register")) { "Finish signing in to Discord first" }
        // Same completed-login capture as mobile DiscordLoginScreen. The credential is
        // returned only to the opt-in presence service and is never displayed or logged.
        val token = web.engine.executeScript("""
            (function(){try{var frame=document.createElement('iframe');document.body.appendChild(frame);
              var value=frame.contentWindow.localStorage.token;document.body.removeChild(frame);
              return value ? JSON.parse(value) : '';}catch(e){return '';}})()
        """.trimIndent()) as? String
        require(!token.isNullOrBlank() && token != "undefined" && token != "null") { "Discord is still loading. Try again in a moment." }
        token
    }, onConnected = onToken)

    private fun signIn(title: String, url: String, domain: String, readCredential: ((WebView) -> String)? = null, onConnected: (String) -> Unit) {
        scope.launch {
            start()
            SwingUtilities.invokeLater {
                val frame = JFrame("BitChord Reworked · $title")
                val panel = JFXPanel()
                frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                frame.add(panel); frame.setSize(1080, 780); frame.setLocationRelativeTo(null); frame.isVisible = true
                Platform.runLater {
                    val web = WebView()
                    web.engine.userDataDirectory = DesktopStore.root.resolve("browser").toFile().apply { mkdirs() }
                    val finish = Button("Use this account after signing in")
                    finish.setOnAction {
                        try {
                            val values = readCredential?.invoke(web) ?: cookies.cookieStore.cookies.filter {
                                !it.hasExpired() && (it.domain?.removePrefix(".") == domain || it.domain?.endsWith(".$domain") == true)
                            }.joinToString("; ") { "${it.name}=${it.value}" }
                            onConnected(values)
                            web.engine.load(null)
                            SwingUtilities.invokeLater { frame.dispose() }
                        } catch (e: Exception) { finish.text = e.message ?: "Sign-in is not complete" }
                    }
                    panel.scene = Scene(BorderPane(web, finish, null, null, null))
                    web.engine.load(url)
                    frame.addWindowListener(object : java.awt.event.WindowAdapter() {
                        override fun windowClosed(event: java.awt.event.WindowEvent) { Platform.runLater { web.engine.load(null) } }
                    })
                }
            }
        }
    }

    suspend fun spotifyTokens(): SpotifyTokens? {
        token?.takeIf { it.expiresAt > System.currentTimeMillis() + 30_000 }?.let { return it }
        if (DesktopStore.readText("spotify-cookie.txt").isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            start()
            val answer = CompletableDeferred<SpotifyTokens?>()
            var web: WebView? = null
            try {
                Platform.runLater {
                    val view = WebView(); web = view
                    val bridge = SpotifyTokenBridge { payload ->
                        runCatching {
                            val root = Json.parseToJsonElement(payload).jsonObject
                            val access = root["accessToken"]?.jsonPrimitive?.contentOrNull
                            val anonymous = root["isAnonymous"]?.jsonPrimitive?.booleanOrNull ?: false
                            if (!access.isNullOrBlank() && !anonymous) {
                                val value = SpotifyTokens(access, root["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()+3_600_000,
                                    root["clientId"]?.jsonPrimitive?.contentOrNull)
                                token = value; answer.complete(value)
                            }
                        }
                    }
                    fun hook() = runCatching {
                        (view.engine.executeScript("window") as JSObject).setMember("BitChordSpotifyTokenBridge", bridge)
                        view.engine.executeScript(SPOTIFY_HOOK)
                    }
                    view.engine.documentProperty().addListener { _, _, document -> if (document != null) hook() }
                    view.engine.loadWorker.stateProperty().addListener { _, _, state ->
                        if (state == Worker.State.RUNNING || state == Worker.State.SUCCEEDED) hook()
                        if (state == Worker.State.SUCCEEDED) runCatching {
                            view.engine.executeScript("fetch('/api/token?reason=transport&productType=web_player',{credentials:'include'}).then(function(r){return r.text()}).then(function(t){BitChordSpotifyTokenBridge.onTokenPayload(t)})")
                        }
                        if (state == Worker.State.FAILED) answer.complete(null)
                    }
                    view.engine.load("https://open.spotify.com/")
                }
                withTimeoutOrNull(25_000) { answer.await() }
            } finally { Platform.runLater { web?.engine?.load(null); web = null } }
        }
    }
}

/** Public method is required by JavaFX's JavaScript bridge. */
class SpotifyTokenBridge(private val accept: (String) -> Unit) {
    fun onTokenPayload(payload: String?) { if (!payload.isNullOrBlank()) accept(payload) }
}

// Same interception contract as mobile SpotifyToken.HOOK_SCRIPT, adapted to JavaFX's bridge.
private val SPOTIFY_HOOK = """
    (function(){
      if(window.__bitchordTokenHook)return; window.__bitchordTokenHook=true;
      function report(body){try{BitChordSpotifyTokenBridge.onTokenPayload(body);}catch(e){}}
      function isToken(u){return String(u).indexOf('/api/token')!==-1;}
      var original=window.fetch;
      if(original)window.fetch=function(input,init){var result=original.apply(this,arguments);if(isToken(input&&input.url?input.url:input)){result.then(function(r){r.clone().text().then(report);}).catch(function(){});}return result;};
      var open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.open=function(method,url){this.__bitchordUrl=url;return open.apply(this,arguments);};
      XMLHttpRequest.prototype.send=function(){var xhr=this;xhr.addEventListener('load',function(){if(isToken(xhr.__bitchordUrl))report(xhr.responseText);});return send.apply(this,arguments);};
    })();
""".trimIndent()
