package com.music.bitchord.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URI
import kotlin.math.roundToInt

/** Desktop YouTube audio only. Search, account management, and other providers stay unchanged. */
internal object DesktopYtDlpResolver {
    private val gate = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val allowedHeaders = setOf(
        "user-agent", "accept", "accept-language", "referer", "origin",
        "x-youtube-client-name", "x-youtube-client-version",
    )

    suspend fun resolve(videoId: String, maxKbps: Int): DesktopStream = gate.withLock {
        withContext(Dispatchers.IO) {
            val tools = DesktopYtDlpRuntime.tools
            val directory = DesktopYtDlpFiles.privateDirectory()
            try {
                // The imported app session is the only cookie source. Never inspect other browsers.
                val cookieFile = DesktopYouTubeAuth.session?.cookie?.takeIf(String::isNotBlank)
                    ?.let { DesktopYtDlpFiles.writeCookies(directory, it) }
                val command = DesktopYtDlpCommand.build(tools.extractor, tools.deno, videoId, maxKbps, cookieFile)
                parse(DesktopYtDlpProcess.run(command, directory), videoId, maxKbps)
            } finally {
                runCatching { DesktopYtDlpFiles.removeDirectory(directory) }.onFailure {
                    DesktopTrackLog.log("youtube: could not remove an extractor temporary directory")
                }
            }
        }
    }

    internal fun parse(output: String, videoId: String, maxKbps: Int): DesktopStream {
        val info = try {
            json.parseToJsonElement(output) as? JsonObject ?: throw IOException()
        } catch (_: Exception) {
            throw IOException("YouTube extractor returned invalid metadata")
        }
        fun text(key: String): String? = (info[key] as? JsonPrimitive)?.contentOrNull
        fun number(key: String): Double? = (info[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        fun invalid(): Nothing = throw IOException("YouTube did not return a supported direct audio stream")
        if (text("id") != videoId || text("_type") in setOf("playlist", "multi_video")) invalid()
        if (text("vcodec") != "none" || text("acodec").isNullOrBlank() || text("acodec") == "none") invalid()
        if (text("protocol") != "https" || text("has_drm") == "true") invalid()
        if ((info["fragments"] as? JsonArray)?.isNotEmpty() == true ||
            (info["requested_formats"] as? JsonArray)?.isNotEmpty() == true) invalid()
        val url = text("url") ?: invalid()
        val uri = runCatching { URI(url) }.getOrNull() ?: invalid()
        val host = uri.host?.lowercase() ?: invalid()
        if (uri.scheme != "https" || uri.userInfo != null ||
            !(host == "googlevideo.com" || host.endsWith(".googlevideo.com"))) invalid()
        val container = text("ext")?.takeIf { it in setOf("webm", "m4a", "mp4", "ogg", "opus") } ?: invalid()
        val bitrate = number("abr")?.takeIf { it > 0 && it <= Int.MAX_VALUE.toDouble() }
        // Do not quietly replace the Low setting with a higher/unknown-bitrate rendition.
        if (maxKbps != Int.MAX_VALUE && (bitrate == null || bitrate > maxKbps)) invalid()
        val headers = (info["http_headers"] as? JsonObject).orEmpty().mapNotNull { (name, element) ->
            val value = (element as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (name.lowercase() !in allowedHeaders || value.any { it == '\r' || it == '\n' || it == '\u0000' }) null
            else name to value
        }.toMap()
        return DesktopStream(
            url = url,
            format = DesktopStreamFormat(codec = container, kbps = bitrate?.roundToInt()),
            headers = headers,
            sourceId = "youtube",
            durationSec = number("duration")?.takeIf { it > 0 && it <= Int.MAX_VALUE.toDouble() }?.toInt(),
            windowedReads = true,
        )
    }
}
