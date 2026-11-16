package com.music.bitchord.desktop

import java.nio.file.Path

internal object DesktopYtDlpCommand {
    fun build(
        executable: Path,
        deno: Path,
        videoId: String,
        maxKbps: Int,
        cookies: Path? = null,
    ): List<String> {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId)) { "Invalid YouTube video ID" }
        require(maxKbps > 0) { "Audio quality must be positive" }
        val ceiling = if (maxKbps == Int.MAX_VALUE) "" else "[abr<=$maxKbps]"
        val formats = "bestaudio[protocol=https][acodec=opus]$ceiling/bestaudio[protocol=https]$ceiling"
        return buildList {
            add(executable.toString())
            addAll(listOf(
                "--ignore-config", "--no-plugin-dirs", "--no-playlist", "--skip-download",
                "--dump-single-json", "--no-progress", "--no-cache-dir", "--no-remote-components",
                "--no-cookies-from-browser", "--no-js-runtimes", "--js-runtimes", "deno:$deno",
                "--socket-timeout", "12", "--retries", "1", "--extractor-retries", "1",
                "--format", formats,
            ))
            if (cookies == null) add("--no-cookies") else addAll(listOf("--cookies", cookies.toString()))
            add("--")
            add("https://music.youtube.com/watch?v=$videoId")
        }
    }
}
