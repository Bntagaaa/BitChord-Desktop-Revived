package com.music.bitchord.desktop

import com.music.bitchord.data.Http
import com.music.bitchord.data.innertube.StreamResolver
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_SIZE
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_context_free
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Reads/caches bounded windows. FFmpeg's native HTTP remains responsible for HLS/DASH/add-ons. */
internal class DesktopRangeStream(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val windowBytes: Int = DEFAULT_WINDOW,
    cacheIdentity: String = "",
    private val cache: DesktopAudioCache? = DesktopAudioCache.shared,
    private val cancelled: () -> Boolean = { false },
    private val requestTimeoutMs: Long = 6_000,
    private val networkRead: ((Long) -> Unit)? = null,
    private val prefetchNext: Boolean = false,
) {
    init { require(windowBytes >= 1 && (cache == null || windowBytes <= DesktopAudioCache.MAX_SPAN)) }
    @Volatile var unsupportedRange: Boolean = false
        private set
    var failure: IOException? = null
        private set
    var length: Long = -1L
        private set
    private var position = 0L
    private var window = ByteArray(0)
    private var windowStart = -1L
    private var prefetchedStart = -1L
    private var prefetchFuture: CompletableFuture<RangeResponse?>? = null
    private val rangeClient: OkHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }
    private val uri = URI.create(url)
    private val expiresAt = minOf(System.currentTimeMillis() + 24 * 60 * 60 * 1000L,
        Regex("(?:^|&)expire=(\\d+)(?:&|$)").find(uri.rawQuery.orEmpty())?.groupValues?.get(1)
            ?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000) ?: Long.MAX_VALUE)
    private val cacheKey = if (uri.userInfo != null || headers.keys.any { it.equals("Authorization", true) || it.equals("Cookie", true) }) null
        else DesktopAudioCache.identity("$cacheIdentity|$url|$windowBytes|" + headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).entries.joinToString())

    fun position(): Long = position
    fun seek(offset: Long) { position = offset.coerceAtLeast(0) }

    fun read(into: ByteArray, count: Int): Int {
        if (cancelled() || Thread.currentThread().isInterrupted) return -1
        if (count <= 0) return 0
        if (length >= 0 && position >= length) return -1
        if (!holds(position) && !fetchWindowFor(position)) return -1
        val offset = (position - windowStart).toInt()
        val taken = minOf(count, into.size, window.size - offset)
        if (taken <= 0) return -1
        System.arraycopy(window, offset, into, 0, taken)
        position += taken
        return taken
    }

    private fun holds(at: Long): Boolean = windowStart >= 0 && at >= windowStart && at < windowStart + window.size

    private fun fetchWindowFor(at: Long): Boolean {
        val start = at - (at % windowBytes)
        val key = if (cache == null) null else cacheKey?.takeIf { expiresAt > System.currentTimeMillis() }
        key?.let { cache?.read(it, start) }?.let {
            windowStart = start; length = it.total; window = it.bytes
            DesktopPlaybackDiagnostics.cacheHit.incrementAndGet()
            return holds(at)
        }
        DesktopPlaybackDiagnostics.cacheMiss.incrementAndGet()
        val end = start + windowBytes - 1
        if (cancelled() || Thread.currentThread().isInterrupted) return false
        val started = if (networkRead != null) System.nanoTime() else 0L
        val response = try {
            consumePrefetchOrFetch(start, end)
        } finally {
            networkRead?.invoke(System.nanoTime() - started)
        } ?: return false
        val status = response.statusCode
        if (status == 416) {
            val total = response.contentRange.orEmpty()
                .removePrefix("bytes */").toLongOrNull()
            if (total != null && at >= total) {
                length = total
                return false
            }
        }
        if (status != 200 && status != 206) {
            StreamResolver.onPlaybackRefused(url, status)
            failure = IOException("Audio range request returned HTTP $status")
            return false
        }
        val body = response.body
        val stated = response.contentRange.orEmpty()
        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(stated)
        if (status == 206) {
            val from = range?.groupValues?.get(1)?.toLongOrNull()
            val to = range?.groupValues?.get(2)?.toLongOrNull()
            val total = range?.groupValues?.get(3)?.toLongOrNull()
            if (from != start || to != start + body.size - 1 || total == null || total < start + body.size) {
                failure = IOException("Audio server returned an invalid byte range")
                return false
            }
            windowStart = start
            length = total
        } else {
            windowStart = 0; length = body.size.toLong()
        }
        window = body
        val policy = response.cacheControl.orEmpty().lowercase()
        val maxAge = Regex("(?:^|[, ])max-age=(\\d+)").find(policy)?.groupValues?.get(1)?.toLongOrNull()
        val immutableSource = uri.host.orEmpty().endsWith(".googlevideo.com") &&
            listOf("itag", "lmt", "clen", "expire").all { name -> Regex("(?:^|&)$name=[^&]+").containsMatchIn(uri.rawQuery.orEmpty()) }
        if (cache != null && key != null && (immutableSource || (maxAge ?: 0) > 0) && "no-store" !in policy && "private" !in policy && !response.hasSetCookie) {
            val expiry = minOf(expiresAt, System.currentTimeMillis() + (maxAge ?: 86400L).coerceIn(0, 86400) * 1000)
            cache.write(key, windowStart, body, length, expiry)
        }
        if (prefetchNext && status == 206 && length > windowStart + window.size) {
            schedulePrefetch(windowStart + windowBytes)
        }
        return holds(at)
    }

    private fun buildRangeRequest(start: Long, end: Long): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> runCatching { builder.header(name, value) } }
        return builder.header("Range", "bytes=$start-$end")
            .header("Accept-Encoding", "identity")
            .get()
            .build()
    }

    private fun consumePrefetchOrFetch(start: Long, end: Long): RangeResponse? {
        val inFlight = if (prefetchedStart == start) prefetchFuture else {
            prefetchFuture?.cancel(true)
            null
        }
        prefetchedStart = -1L
        prefetchFuture = null
        if (inFlight != null) {
            val prefetched = runCatching { inFlight.get(requestTimeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
            if (prefetched != null && prefetched.statusCode in listOf(200, 206)) return prefetched
        }
        return fetchRange(buildRangeRequest(start, end))
    }

    private fun schedulePrefetch(nextStart: Long) {
        if (cancelled() || prefetchedStart == nextStart) return
        prefetchFuture?.cancel(true)
        val nextEnd = minOf(nextStart + windowBytes - 1, length - 1)
        if (nextEnd < nextStart) return
        val request = buildRangeRequest(nextStart, nextEnd)
        prefetchedStart = nextStart
        prefetchFuture = enqueueRange(request)
            .handle { response, _ -> response }
    }

    fun close() {
        prefetchFuture?.cancel(true)
        prefetchFuture = null
        prefetchedStart = -1L
    }

    private fun fetchRange(request: Request): RangeResponse? {
        repeat(2) { attempt ->
            if (cancelled() || Thread.currentThread().isInterrupted) return null
            val callFuture = enqueueRange(request)
            try {
                val response = callFuture.get(requestTimeoutMs, TimeUnit.MILLISECONDS)
                if (attempt == 1 || response.statusCode !in 502..504) return response
            } catch (_: InterruptedException) {
                callFuture.cancel(true)
                Thread.currentThread().interrupt()
                return null
            } catch (_: TimeoutException) {
                callFuture.cancel(true)
                if (attempt == 1) failure = SocketTimeoutException("Audio range body timed out")
            } catch (e: ExecutionException) {
                if (unsupportedRange) return null
                if (attempt == 1) failure = IOException("Audio range request failed", e.cause)
            }
        }
        return null
    }

    private fun enqueueRange(request: Request): CompletableFuture<RangeResponse> {
        val future = CompletableFuture<RangeResponse>()
        val call = rangeClient.newCall(request)
        future.whenComplete { _, _ -> if (future.isCancelled) call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                future.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { res ->
                        val bodyBytes = readBoundedBytes(res, windowBytes) { unsupportedRange = true }
                        future.complete(
                            RangeResponse(
                                statusCode = res.code,
                                body = bodyBytes,
                                contentRange = res.header("Content-Range"),
                                cacheControl = res.header("Cache-Control"),
                                hasSetCookie = res.header("Set-Cookie") != null,
                            ),
                        )
                    }
                } catch (e: Throwable) {
                    future.completeExceptionally(e)
                }
            }
        })
        return future
    }

    private class RangeResponse(
        val statusCode: Int,
        val body: ByteArray,
        val contentRange: String?,
        val cacheControl: String?,
        val hasSetCookie: Boolean,
    )

    companion object {
        const val DEFAULT_WINDOW = 512 * 1024

        /** Cancels oversized/ignored range responses before they can allocate a whole track. */
        private fun readBoundedBytes(response: Response, limit: Int, onOversize: () -> Unit): ByteArray {
            val stream = response.body?.byteStream() ?: return ByteArray(0)
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            stream.use { input ->
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    if (buffer.size() + read > limit) {
                        if (response.code == 200) onOversize()
                        throw IOException("range response exceeded window")
                    }
                    buffer.write(chunk, 0, read)
                }
            }
            return buffer.toByteArray()
        }
    }
}

