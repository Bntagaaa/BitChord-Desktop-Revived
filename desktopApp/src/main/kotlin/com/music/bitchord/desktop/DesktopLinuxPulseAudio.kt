package com.music.bitchord.desktop

import java.util.concurrent.TimeUnit

/**
 * Enumerate the actual playback sinks advertised by PulseAudio / pipewire-pulse.
 *
 * The selected sink is opened directly by DesktopLinuxPulseSink. Java Sound's ALSA
 * default route is not necessarily visible as a Pulse sink-input, so do not try
 * to move ALSA streams with pactl; that approach could claim success without audio.
 */
internal object DesktopLinuxPulseAudio {
    const val PREFIX = "pulse-sink:"

    private fun command(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("pactl") + args.toList())
            .redirectErrorStream(true).start()
        var output: String? = null
        // A verbose pactl listing may fill stdout before process exit; drain concurrently.
        val reader = Thread({
            output = runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrNull()
        }, "BitChord-Pactl-Read").apply { isDaemon = true; start() }
        try {
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) return@runCatching null
            reader.join(300)
            if (reader.isAlive || process.exitValue() != 0) return@runCatching null
            output
        } finally {
            if (process.isAlive) process.destroyForcibly()
            if (reader.isAlive) {
                runCatching { process.inputStream.close() }
                reader.interrupt()
            }
        }
    }.getOrNull()

    fun sinks(): List<DesktopAudioDevice>? = command("list", "sinks")?.let(::parseSinks)

    internal fun parseSinks(listing: String): List<DesktopAudioDevice> =
        Regex("""(?m)^Sink #""").split(listing).drop(1).mapNotNull { section ->
            val name = Regex("""(?m)^\s*Name: (.+)$""")
                .find(section)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val description = Regex("""(?m)^\s*Description: (.+)$""")
                .find(section)?.groupValues?.get(1)?.trim().orEmpty()
            DesktopAudioDevice(PREFIX + name, description.ifBlank { name }, "PipeWire / PulseAudio")
        }.distinctBy { it.id }

    /** Unknown while pactl is absent: do not erase a saved output selection. */
    fun sinkPresent(id: String): Boolean? = sinks()?.any { it.id == id }

    fun snapshot(): String = command("list", "short", "sinks").orEmpty()
}
