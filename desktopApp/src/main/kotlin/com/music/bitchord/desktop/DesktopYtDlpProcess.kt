package com.music.bitchord.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** One short-lived extractor, never an audio downloader or a shell command. */
internal object DesktopYtDlpProcess {
    private val active = ConcurrentHashMap<Process, Path>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread({
            active.forEach { (process, directory) ->
                runCatching { process.descendants().use { it.forEach { child -> child.destroyForcibly() } } }
                runCatching { process.destroyForcibly().waitFor(2, TimeUnit.SECONDS) }
                runCatching { DesktopYtDlpFiles.removeDirectory(directory) }
            }
        }, "bitchord-ytdlp-shutdown"))
    }
    suspend fun run(
        command: List<String>,
        directory: Path,
        timeoutMillis: Long = 45_000,
        outputLimit: Int = 4 * 1024 * 1024,
    ): String = withContext(Dispatchers.IO) {
        require(timeoutMillis > 0 && outputLimit > 0)
        currentCoroutineContext().ensureActive()
        val process = try {
            ProcessBuilder(command).directory(directory.toFile()).apply {
                environment().remove("PYTHONPATH")
                environment().remove("PYTHONHOME")
                environment().remove("BITCHORD_YTMUSIC_COOKIE")
                listOf("TMPDIR", "TMP", "TEMP").forEach { environment()[it] = directory.toString() }
                environment()["PYTHONUTF8"] = "1"
                environment()["DENO_NO_UPDATE_CHECK"] = "1"
                environment()["DENO_DIR"] = directory.resolve("deno-cache").toString()
            }.start()
        } catch (_: IOException) {
            throw IOException("Cannot start the YouTube extractor. Check the yt-dlp/Deno installation and executable permissions.")
        }
        active[process] = directory
        val readers = Executors.newFixedThreadPool(2) { action ->
            Thread(action, "bitchord-ytdlp-output").apply { isDaemon = true }
        }
        val descendants = linkedSetOf<ProcessHandle>()
        var stdout: Future<String>? = null
        var stderr: Future<String>? = null
        try {
            process.outputStream.close()
            stdout = readers.submit<String> { readBounded(process.inputStream, outputLimit) }
            stderr = readers.submit<String> { readBounded(process.errorStream, 64 * 1024) }
            val started = System.nanoTime()
            while (true) {
                currentCoroutineContext().ensureActive()
                process.descendants().use { children -> children.forEach { descendants.add(it) } }
                if (stdout.isDone) checkedOutput(stdout)
                if (stderr.isDone) checkedOutput(stderr)
                if (process.waitFor(50, TimeUnit.MILLISECONDS)) break
                if ((System.nanoTime() - started) / 1_000_000 >= timeoutMillis) {
                    throw IOException("YouTube stream resolution timed out. Please try again.")
                }
            }
            currentCoroutineContext().ensureActive()
            val output = checkedOutput(stdout)
            val errors = checkedOutput(stderr)
            if (process.exitValue() != 0) throw IOException(failureMessage(errors))
            output
        } finally {
            runCatching { process.descendants().use { it.forEach { child -> descendants.add(child) } } }
            descendants.toList().asReversed().forEach { child ->
                runCatching { if (child.isAlive) child.destroyForcibly() }
            }
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            descendants.forEach { child ->
                val remaining = deadline - System.nanoTime()
                if (remaining > 0) runCatching { child.onExit().get(remaining, TimeUnit.NANOSECONDS) }
            }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            stdout?.cancel(true)
            stderr?.cancel(true)
            readers.shutdownNow()
            readers.awaitTermination(2, TimeUnit.SECONDS)
            active.remove(process)
        }
    }

    private fun checkedOutput(future: Future<String>): String = try {
        future.get(2, TimeUnit.SECONDS)
    } catch (_: Exception) {
        throw IOException("YouTube extractor output was too large, incomplete, or unreadable.")
    }

    private fun readBounded(input: InputStream, limit: Int): String = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            if (count > limit - output.size()) throw IOException("Extractor output limit exceeded")
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8)
    }

    internal fun failureMessage(stderr: String): String = when {
        stderr.contains("sign in", ignoreCase = true) || stderr.contains("cookies", ignoreCase = true) ->
            "YouTube requires a valid session. Reconnect your YouTube Music account and try again."
        stderr.contains("Requested format is not available", ignoreCase = true) ->
            "YouTube did not provide a direct audio stream at the selected quality. Try a different quality or update yt-dlp."
        stderr.contains("JavaScript", ignoreCase = true) || stderr.contains("deno", ignoreCase = true) ->
            "The YouTube JavaScript challenge could not be solved. Check or update the bundled yt-dlp and Deno runtime."
        else -> "YouTube stream extraction failed. Retry, check your connection, or update yt-dlp."
    }
}
