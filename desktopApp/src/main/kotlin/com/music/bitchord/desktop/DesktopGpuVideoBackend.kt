package com.music.bitchord.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import kotlin.coroutines.CoroutineContext

/**
 * Optional native GPU video path. Never replaces BitChord's audio engine.
 *
 * MediaMP 0.5.1 renders libmpv textures straight into Skiko's Compose canvas on
 * supported Linux/Windows drivers. When the native bridge cannot initialize,
 * switch both video surfaces back to the existing FFmpeg renderer. For diagnosis,
 * use -Dbitchord.video.renderer=ffmpeg.
 */
internal object DesktopGpuVideoBackend {
    val legacy = MutableStateFlow(
        System.getProperty("bitchord.video.renderer", "mpv").equals("ffmpeg", ignoreCase = true),
    )

    private var prepared = false

    @Synchronized
    private fun ensurePrepared() {
        if (!prepared) {
            MpvMediampPlayer.prepareLibraries()
            prepared = true
        }
    }

    /**
     * Extract/load libmpv on an IO dispatcher at app startup. Before this, the
     * first Artist Motion/Video composable blocked the UI thread in remember()
     * while the native libraries were extracted from the distribution jars.
     */
    suspend fun prewarm() {
        if (legacy.value) return
        withContext(Dispatchers.IO) {
            runCatching { ensurePrepared() }.onFailure(::fallback)
        }
    }

    @Synchronized
    fun create(context: CoroutineContext): MpvMediampPlayer? {
        if (legacy.value) return null
        return runCatching {
            ensurePrepared()
            MpvMediampPlayer(Any(), context).let { player ->
                try {
                    val handle = player.impl as MPVHandle
                    // BitChord's audio engine owns all audio output and DSP.
                    handle.setPropertyString("aid", "no")
                    handle.setPropertyBoolean("mute", true)
                    handle.setPropertyString("hwdec", "auto-safe")
                    handle.setPropertyString("osc", "no")
                    // Bound the buffer without reconfiguring BitChord's audio pipeline.
                    handle.setPropertyString("demuxer-max-bytes", "8388608")
                    player
                } catch (failure: Throwable) {
                    // A partially configured native player still owns a render
                    // context and must be released before FFmpeg takes over.
                    player.close()
                    throw failure
                }
            }
        }.getOrElse {
            fallback(it)
            null
        }
    }

    /** One-way runtime fallback: never run both video decoders for the same media. */
    fun fallback(cause: Throwable) {
        if (!legacy.value) {
            DesktopTrackLog.log(
                "gpu video: falling back to FFmpeg (${cause.javaClass.simpleName}: ${cause.message})",
            )
            legacy.value = true
        }
    }

    fun configureHeaders(player: MpvMediampPlayer, headers: Map<String, String>) {
        if (headers.isEmpty()) return
        val handle = player.impl as MPVHandle
        headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.let { handle.setPropertyString("user-agent", it.value) }
        headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.let { handle.setPropertyString("referrer", it.value) }
        val extras = headers.entries.filterNot {
            it.key.equals("User-Agent", ignoreCase = true) ||
                it.key.equals("Referer", ignoreCase = true)
        }.filter { (key, value) ->
            key.matches(Regex("[A-Za-z0-9-]+")) &&
                !value.contains('\r') && !value.contains('\n')
        }
        if (extras.isNotEmpty()) {
            // mpv parses this comma-delimited option as a list of HTTP header fields.
            handle.setPropertyString(
                "http-header-fields",
                extras.joinToString(",") { (key, value) -> "$key: $value" },
            )
        }
    }
}
