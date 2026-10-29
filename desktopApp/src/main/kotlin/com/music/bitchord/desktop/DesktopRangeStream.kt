package com.music.bitchord.desktop

import com.music.bitchord.data.innertube.StreamResolver
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.io.ByteArrayOutputStream

/** Reads/caches bounded windows. FFmpeg's native HTTP remains responsible for HLS/DASH/add-ons. */
internal class DesktopRangeStream(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val windowBytes: Int = DEFAULT_WINDOW,
    cacheIdentity: String = "",
    private val cache: DesktopAudioCache = DesktopAudioCache.shared,
    private val cancelled: () -> Boolean = { false },
) {
    init { require(windowBytes in 1..DesktopAudioCache.MAX_SPAN) }
    var unsupportedRange: Boolean = false
        private set
    var length: Long = -1L
        private set
    private var position = 0L
    private var window = ByteArray(0)
    private var windowStart = -1L
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
        val key = cacheKey?.takeIf { expiresAt > System.currentTimeMillis() }
        key?.let { cache.read(it, start) }?.let {
            windowStart = start; length = it.total; window = it.bytes
            DesktopPlaybackDiagnostics.cacheHit.incrementAndGet()
            return holds(at)
        }
        DesktopPlaybackDiagnostics.cacheMiss.incrementAndGet()
        val end = start + windowBytes - 1
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
        headers.forEach { (name, value) -> runCatching { request.header(name, value) } }
        request.setHeader("Range", "bytes=$start-$end").setHeader("Accept-Encoding", "identity")
        if (cancelled() || Thread.currentThread().isInterrupted) return false
        val response = try {
            client.send(request.GET().build(), boundedBody(windowBytes) { unsupportedRange = true })
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (_: Exception) { return false }
        if (response.statusCode() !in listOf(200, 206)) {
            StreamResolver.onPlaybackRefused(url, response.statusCode())
            return false
        }
        val body = response.body()
        val stated = response.headers().firstValue("content-range").orElse("")
        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(stated)
        if (response.statusCode() == 206) {
            val (from, to, total) = range?.destructured ?: return false
            windowStart = from.toLongOrNull() ?: return false
            length = total.toLongOrNull() ?: return false
            if (windowStart != start || to.toLongOrNull() != start + body.size - 1 || length < start + body.size) return false
        } else {
            windowStart = 0; length = body.size.toLong()
        }
        window = body
        val policy = response.headers().firstValue("cache-control").orElse("").lowercase()
        val maxAge = Regex("(?:^|[, ])max-age=(\\d+)").find(policy)?.groupValues?.get(1)?.toLongOrNull()
        val immutableSource = uri.host.orEmpty().endsWith(".googlevideo.com") &&
            listOf("itag", "lmt", "clen", "expire").all { name -> Regex("(?:^|&)$name=[^&]+").containsMatchIn(uri.rawQuery.orEmpty()) }
        if (key != null && (immutableSource || (maxAge ?: 0) > 0) && "no-store" !in policy && "private" !in policy && response.headers().firstValue("set-cookie").isEmpty) {
            val expiry = minOf(expiresAt, System.currentTimeMillis() + (maxAge ?: 86400L).coerceIn(0, 86400) * 1000)
            cache.write(key, windowStart, body, length, expiry)
        }
        return holds(at)
    }

    companion object {
        const val DEFAULT_WINDOW = 512 * 1024
        private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build()

        /** Cancels oversized/ignored range responses before they can allocate a whole track. */
        private fun boundedBody(limit: Int, onOversize: () -> Unit) = HttpResponse.BodyHandler<ByteArray> {
            object : HttpResponse.BodySubscriber<ByteArray> {
                private val result = CompletableFuture<ByteArray>()
                private val bytes = ByteArrayOutputStream()
                private lateinit var subscription: Flow.Subscription
                override fun getBody() = result
                override fun onSubscribe(value: Flow.Subscription) { subscription = value; value.request(1) }
                override fun onNext(items: List<ByteBuffer>) {
                    for (item in items) {
                        if (bytes.size().toLong() + item.remaining() > limit) {
                            if (it.statusCode() == 200) onOversize()
                            subscription.cancel(); result.completeExceptionally(java.io.IOException("range response exceeded window")); return
                        }
                        val chunk = ByteArray(item.remaining()); item.get(chunk); bytes.write(chunk)
                    }
                    subscription.request(1)
                }
                override fun onError(error: Throwable) { result.completeExceptionally(error) }
                override fun onComplete() { result.complete(bytes.toByteArray()) }
            }
        }
    }
}
