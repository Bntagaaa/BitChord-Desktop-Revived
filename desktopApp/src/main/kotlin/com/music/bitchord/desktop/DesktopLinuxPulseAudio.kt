package com.music.bitchord.desktop

import java.util.concurrent.TimeUnit

/**
 * PipeWire's PulseAudio compatibility API exposes real desktop sinks (including Bluetooth),
 * unlike Java Sound's ALSA mixer inventory. All commands are best-effort: machines without
 * pactl retain the original Java Sound path.
 */
internal object DesktopLinuxPulseAudio {
    const val PREFIX = "pulse-sink:"
    private val processId = ProcessHandle.current().pid().toString()

    private fun command(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("pactl") + args.toList())
            .redirectErrorStream(true).start()
        if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) return@runCatching null
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    fun sinks(): List<DesktopAudioDevice>? {
        val listing = command("list", "sinks") ?: return null
        val sections = Regex("(?m)^Sink #").split(listing).drop(1)
        return sections.mapNotNull { section ->
            val name = Regex("(?m)^\\s*Name: (.+)$").find(section)?.groupValues?.get(1)?.trim()
                ?: return@mapNotNull null
            val description = Regex("(?m)^\\s*Description: (.+)$")
                .find(section)?.groupValues?.get(1)?.trim().orEmpty()
            DesktopAudioDevice(PREFIX + name, description.ifBlank { name }, "PipeWire / PulseAudio")
        }.distinctBy { it.id }
    }

    /** Moves only streams owned by this app process, never other applications' audio. */
    fun route(selectedId: String) {
        val destination = if (selectedId.startsWith(PREFIX)) {
            selectedId.removePrefix(PREFIX)
        } else if (selectedId.isEmpty()) {
            command("get-default-sink")?.trim()?.takeIf { it.isNotEmpty() }
        } else null
        if (destination == null) return
        val sinkIndex = command("list", "short", "sinks")
            ?.lineSequence()
            ?.map { it.split('\t') }
            ?.firstOrNull { it.size > 1 && it[1] == destination }
            ?.firstOrNull()
        val inputs = command("list", "sink-inputs") ?: return
        val sections = Regex("(?m)^Sink Input #").split(inputs).drop(1)
        sections.forEach { section ->
            val inputId = section.takeWhile { it.isDigit() }
            if (inputId.isEmpty()) return@forEach
            val pid = Regex("application\\.process\\.id\\s*=\\s*\\\"(\\d+)\\\"")
                .find(section)?.groupValues?.get(1)
            val currentSink = Regex("(?m)^\\s*Sink: (\\d+)")
                .find(section)?.groupValues?.get(1)
            if (pid == processId && (sinkIndex == null || currentSink != sinkIndex)) {
                command("move-sink-input", inputId, destination)
            }
        }
    }

    fun snapshot(): String = command("list", "short", "sinks").orEmpty()
}
