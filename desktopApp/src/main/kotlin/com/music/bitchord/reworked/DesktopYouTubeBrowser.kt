package com.music.bitchord.reworked

import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox
import javafx.scene.web.WebView
import kotlinx.coroutines.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Original mobile BrowserSession / YtMusicLoginScreen capture semantics, hosted
 * by a new JavaFX window. Opening a page never saves a session: only an explicit
 * confirmation reads the live Music ytcfg, and the controller validates it first.
 */
internal object DesktopYouTubeBrowser {
    private val open = AtomicBoolean(false)
    private val music = URI("https://music.youtube.com")
    private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&prompt=select_account&continue=https%3A%2F%2Fmusic.youtube.com%2F"
    private val youtubeOrigins = listOf(music, URI("https://www.youtube.com"), URI("https://youtube.com"))

    fun launch(
        cookies: CookieManager,
        mode: YouTubeSessionMode,
        snapshot: Pair<String, String>?,
        scope: CoroutineScope,
        onConnected: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!open.compareAndSet(false, true)) {
            onError("A YouTube account window is already open. Finish or close it first.")
            return
        }
        val work = SupervisorJob(scope.coroutineContext[Job])
        val windowScope = CoroutineScope(scope.coroutineContext + work)
        var directory: Path? = null
        try {
            if (mode == YouTubeSessionMode.SWITCH_CHANNEL) {
                require(snapshot != null) { "Select a saved Google account before choosing its channel" }
                installGoogleCookies(cookies, snapshot.second)
            } else clearGoogleCookies(cookies)
            val browserRoot = DesktopStore.root.resolve("browser-sessions")
            Files.createDirectories(browserRoot)
            val attributes = if (Files.getFileStore(browserRoot).supportsFileAttributeView("posix"))
                arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) else emptyArray()
            directory = Files.createTempDirectory(browserRoot, "youtube-", *attributes)
            val ownedDirectory = directory
            SwingUtilities.invokeLater {
                val frame = JFrame("BitChord Reworked · YouTube Music account")
                try {
                    val panel = JFXPanel()
                    frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    frame.add(panel); frame.setSize(1080, 780); frame.setLocationRelativeTo(null)
                    Platform.runLater {
                        try {
                            val web = WebView()
                            web.engine.userDataDirectory = ownedDirectory.toFile()
                            val instruction = Label(if (mode == YouTubeSessionMode.SWITCH_CHANNEL)
                                "Choose the profile in YouTube Music's account menu, then confirm it here."
                            else "Sign in, choose your YouTube Music profile, then confirm it here.").apply { isWrapText = true }
                            val confirm = Button("Use this YouTube Music profile")
                            val status = Label().apply { isWrapText = true }
                            val controls = VBox(8.0, instruction, confirm, status).apply { style = "-fx-padding: 12;" }
                            var confirming = false
                            confirm.setOnAction {
                                if (!confirming) {
                                    val capture = runCatching {
                                        val header = cookies.get(music, emptyMap()).entries
                                            .filter { it.key.equals("Cookie", true) }.flatMap { it.value }.joinToString("; ")
                                        YouTubeCapture.parse(web.engine.location, header, web.engine.executeScript(YouTubeCapture.probeScript) as? String)
                                    }.getOrElse { error -> status.text = error.message ?: "The account is still loading"; return@setOnAction }
                                    confirming = true; confirm.isDisable = true; status.text = "Confirming this profile with YouTube Music…"
                                    windowScope.launch {
                                        val result = MobileYouTubeAccounts.acceptCapture(capture, mode, snapshot?.first)
                                        if (result.isSuccess) {
                                            SwingUtilities.invokeLater { onConnected(); frame.dispose() }
                                        } else Platform.runLater {
                                            confirming = false; confirm.isDisable = false
                                            status.text = result.exceptionOrNull()?.message ?: "This profile could not be confirmed. Try again."
                                        }
                                    }
                                }
                            }
                            web.engine.loadWorker.stateProperty().addListener { _, _, state ->
                                if (state == Worker.State.FAILED && !confirming) {
                                    status.text = "This page could not load. Check the connection and reopen the account window."
                                }
                            }
                            frame.addWindowListener(object : WindowAdapter() {
                                override fun windowClosed(event: WindowEvent) {
                                    work.cancel()
                                    Platform.runLater {
                                        web.engine.load(null)
                                        // No process-wide browser logout is sent. Other saved
                                        // accounts and other services keep their sessions.
                                        scope.launch { removeOwnedDirectory(ownedDirectory) }
                                    }
                                    open.set(false)
                                }
                            })
                            panel.scene = Scene(BorderPane(web, controls, null, null, null))
                            web.engine.load(if (mode == YouTubeSessionMode.SIGN_IN) LOGIN_URL else music.toString())
                            SwingUtilities.invokeLater { frame.isVisible = true }
                        } catch (_: Exception) {
                            work.cancel(); open.set(false)
                            scope.launch { removeOwnedDirectory(ownedDirectory) }
                            SwingUtilities.invokeLater { frame.dispose(); onError("The YouTube account browser could not start. Reopen it to try again.") }
                        }
                    }
                } catch (_: Exception) {
                    work.cancel(); open.set(false)
                    scope.launch { removeOwnedDirectory(ownedDirectory) }
                    frame.dispose(); onError("The YouTube account browser could not start. Reopen it to try again.")
                }
            }
        } catch (error: Exception) {
            work.cancel(); open.set(false)
            directory?.let { path -> scope.launch { removeOwnedDirectory(path) } }
            onError(error.message ?: "The YouTube account browser could not start")
        }
    }

    /** Domain-scoped removal preserves Spotify, Discord and other browser logins. */
    internal fun clearGoogleCookies(manager: CookieManager) {
        val store = manager.cookieStore
        val origins = store.urIs.toList()
        // JDK CookieStore normalizes its URI index to http:// even for secure
        // cookies. Query the HTTPS origin or host-only secure cookies disappear
        // from get(uri) and would incorrectly survive sign-out.
        val googleOrigins = origins.filter { isGoogleHost(it.host) }.map { URI("https", null, it.host, -1, "/", null, null) }
        val entries = store.cookies.toList().filter { cookie ->
            isGoogleHost(cookie.domain?.removePrefix(".")) || googleOrigins.any { uri -> store.get(uri).any { it == cookie } }
        }
        for (cookie in entries) {
            // JDK stores can retain a cookie with several URI associations.
            origins.forEach { origin -> store.remove(origin, cookie) }
            store.remove(null, cookie)
        }
    }

    internal fun installGoogleCookies(manager: CookieManager, header: String) {
        val entries = YouTubeCapture.cookieEntries(header)
        require(YouTubeCapture.hasApiSid(header)) { "This saved Google session is incomplete. Add the account again." }
        clearGoogleCookies(manager)
        youtubeOrigins.forEach { origin -> entries.forEach { (name, value) ->
            manager.cookieStore.add(origin, HttpCookie(name, value).apply { version = 0; path = "/"; secure = true })
        } }
    }

    private fun isGoogleHost(host: String?): Boolean = host?.lowercase()?.let {
        it == "google.com" || it.endsWith(".google.com") || it == "youtube.com" || it.endsWith(".youtube.com")
    } == true

    private suspend fun removeOwnedDirectory(directory: Path) {
        // JavaFX may briefly hold a Windows WebKit file after load(null).
        repeat(3) { attempt ->
            if (attempt > 0) delay(300)
            val removed = runCatching {
                if (Files.exists(directory)) Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }.isSuccess
            if (removed) return
        }
    }
}
