package com.music.bitchord.desktop

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PipeWire's PulseAudio compatibility API exposes real desktop sinks (including Bluetooth and
 * USB), unlike Java Sound's ALSA mixer inventory. Without pactl, Java Sound still works.
 */
internal object DesktopLinuxPulseAudio {
    const val PREFIX = "pulse-sink:"
    private val processId = ProcessHandle.current().pid().toString()
    private val routeGuard = Any()
    private var routePending = false
    private var routerStarted = false
    private var lastMoveFailure: String? = null
    private val router = Executors.newSingleThreadExecutor { action ->
        Thread(action, "BitChord-Audio-Route").apply { isDaemon = true }
    }

    /**
     * Both "System default" and named Pulse sinks use the same Java Sound default mixer.
     * Switching between them moves the existing Pulse sink-input, not the PCM line itself.
     */
    internal fun usesSystemMixer(id: String): Boolean = id.isEmpty() || id.startsWith(PREFIX)

    internal fun sameOutputLine(previous: String?, next: String, isLinux: Boolean): Boolean =
        previous != null && (previous == next ||
            (isLinux && usesSystemMixer(previous) && usesSystemMixer(next)))

    /**
     * Coalesce device changes, new PCM lines and the periodic hot-plug watcher into a single
     * worker. A slow pactl call cannot let an older route command win permanently.
     */
    fun requestRoute() {
        if (!DesktopPlatform.isLinux) return
        val schedule = synchronized(routeGuard) {
            routePending = true
            if (routerStarted) false else {
                routerStarted = true
                true
            }
        }
        if (schedule) router.execute(::processPendingRoutes)
    }

    private fun processPendingRoutes() {
        while (true) {
            val selected = synchronized(routeGuard) {
                if (!routePending) {
                    routerStarted = false
                    return
                }
                routePending = false
                DesktopAudioDevices.selected.value
            }
            runCatching { route(selected) }
                .onFailure { DesktopTrackLog.log("audio routing error: " + it.message) }
        }
    }

    /**
     * Drain stdout *while* waiting for pactl. Its verbose sink-input listing can exceed the OS
     * pipe buffer: waiting first deadlocks the child and silently loses every routing attempt.
     */
    private fun command(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("pactl") + args.toList())
            .redirectErrorStream(true).start()
        var output: String? = null
        val reader = Thread({
            output = runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull()
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

    fun sinks(): List<DesktopAudioDevice>? =
        command("list", "sinks")?.let(::parseSinks)

    internal fun parseSinks(listing: String): List<DesktopAudioDevice> =
        Regex("""(?m)^Sink #""").split(listing).drop(1).mapNotNull { section ->
            val name = Regex("""(?m)^\s*Name: (.+)$""")
                .find(section)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val description = Regex("""(?m)^\s*Description: (.+)$""")
                .find(section)?.groupValues?.get(1)?.trim().orEmpty()
            DesktopAudioDevice(PREFIX + name, description.ifBlank { name }, "PipeWire / PulseAudio")
        }.distinctBy { it.id }

    /** Null means pactl is unavailable; it must not cause a remembered choice to be erased. */
    fun sinkPresent(id: String): Boolean? =
        sinks()?.any { it.id == id }

    internal data class SinkInput(val index: String, val sinkIndex: String?)

    /** Match by process ID, never by the generic "java"/"ALSA plug-in" client name. */
    internal fun parseOwnedInputs(listing: String, pid: String): List<SinkInput> =
        Regex("""(?m)^Sink Input #""").split(listing).drop(1).mapNotNull { section ->
            val index = section.takeWhile { it.isDigit() }
            val owner = Regex("""application\.process\.id\s*=\s*"?(\d+)"?""")
                .find(section)?.groupValues?.get(1)
            if (index.isEmpty() || owner != pid) return@mapNotNull null
            val currentSink = Regex("""(?m)^\s*Sink:\s*(\d+)""")
                .find(section)?.groupValues?.get(1)
            SinkInput(index, currentSink)
        }

    /** Move only this JVM's live sink-inputs, leaving every other application's playback alone. */
    private fun route(selectedId: String) {
        val destination = when {
            selectedId.startsWith(PREFIX) -> selectedId.removePrefix(PREFIX)
            selectedId.isEmpty() -> command("get-default-sink")?.trim()
            else -> return // A raw ALSA mixer is opened directly, not routed through Pulse.
        }?.takeIf { it.isNotBlank() } ?: return

        val sinkIndex = command("list", "short", "sinks")
            ?.lineSequence()
            ?.map { it.split('\t') }
            ?.firstOrNull { it.size > 1 && it[1] == destination }
            ?.firstOrNull() ?: return
        val inputs = command("list", "sink-inputs") ?: return
        for (input in parseOwnedInputs(inputs, processId)) {
            if (DesktopAudioDevices.selected.value != selectedId) return
            if (input.sinkIndex == sinkIndex) continue
            if (command("move-sink-input", input.index, destination) == null) {
                val failure = "audio route failed: sink input " + input.index + " -> " + destination
                if (lastMoveFailure != failure) DesktopTrackLog.log(failure)
                lastMoveFailure = failure
            } else {
                lastMoveFailure = null
                DesktopTrackLog.log("audio output moved: sink input " + input.index + " -> " + destination)
            }
        }
    }

    fun snapshot(): String = command("list", "short", "sinks").orEmpty()
}
