package com.music.bitchord.reworked

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.*
import kotlinx.coroutines.*
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path

/** Original mobile MusicLink path rules, with JVM URI and desktop file handling. */
internal object MobileLinks {
    fun open(session: MobileSession, input: String) {
        val revision = MobileYouTubeAccounts.state.value.revision
        session.scope.launch {
            try {
                val text = input.trim()
                val file = runCatching { if (text.startsWith("file:")) Path.of(URI(text)) else Path.of(text) }.getOrNull()
                if (file != null && Files.isRegularFile(file) && MobileFiles.isAudioFile(file)) {
                    val song = withContext(Dispatchers.IO) { MobileFiles.inspect(file.toRealPath()) }
                    session.player.play(song); return@launch
                }
                OfflineSmoke.requireNetwork()
                val uri = URI(text)
                require(uri.scheme in setOf("http", "https")) { "Open a YouTube Music link or an audio file" }
                val host = uri.host?.lowercase()?.removePrefix("www.")
                val paths = uri.path.orEmpty().split('/').filter(String::isNotBlank)
                val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                }
                require(host in setOf("youtu.be", "youtube.com", "music.youtube.com", "m.youtube.com")) { "Use Import Spotify playlist for Spotify links" }
                val videoId = if (host == "youtu.be") paths.firstOrNull() else when (paths.firstOrNull()) {
                    "watch" -> query["v"]; "shorts", "embed", "v" -> paths.getOrNull(1); else -> null
                }
                if (revision != MobileYouTubeAccounts.state.value.revision) throw CancellationException("The YouTube profile changed before opening this link")
                if (!videoId.isNullOrBlank()) {
                    val song = MobileYouTubeAccounts.withListener(revision) { YtMusicRepository.trackLinks(videoId).getOrThrow() }
                    currentCoroutineContext().ensureActive()
                    if (revision != MobileYouTubeAccounts.state.value.revision) throw CancellationException("The YouTube profile changed while opening this link")
                    session.player.play(song.copy(playbackSource = "Shared link", playbackSourceType = PlaybackSourceType.SHARED_LINK))
                } else if (paths.firstOrNull() == "search" && !query["q"].isNullOrBlank()) session.search(query.getValue("q"))
                else {
                    val page = if (paths.firstOrNull() in setOf("channel", "browse")) paths.getOrNull(1) else query["list"]?.let { if (it.startsWith("VL")) it else "VL$it" }
                    require(!page.isNullOrBlank()) { "This link does not identify a song or collection" }
                    session.openBrowse(BrowseItem(page, "Loading…", "", null, when {
                        page.startsWith("UC") -> BrowseType.ARTIST; page.startsWith("MPRE") -> BrowseType.ALBUM; else -> BrowseType.PLAYLIST
                    }))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { session.notice(error.message ?: "Could not open the link") }
        }
    }
}

internal object DesktopWindowOptions {
    var keepPlaying by androidx.compose.runtime.mutableStateOf(DesktopStore.preferences.getBoolean("keep_playing", true))
        private set
    @JvmName("updateKeepPlaying")
    fun setKeepPlaying(value: Boolean) { keepPlaying = value; DesktopStore.preferences.putBoolean("keep_playing", value) }
}
