package com.music.bitchord.reworked

import com.music.bitchord.data.Http
import com.music.bitchord.data.innertube.PlayerClient
import com.music.bitchord.data.innertube.StreamResolver
import okhttp3.Call
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM port of mobile playback/ChunkedDataSource: bounded ranges, a source-specific cap,
 * shortened-response recovery, and refused streams handed back to the shared resolver.
 */
internal class RangedMediaInput(
    private val url: String,
    private val headers: Map<String, String>,
    private val cancelled: () -> Boolean,
) : AutoCloseable {
    var position: Long = 0
    var length: Long = URI(url).rawQuery?.split('&')?.firstOrNull { it.startsWith("clen=") }
        ?.substringAfter('=')?.toLongOrNull() ?: -1
        private set
    @Volatile var failure: IOException? = null
        private set
    @Volatile private var call: Call? = null
    private val closed = AtomicBoolean()
    private var bytes = ByteArray(0)
    private var start = -1L
    private val chunkBytes = minOf(1_048_576L, PlayerClient.rangeBytesFor(url)).coerceAtLeast(1).toInt()

    fun read(into: ByteArray, requested: Int): Int {
        if (requested <= 0) return 0
        if (closed.get() || cancelled() || (length >= 0 && position >= length)) return -1
        return try {
            repeat(3) {
                if (position !in start until start + bytes.size) fetch()
                val offset = (position - start).toInt()
                if (offset >= 0 && offset < bytes.size) {
                    val count = minOf(requested, bytes.size - offset)
                    bytes.copyInto(into, 0, offset, offset + count)
                    position += count
                    return count
                }
                if (length >= 0 && position >= length) return -1
            }
            throw IOException("The audio server returned an empty range repeatedly")
        } catch (error: IOException) {
            if (!closed.get() && !cancelled()) failure = error
            -1
        }
    }

    private fun fetch() {
        if (closed.get() || cancelled()) throw IOException("Playback cancelled")
        val desired = if (length >= 0) minOf(chunkBytes.toLong(), length - position).coerceAtLeast(0).toInt() else chunkBytes
        if (desired == 0) { bytes = ByteArray(0); start = position; return }
        val request = Request.Builder().url(url)
        headers.forEach { (name, value) -> request.header(name, value) }
        request.header("Accept-Encoding", "identity")
        request.header("Range", "bytes=$position-${position + desired - 1}")
        val active = Http.client.newCall(request.build())
        call = active
        if (closed.get() || cancelled()) active.cancel()
        try {
            active.execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 416) {
                        response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { length = it }
                        if (length >= 0 && position >= length) { bytes = ByteArray(0); start = position; return }
                    }
                    StreamResolver.onPlaybackRefused(url, response.code)
                    throw IOException("The audio server refused the stream (HTTP ${response.code})")
                }
                val contentRange = response.header("Content-Range")
                if (contentRange != null) {
                    val first = contentRange.substringAfter("bytes ").substringBefore('-').toLongOrNull()
                    if (first != position) throw IOException("The audio server returned the wrong byte range")
                    contentRange.substringAfterLast('/').toLongOrNull()?.let { length = it }
                } else if (response.code == 200) {
                    response.body.contentLength().takeIf { it >= 0 }?.let { length = it }
                }
                response.body.byteStream().use { input ->
                    if (contentRange == null && response.code == 200 && position > 0) input.skipNBytes(position)
                    val result = ByteArray(desired)
                    var count = 0
                    while (count < desired && !closed.get() && !cancelled()) {
                        val read = input.read(result, count, desired - count)
                        if (read < 0) break
                        if (read > 0) count += read
                    }
                    start = position
                    bytes = result.copyOf(count)
                    if (count < desired && contentRange == null && length < 0) length = position + count
                }
            }
        } finally {
            call = null
        }
    }

    override fun close() {
        closed.set(true)
        call?.cancel()
    }
}