/**
 * Attaches a [DesktopRangeStream] to an FFmpeg [AVFormatContext] via custom [AVIOContext]
 * so both audio and video decoders share bounded HTTP `Range` reading and clean native disposal.
 */
internal class DesktopWindowedAvio(
    url: String,
    headers: Map<String, String>,
    windowBytes: Int = DesktopRangeStream.DEFAULT_WINDOW,
    cacheIdentity: String = "",
    cache: DesktopAudioCache? = DesktopAudioCache.shared,
    cancelled: () -> Boolean = { false },
    rangeRead: ((Long) -> Unit)? = null,
    prefetchNext: Boolean = false,
) : AutoCloseable {
    val source = DesktopRangeStream(
        url = url,
        headers = headers,
        windowBytes = windowBytes,
        cacheIdentity = cacheIdentity,
        cache = cache,
        cancelled = cancelled,
        networkRead = rangeRead,
        prefetchNext = prefetchNext,
    )
    private var readPacket: Read_packet_Pointer_BytePointer_int?
    private var seekPacket: Seek_Pointer_long_int?
    private var avio: AVIOContext?
    val formatContext: AVFormatContext = avformat_alloc_context()

    init {
        val scratch = ByteArray(IO_BUFFER_BYTES)
        val read = object : Read_packet_Pointer_BytePointer_int() {
            override fun call(opaque: Pointer?, buffer: BytePointer?, size: Int): Int {
                if (buffer == null) return AVERROR_EOF
                val taken = source.read(scratch, minOf(size, scratch.size))
                if (taken <= 0) return if (source.failure != null) AVERROR_IO else AVERROR_EOF
                buffer.put(scratch, 0, taken)
                return taken
            }
        }
        val seek = object : Seek_Pointer_long_int() {
            override fun call(opaque: Pointer?, offset: Long, whence: Int): Long {
                if (whence == AVSEEK_SIZE) return source.length
                val target = when (whence) {
                    SEEK_CUR -> source.position() + offset
                    SEEK_END -> source.length + offset
                    else -> offset
                }
                source.seek(target)
                return target
            }
        }
        val buffer = BytePointer(av_malloc(IO_BUFFER_BYTES.toLong()))
        val io = avio_alloc_context(buffer, IO_BUFFER_BYTES, 0, null, read, null, seek)
        formatContext.pb(io)
        readPacket = read
        seekPacket = seek
        avio = io
    }

    override fun close() {
        source.close()
        avio?.let {
            av_free(it.buffer())
            it.buffer(null as BytePointer?)
            avio_context_free(it)
        }
        avio = null
        readPacket?.deallocate()
        seekPacket?.deallocate()
        readPacket = null
        seekPacket = null
    }

    companion object {
        const val IO_BUFFER_BYTES = 64 * 1024
        const val VIDEO_WINDOW_BYTES = 1024 * 1024
        private const val AVERROR_IO = -5
        private const val SEEK_CUR = 1
        private const val SEEK_END = 2
    }
}
