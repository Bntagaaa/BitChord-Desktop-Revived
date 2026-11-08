package com.music.bitchord.reworked

import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.Window
import java.nio.file.Files
import java.security.MessageDigest

/** JNI transport has no Java callback reference: commands are drained by this lifecycle. */
object WindowsMediaNative {
    external fun attach(hwnd: Long): Long
    external fun update(handle: Long, title: String, artist: String, album: String, artwork: String, playing: Boolean, durationMs: Long, positionMs: Long): Boolean
    external fun poll(handle: Long): LongArray
    external fun detach(handle: Long)
    external fun backdrop(hwnd: Long, kind: Int, dark: Boolean): Boolean
    external fun wallpaper(): String
}

internal class WindowsMedia(window: Window, private val player: DesktopPlayback) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var handle = 0L
    val status = MutableStateFlow("Not connected")
    init {
        if (System.getProperty("os.name").startsWith("Windows")) scope.launch {
            try {
                load()
                val hwnd = withContext(Dispatchers.Main) {
                    withTimeout(10_000) { while (!window.isDisplayable) delay(50) }
                    Pointer.nativeValue(Native.getComponentPointer(window))
                }
                handle = WindowsMediaNative.attach(hwnd)
                check(handle != 0L) { "Windows media controls could not attach to the window" }
                status.value = "Connected"
                var previous: PlaybackState? = null
                while (isActive) {
                    val state = player.state.value.copy(positionMs = player.state.value.positionMs / 1000 * 1000, sampledAtNanos = 0, transitionProgress = 0f)
                    if (state != previous) {
                        check(WindowsMediaNative.update(handle, state.song?.title.orEmpty(), state.song?.artist.orEmpty(), state.song?.albumName.orEmpty(),
                            state.song?.thumbnailUrl.orEmpty(), state.isPlaying, state.durationMs, state.positionMs)) { "Windows media metadata or timeline update failed" }
                        previous = state
                    }
                    while (isActive) {
                        val command = WindowsMediaNative.poll(handle)
                        if (command.size < 2) break
                        when (command[0]) {
                            0L -> player.resume(); 1L -> player.pause(); 2L -> player.next(); 3L -> player.previous()
                            4L -> { player.pause(); player.seek(0) }; 5L -> player.seek(command[1]); 6L -> player.seek((player.state.value.positionMs + command[1]).coerceAtLeast(0))
                        }
                    }
                    delay(100)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { status.value = "Unavailable: ${error.message?.take(160)}" }
            finally { if (handle != 0L) { runCatching { WindowsMediaNative.detach(handle) }; handle = 0 } }
        }
    }
    override fun close() { scope.cancel() }
    companion object {
        @Synchronized internal fun load() {
            val bytes = WindowsMedia::class.java.getResourceAsStream("/native/windows-x86_64/bitchord_platform.dll")?.use { it.readBytes() }
                ?: error("Windows media library is missing")
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val directory = Files.createDirectories(DesktopStore.cache.resolve("native").resolve(hash))
            val file = directory.resolve("bitchord_platform.dll")
            if (!Files.exists(file)) {
                val part = Files.createTempFile(directory, "platform-", ".part")
                try { Files.write(part, bytes); atomicMove(part, file) } finally { Files.deleteIfExists(part) }
            }
            require(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).contentEquals(MessageDigest.getInstance("SHA-256").digest(bytes))) { "Native library integrity check failed" }
            System.load(file.toAbsolutePath().toString())
        }
    }
}
